/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.data.framework

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.rtbishop.look4sat.core.domain.repository.IRadioController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class Ic705Controller(
    private val bluetoothManager: BluetoothManager,
    private val deviceAddress: String
) : IRadioController {

    private val tag = "IC705"
    private val sppId: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    private val ioMutex = Mutex()
    private val responseTimeoutMs = 500L

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var onVfoB = false

    override var isConnected: Boolean = false
        private set

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (isConnected) return@withContext true
        if (deviceAddress.isBlank()) return@withContext false
        try {
            val device = bluetoothManager.adapter.getRemoteDevice(deviceAddress)
            val btSocket = device.createInsecureRfcommSocketToServiceRecord(sppId)
            btSocket.connect()
            socket = btSocket
            outputStream = btSocket.outputStream
            inputStream = btSocket.inputStream
            isConnected = true
            onVfoB = false
            Log.i(tag, "Connected to $deviceAddress")
            true
        } catch (e: Exception) {
            Log.e(tag, "Connect error: ${e.message}")
            isConnected = false
            false
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                inputStream?.close()
                outputStream?.close()
                socket?.close()
            } catch (e: Exception) {
                Log.e(tag, "Disconnect error: ${e.message}")
            } finally {
                inputStream = null
                outputStream = null
                socket = null
                isConnected = false
                Log.i(tag, "Disconnected from $deviceAddress")
            }
        }
    }

    override suspend fun setFrequency(frequencyHz: Long): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            sendAndExpectAck(Ic705CivProtocol.buildSetFreqCommand(frequencyHz))
        }
    }

    override suspend fun setMode(mode: String): Boolean = withContext(Dispatchers.IO) {
        val cmd = Ic705CivProtocol.buildSetModeCommand(mode) ?: return@withContext false
        ioMutex.withLock { sendAndExpectAck(cmd) }
    }

    override suspend fun setCtcssMode(enabled: Boolean): Boolean {
        // IC-705 CTCSS is set via repeater tone settings (CI-V 0x1A)
        // Simplified: not implemented for initial version
        return true
    }

    override suspend fun setCtcssTone(toneHz: Double): Boolean {
        // IC-705 CTCSS tone setting via CI-V 0x1A sub-commands
        // Simplified: not implemented for initial version
        return true
    }

    override suspend fun readFrequencyAndMode(): Pair<Long, String>? = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            // Read frequency
            val freqFrame = sendAndReadResponse(Ic705CivProtocol.buildReadFreqCommand())
                ?: return@withContext null
            val freq = Ic705CivProtocol.parseFreqResponse(freqFrame)
                ?: return@withContext null

            // Read mode
            val modeFrame = sendAndReadResponse(Ic705CivProtocol.buildReadModeCommand())
                ?: return@withContext null
            val mode = Ic705CivProtocol.parseModeResponse(modeFrame)
                ?: return@withContext null

            Pair(freq, mode)
        }
    }

    override suspend fun pttOn(): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock { sendAndExpectAck(Ic705CivProtocol.buildPttOnCommand()) }
    }

    override suspend fun pttOff(): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock { sendAndExpectAck(Ic705CivProtocol.buildPttOffCommand()) }
    }

    override suspend fun toggleVfo(): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            onVfoB = !onVfoB
            sendAndExpectAck(Ic705CivProtocol.buildSelectVfoCommand(onVfoB))
        }
    }

    override suspend fun setSplit(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock { sendAndExpectAck(Ic705CivProtocol.buildSplitCommand(enabled)) }
    }

    /**
     * IC-705 can set frequency on a specific VFO without switching (Cmd 0x25).
     * vfo: 0 = selected (active) VFO, 1 = unselected VFO
     */
    suspend fun setFrequencyOnVfo(unselected: Boolean, frequencyHz: Long): Boolean =
        withContext(Dispatchers.IO) {
            ioMutex.withLock {
                sendAndExpectAck(
                    Ic705CivProtocol.buildSetFreqOnVfoCommand(unselected, frequencyHz)
                )
            }
        }

    /**
     * Select VFO A or B explicitly (not a toggle).
     */
    suspend fun selectVfo(vfoB: Boolean): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            onVfoB = vfoB
            sendAndExpectAck(Ic705CivProtocol.buildSelectVfoCommand(vfoB))
        }
    }

    // --- Internal I/O ---

    private fun sendFrame(frame: ByteArray): Boolean {
        return try {
            outputStream?.write(frame) ?: return false
            outputStream?.flush()
            true
        } catch (e: Exception) {
            Log.e(tag, "Send error: ${e.message}")
            isConnected = false
            false
        }
    }

    /**
     * Read a complete CI-V frame from the input stream.
     * Reads bytes until 0xFD is found or timeout.
     */
    private fun readFrame(): ByteArray? {
        return try {
            val input = inputStream ?: return null
            val buffer = mutableListOf<Byte>()
            val deadline = System.currentTimeMillis() + responseTimeoutMs

            while (System.currentTimeMillis() < deadline) {
                if (input.available() > 0) {
                    val b = input.read()
                    if (b < 0) return null
                    buffer.add(b.toByte())
                    if (b.toByte() == Ic705CivProtocol.EOM && buffer.size >= 6) {
                        return buffer.toByteArray()
                    }
                } else {
                    Thread.sleep(5)
                }
            }
            if (buffer.isNotEmpty()) {
                Log.w(tag, "Read timeout, partial frame: ${buffer.size} bytes")
            }
            null
        } catch (e: Exception) {
            Log.e(tag, "Read error: ${e.message}")
            isConnected = false
            null
        }
    }

    /**
     * Drain any CI-V echo or transceive frames that the radio sends unsolicited.
     * The IC-705 may echo our command back before sending the ACK.
     */
    private fun drainEcho() {
        try {
            val input = inputStream ?: return
            Thread.sleep(10)
            while (input.available() > 0) {
                val frame = readFrame() ?: break
                // Skip echoed commands (addressed to the radio, not to us)
                if (frame.size >= 4 && frame[2] == Ic705CivProtocol.RADIO_ADDR) continue
                // If we accidentally read a response, we lost it — log it
                Log.w(tag, "Drained unexpected frame: ${frame.size} bytes")
            }
        } catch (_: Exception) { }
    }

    private fun sendAndExpectAck(frame: ByteArray): Boolean {
        if (!sendFrame(frame)) return false
        // Read response frames until we get ACK/NAK (skip echoes)
        repeat(3) {
            val response = readFrame() ?: return false
            if (Ic705CivProtocol.isAck(response)) return true
            if (Ic705CivProtocol.isNak(response)) return false
            // Otherwise it's an echo or transceive frame — continue reading
        }
        return false
    }

    private fun sendAndReadResponse(frame: ByteArray): ByteArray? {
        if (!sendFrame(frame)) return null
        // Read response frames, skip echoes, return data frame
        repeat(3) {
            val response = readFrame() ?: return null
            if (Ic705CivProtocol.isNak(response)) return null
            // Data response is addressed to us (CTRL_ADDR) and has the command byte
            if (response.size >= 6 && response[2] == Ic705CivProtocol.CTRL_ADDR) {
                return response
            }
            // Otherwise it's an echo — continue
        }
        return null
    }
}
