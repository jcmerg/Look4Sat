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
import android.util.Log
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.repository.IRadioController
import com.rtbishop.look4sat.core.domain.repository.IRadioTrackingService
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.repository.RadioTrackingState
import com.rtbishop.look4sat.core.domain.utility.TransponderMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RadioTrackingService(
    private val appScope: CoroutineScope,
    private val bluetoothManager: BluetoothManager,
    private val satelliteRepo: ISatelliteRepo,
    private val settingsRepo: ISettingsRepo
) : IRadioTrackingService {

    private val tag = "RadioTracking"
    private val _state = MutableStateFlow(RadioTrackingState())
    override val state: StateFlow<RadioTrackingState> = _state

    private var txController: IRadioController? = null
    private var rxController: IRadioController? = null
    private var trackingJob: Job? = null
    private var onVfoB = false // tracks current VFO state in simplex mode

    private fun createController(address: String): IRadioController {
        val model = settingsRepo.radioControlSettings.value.radioModel
        return if (model.startsWith("Icom")) {
            Ic705Controller(bluetoothManager, address)
        } else {
            Ft817Controller(bluetoothManager, address)
        }
    }

    /** Switch to VFO A if not already there. */
    private suspend fun ensureVfoA(radio: IRadioController) {
        if (onVfoB) { radio.toggleVfo(); onVfoB = false }
    }

    /** Switch to VFO B if not already there. */
    private suspend fun ensureVfoB(radio: IRadioController) {
        if (!onVfoB) { radio.toggleVfo(); onVfoB = true }
    }

    override suspend fun connectRadios() {
        // Disconnect old controllers if any
        txController?.disconnect()
        rxController?.disconnect()

        // Read current addresses from settings
        val rcSettings = settingsRepo.radioControlSettings.value
        val simplex = rcSettings.isSimplex
        val txAddr = rcSettings.txRadioAddress
        val rxAddr = rcSettings.rxRadioAddress

        Log.i(tag, "Connecting mode=${rcSettings.operatingMode} TX=$txAddr RX=$rxAddr")

        if (simplex) {
            // Simplex: single radio on txRadioAddress, split VFO A/B
            if (txAddr.isBlank()) {
                _state.update { it.copy(errorMessage = "No radio address configured. Set it in Settings → FT-817.") }
                return
            }
            val radio = createController(txAddr)
            txController = radio
            rxController = null

            _state.update { it.copy(errorMessage = null) }
            val ok = radio.connect()
            if (ok) {
                onVfoB = false // assume VFO A after connect
                Log.i(tag, "Connected to $txAddr, starting on VFO A")
            }
            _state.update {
                it.copy(
                    txConnected = ok,
                    rxConnected = ok,
                    errorMessage = if (!ok) "Could not connect to radio ($txAddr)" else null
                )
            }
        } else {
            // Duplex: two separate radios
            if (txAddr.isBlank() && rxAddr.isBlank()) {
                _state.update { it.copy(errorMessage = "No radio addresses configured. Set them in Settings → FT-817.") }
                return
            }
            val tx = createController(txAddr)
            val rx = createController(rxAddr)
            txController = tx
            rxController = rx

            _state.update { it.copy(errorMessage = null) }
            val txOk = if (txAddr.isNotBlank()) tx.connect() else false
            val rxOk = if (rxAddr.isNotBlank()) rx.connect() else false
            _state.update {
                it.copy(
                    txConnected = txOk,
                    rxConnected = rxOk,
                    errorMessage = when {
                        !txOk && !rxOk -> "Could not connect to TX and RX radios"
                        !txOk -> "Could not connect to TX radio ($txAddr)"
                        !rxOk -> "Could not connect to RX radio ($rxAddr)"
                        else -> null
                    }
                )
            }
        }
    }

    override suspend fun disconnectRadios() {
        stopTracking()
        val simplex = settingsRepo.radioControlSettings.value.isSimplex
        if (simplex) {
            txController?.setSplit(false)
            Log.i(tag, "Split mode disabled")
        }
        txController?.disconnect()
        rxController?.disconnect()
        txController = null
        rxController = null
        _state.update {
            it.copy(
                txConnected = false,
                rxConnected = false,
                isActive = false
            )
        }
    }

    override fun startTracking(pass: OrbitalPass, transponder: SatRadio, txBaseFreqHz: Long?) {
        _state.update {
            it.copy(
                isActive = true,
                currentPass = pass,
                selectedTransponder = transponder,
                txBaseFrequencyHz = txBaseFreqHz
            )
        }
        trackingJob?.cancel()
        val simplex = settingsRepo.radioControlSettings.value.isSimplex
        trackingJob = appScope.launch {
            val tx = txController
            val rx = rxController
            val txMode = transponder.uplinkMode
            val rxMode = transponder.downlinkMode
                ?: transponder.uplinkMode?.let {
                    TransponderMapper.mapUplinkModeToDownlinkMode(it, transponder.isInverted)
                }

            if (simplex && tx != null && tx.isConnected) {
                // Simplex: set up both VFOs, then enable split
                val ic705 = tx as? Ic705Controller
                if (ic705 != null) {
                    // IC-705: select VFOs directly
                    ic705.selectVfo(false) // VFO A
                    if (rxMode != null) {
                        tx.setMode(rxMode)
                        Log.i(tag, "Simplex VFO A (RX) mode set to $rxMode")
                    }
                    val initRxFreq = if (txBaseFreqHz != null) {
                        TransponderMapper.mapUplinkToDownlink(txBaseFreqHz, transponder)
                    } else {
                        transponder.downlinkLow
                    }
                    initRxFreq?.let { tx.setFrequency(it) }

                    ic705.selectVfo(true) // VFO B
                    if (txMode != null) {
                        tx.setMode(txMode)
                        Log.i(tag, "Simplex VFO B (TX) mode set to $txMode")
                    }
                    if (txMode?.uppercase() == "FM") {
                        _state.value.ctcssTone?.let { tone ->
                            tx.setCtcssTone(tone)
                            tx.setCtcssMode(true)
                        }
                    }
                    txBaseFreqHz?.let { tx.setFrequency(it) }
                    ic705.selectVfo(false) // back to VFO A
                } else {
                    // FT-817: use toggle-based VFO switching
                    ensureVfoA(tx)
                    if (rxMode != null) {
                        tx.setMode(rxMode)
                        Log.i(tag, "Simplex VFO A (RX) mode set to $rxMode")
                    }
                    val initRxFreq = if (txBaseFreqHz != null) {
                        TransponderMapper.mapUplinkToDownlink(txBaseFreqHz, transponder)
                    } else {
                        transponder.downlinkLow
                    }
                    initRxFreq?.let { tx.setFrequency(it) }

                    ensureVfoB(tx)
                    if (txMode != null) {
                        tx.setMode(txMode)
                        Log.i(tag, "Simplex VFO B (TX) mode set to $txMode")
                    }
                    if (txMode?.uppercase() == "FM") {
                        _state.value.ctcssTone?.let { tone ->
                            tx.setCtcssTone(tone)
                            tx.setCtcssMode(true)
                        }
                    }
                    txBaseFreqHz?.let { tx.setFrequency(it) }
                    ensureVfoA(tx)
                }
                tx.setSplit(true)
                Log.i(tag, "Split mode enabled after VFO setup")
            } else {
                // Duplex: set modes on separate radios
                if (tx != null && tx.isConnected && txMode != null) {
                    tx.setMode(txMode)
                    Log.i(tag, "TX mode set to $txMode")
                }
                if (rx != null && rx.isConnected && rxMode != null) {
                    rx.setMode(rxMode)
                    Log.i(tag, "RX mode set to $rxMode")
                }
                if (txMode?.uppercase() == "FM") {
                    _state.value.ctcssTone?.let { tone ->
                        tx?.setCtcssTone(tone)
                        tx?.setCtcssMode(true)
                    }
                }
            }
            _state.update { it.copy(txMode = txMode, rxMode = rxMode) }

            var lastSetTxFreq = 0.0
            var lastSetRxFreq = 0.0
            var tuningRadio = "" // "", "tx", or "rx" - which radio the user is tuning
            var lastReadFreq = 0L
            var stableCount = 0
            var tickCount = 0

            while (isActive) {
                val currentState = _state.value
                if (!currentState.isActive) break

                val satPass = currentState.currentPass ?: break
                val xpdr = currentState.selectedTransponder ?: break
                var txBaseFreq = currentState.txBaseFrequencyHz
                val stationPos = settingsRepo.stationPosition.value
                val timeNow = System.currentTimeMillis()

                val pos = satelliteRepo.getPosition(satPass.orbitalObject, stationPos, timeNow)
                val hasUplink = txBaseFreq != null

                if (simplex) {
                    // --- Simplex: single radio, VFO A/B switching ---
                    val radio = tx
                    if (radio != null && radio.isConnected) {
                        if (tuningRadio.isNotEmpty()) {
                            // User is tuning on VFO A (RX): read and wait for stabilization
                            val readResult = radio.readFrequencyAndMode()
                            if (readResult != null) {
                                val (freq, _) = readResult
                                if (kotlin.math.abs(freq - lastReadFreq) <= 20) {
                                    stableCount++
                                } else {
                                    stableCount = 0
                                    lastReadFreq = freq
                                }
                                if (stableCount >= 1) {
                                    // Apply user's tuning delta directly to txBase
                                    val rxDelta = freq - lastSetRxFreq.toLong()
                                    val currentTxBase = _state.value.txBaseFrequencyHz
                                    if (currentTxBase != null) {
                                        val newTxBase = if (xpdr.isInverted) {
                                            currentTxBase - rxDelta
                                        } else {
                                            currentTxBase + rxDelta
                                        }
                                        if (newTxBase > 0) {
                                            txBaseFreq = newTxBase
                                            _state.update { it.copy(txBaseFrequencyHz = newTxBase) }
                                            Log.i(tag, "Simplex tuning done → delta=$rxDelta, txBase=$newTxBase")
                                        }
                                    }
                                    tuningRadio = ""
                                    stableCount = 0
                                    lastSetRxFreq = 0.0
                                    lastSetTxFreq = 0.0
                                }
                            }
                        } else {
                            // Dial feedback: read VFO A before setting, detect user tuning
                            if (lastSetRxFreq > 0.0) {
                                ensureVfoA(radio)
                                val readResult = radio.readFrequencyAndMode()
                                if (readResult != null) {
                                    val (actualRxFreq, _) = readResult
                                    if (kotlin.math.abs(actualRxFreq - lastSetRxFreq) >= 20.0) {
                                        tuningRadio = "rx"
                                        lastReadFreq = actualRxFreq
                                        stableCount = 0
                                        Log.i(tag, "Simplex tuning detected (read=$actualRxFreq, lastSet=$lastSetRxFreq)")
                                    }
                                }
                            }

                            // Compute Doppler-corrected frequencies (after tuning may have updated txBaseFreq)
                            val txRadioFreqNow = txBaseFreq?.let { pos.getUplinkFreq(it) }
                            val rxBaseFreqNow = if (txBaseFreq != null) {
                                TransponderMapper.mapUplinkToDownlink(txBaseFreq, xpdr)
                            } else {
                                xpdr.downlinkLow
                            }
                            val rxRadioFreqNow = rxBaseFreqNow?.let { pos.getDownlinkFreq(it) }

                            // Set frequencies (skip if user is tuning)
                            if (tuningRadio.isEmpty()) {
                                val ic705 = radio as? Ic705Controller
                                if (ic705 != null) {
                                    // IC-705: set both VFOs without toggling (Cmd 0x25)
                                    if (rxRadioFreqNow != null) {
                                        ic705.setFrequencyOnVfo(false, rxRadioFreqNow)
                                        lastSetRxFreq = rxRadioFreqNow.toDouble()
                                    }
                                    if (txRadioFreqNow != null) {
                                        ic705.setFrequencyOnVfo(true, txRadioFreqNow)
                                        lastSetTxFreq = txRadioFreqNow.toDouble()
                                    }
                                } else {
                                    // FT-817: toggle VFO A/B to set frequencies
                                    if (rxRadioFreqNow != null) {
                                        ensureVfoA(radio)
                                        radio.setFrequency(rxRadioFreqNow)
                                        lastSetRxFreq = rxRadioFreqNow.toDouble()
                                    }
                                    if (txRadioFreqNow != null) {
                                        val txDrift = kotlin.math.abs(txRadioFreqNow - lastSetTxFreq)
                                        if (lastSetTxFreq == 0.0 || txDrift >= 50.0) {
                                            ensureVfoB(radio)
                                            radio.setFrequency(txRadioFreqNow)
                                            lastSetTxFreq = txRadioFreqNow.toDouble()
                                            ensureVfoA(radio)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Use latest computed frequencies for state update
                    val txRadioFreq = txBaseFreq?.let { pos.getUplinkFreq(it) }
                    val rxBaseFreq = if (txBaseFreq != null) {
                        TransponderMapper.mapUplinkToDownlink(txBaseFreq, xpdr)
                    } else {
                        xpdr.downlinkLow
                    }
                    val rxRadioFreq = rxBaseFreq?.let { pos.getDownlinkFreq(it) }

                    _state.update {
                        it.copy(
                            txConnected = radio?.isConnected ?: false,
                            rxConnected = radio?.isConnected ?: false,
                            txFrequencyHz = txRadioFreq,
                            rxFrequencyHz = rxRadioFreq,
                            azimuth = Math.toDegrees(pos.azimuth),
                            elevation = Math.toDegrees(pos.elevation),
                            distance = pos.distance
                        )
                    }
                } else {
                    // --- Duplex: two separate radios ---
                    val c = com.rtbishop.look4sat.core.domain.predict.SPEED_OF_LIGHT
                    val v = pos.distanceRate * 1000.0

                    if (tuningRadio.isNotEmpty()) {
                        val radio = if (tuningRadio == "tx") tx else rx
                        if (radio != null && radio.isConnected) {
                            val readResult = radio.readFrequencyAndMode()
                            if (readResult != null) {
                                val (freq, _) = readResult
                                if (kotlin.math.abs(freq - lastReadFreq) <= 20) {
                                    stableCount++
                                } else {
                                    stableCount = 0
                                    lastReadFreq = freq
                                }
                                if (stableCount >= 1) {
                                    if (tuningRadio == "tx" && txBaseFreq != null) {
                                        val newBase = (freq.toDouble() * c / (c + v)).toLong()
                                        if (newBase > 0) {
                                            txBaseFreq = newBase
                                            _state.update { it.copy(txBaseFrequencyHz = newBase) }
                                            Log.i(tag, "TX tuning done → base=$newBase")
                                        }
                                    } else if (tuningRadio == "rx") {
                                        val rxNominal = (freq.toDouble() * c / (c - v)).toLong()
                                        val newTxBase = TransponderMapper.mapDownlinkToUplink(rxNominal, xpdr)
                                        if (newTxBase != null && newTxBase > 0) {
                                            txBaseFreq = newTxBase
                                            _state.update { it.copy(txBaseFrequencyHz = newTxBase) }
                                            Log.i(tag, "RX tuning done → txBase=$newTxBase")
                                        }
                                    }
                                    tuningRadio = ""
                                    stableCount = 0
                                    lastSetTxFreq = 0.0
                                    lastSetRxFreq = 0.0
                                }
                            }
                        }
                    } else {
                        // TX dial feedback
                        if (hasUplink && tx != null && tx.isConnected && lastSetTxFreq > 0.0) {
                            val readResult = tx.readFrequencyAndMode()
                            if (readResult != null) {
                                val (actualTxFreq, _) = readResult
                                if (kotlin.math.abs(actualTxFreq - lastSetTxFreq) >= 20.0) {
                                    tuningRadio = "tx"
                                    lastReadFreq = actualTxFreq
                                    stableCount = 0
                                    Log.i(tag, "TX tuning detected (read=$actualTxFreq, lastSet=$lastSetTxFreq)")
                                }
                            }
                        }

                        // RX dial feedback (only if TX not tuning)
                        if (tuningRadio.isEmpty() && rx != null && rx.isConnected && lastSetRxFreq > 0.0) {
                            val readResult = rx.readFrequencyAndMode()
                            if (readResult != null) {
                                val (actualRxFreq, _) = readResult
                                if (kotlin.math.abs(actualRxFreq - lastSetRxFreq) >= 20.0) {
                                    tuningRadio = "rx"
                                    lastReadFreq = actualRxFreq
                                    stableCount = 0
                                    Log.i(tag, "RX tuning detected (read=$actualRxFreq, lastSet=$lastSetRxFreq)")
                                }
                            }
                        }
                    }

                    // Compute Doppler-corrected frequencies (after tuning may have updated txBaseFreq)
                    val txRadioFreq = txBaseFreq?.let { pos.getUplinkFreq(it) }
                    val rxBaseFreq = if (txBaseFreq != null) {
                        TransponderMapper.mapUplinkToDownlink(txBaseFreq, xpdr)
                    } else {
                        xpdr.downlinkLow
                    }
                    val rxRadioFreq = rxBaseFreq?.let { pos.getDownlinkFreq(it) }

                    // Command radios (only when not tuning)
                    if (tuningRadio.isEmpty()) {
                        if (tx != null && tx.isConnected && txRadioFreq != null) {
                            tx.setFrequency(txRadioFreq)
                            lastSetTxFreq = txRadioFreq.toDouble()
                        }
                        if (rx != null && rx.isConnected && rxRadioFreq != null) {
                            rx.setFrequency(rxRadioFreq)
                            lastSetRxFreq = rxRadioFreq.toDouble()
                        }
                    }

                    _state.update {
                        it.copy(
                            txConnected = tx?.isConnected ?: false,
                            rxConnected = rx?.isConnected ?: false,
                            txFrequencyHz = txRadioFreq,
                            rxFrequencyHz = rxRadioFreq,
                            azimuth = Math.toDegrees(pos.azimuth),
                            elevation = Math.toDegrees(pos.elevation),
                            distance = pos.distance
                        )
                    }
                }

                delay(1000)
            }
        }
    }

    override fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        _state.update { it.copy(isActive = false) }
    }

    override fun setTransponder(transponder: SatRadio) {
        val simplex = settingsRepo.radioControlSettings.value.isSimplex
        appScope.launch {
            val tx = txController
            val rx = rxController
            val rxMode = transponder.downlinkMode
                ?: transponder.uplinkMode?.let {
                    TransponderMapper.mapUplinkModeToDownlinkMode(it, transponder.isInverted)
                }
            if (simplex && tx != null && tx.isConnected) {
                // Simplex: set modes via VFO toggle on single radio
                if (rxMode != null) {
                    ensureVfoA(tx)
                    tx.setMode(rxMode)
                }
                if (transponder.uplinkMode != null) {
                    ensureVfoB(tx)
                    tx.setMode(transponder.uplinkMode!!)
                }
                if (transponder.uplinkMode?.uppercase() == "FM") {
                    _state.value.ctcssTone?.let { tone ->
                        tx.setCtcssTone(tone)
                        tx.setCtcssMode(true)
                    }
                }
                ensureVfoA(tx)
            } else {
                // Duplex: separate radios
                transponder.uplinkMode?.let { tx?.setMode(it) }
                rxMode?.let { rx?.setMode(it) }
                if (transponder.uplinkMode?.uppercase() == "FM") {
                    _state.value.ctcssTone?.let { tone ->
                        tx?.setCtcssTone(tone)
                        tx?.setCtcssMode(true)
                    }
                }
            }
        }
        val txCenter = when {
            transponder.uplinkLow != null && transponder.uplinkHigh != null ->
                (transponder.uplinkLow!! + transponder.uplinkHigh!!) / 2
            transponder.uplinkLow != null -> transponder.uplinkLow!!
            else -> null
        }
        // Show nominal frequencies immediately
        val rxNominal = if (txCenter != null) {
            TransponderMapper.mapUplinkToDownlink(txCenter, transponder)
        } else {
            // Downlink-only transponder (beacon etc.) - use downlink directly
            transponder.downlinkLow
        }
        _state.update {
            it.copy(
                selectedTransponder = transponder,
                txBaseFrequencyHz = txCenter,
                txFrequencyHz = txCenter,
                rxFrequencyHz = rxNominal,
                txMode = transponder.uplinkMode,
                rxMode = transponder.downlinkMode
                    ?: transponder.uplinkMode?.let { m ->
                        TransponderMapper.mapUplinkModeToDownlinkMode(m, transponder.isInverted)
                    }
            )
        }
    }

    override fun setTxBaseFrequency(frequencyHz: Long) {
        _state.update { it.copy(txBaseFrequencyHz = frequencyHz) }
    }

    override fun adjustTxBaseFrequency(deltaHz: Long) {
        val current = _state.value.txBaseFrequencyHz ?: return
        _state.update { it.copy(txBaseFrequencyHz = current + deltaHz) }
    }

    override fun setCtcssTone(toneHz: Double?) {
        _state.update { it.copy(ctcssTone = toneHz) }
        val simplex = settingsRepo.radioControlSettings.value.isSimplex
        appScope.launch {
            val radio = txController
            if (radio != null && radio.isConnected) {
                if (simplex) ensureVfoB(radio)
                if (toneHz != null) {
                    radio.setCtcssTone(toneHz)
                    radio.setCtcssMode(true)
                } else {
                    radio.setCtcssMode(false)
                }
                if (simplex) ensureVfoA(radio)
            }
        }
    }

    override fun setMode(txMode: String, rxMode: String) {
        val simplex = settingsRepo.radioControlSettings.value.isSimplex
        appScope.launch {
            if (simplex) {
                val radio = txController
                if (radio != null && radio.isConnected) {
                    ensureVfoA(radio)
                    radio.setMode(rxMode)
                    ensureVfoB(radio)
                    radio.setMode(txMode)
                    ensureVfoA(radio)
                }
            } else {
                txController?.setMode(txMode)
                rxController?.setMode(rxMode)
            }
        }
        _state.update { it.copy(txMode = txMode, rxMode = rxMode) }
    }

}
