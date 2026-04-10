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

object Ic705CivProtocol {

    const val RADIO_ADDR: Byte = 0xA4.toByte()  // IC-705 default CI-V address
    const val CTRL_ADDR: Byte = 0xE0.toByte()   // Controller address

    const val PREAMBLE: Byte = 0xFE.toByte()
    const val EOM: Byte = 0xFD.toByte()
    const val ACK: Byte = 0xFB.toByte()
    const val NAK: Byte = 0xFA.toByte()

    // Command bytes
    const val CMD_READ_FREQ: Byte = 0x03
    const val CMD_READ_MODE: Byte = 0x04
    const val CMD_SET_FREQ: Byte = 0x05
    const val CMD_SET_MODE: Byte = 0x06
    const val CMD_SELECT_VFO: Byte = 0x07
    const val CMD_SPLIT: Byte = 0x0F
    const val CMD_PTT: Byte = 0x1C
    const val CMD_SET_FREQ_VFO: Byte = 0x25

    // VFO sub-commands for CMD_SELECT_VFO
    const val VFO_A: Byte = 0x00
    const val VFO_B: Byte = 0x01

    // Split sub-commands
    const val SPLIT_OFF: Byte = 0x00
    const val SPLIT_ON: Byte = 0x01

    // PTT sub-commands (CMD_PTT, sub 0x00, then data)
    const val PTT_SUB: Byte = 0x00
    const val PTT_ON: Byte = 0x01
    const val PTT_OFF: Byte = 0x00

    // VFO sub-commands for CMD_SET_FREQ_VFO (0x25)
    const val VFO_SELECTED: Byte = 0x00
    const val VFO_UNSELECTED: Byte = 0x01

    val MODE_TO_BYTE: Map<String, Byte> = mapOf(
        "LSB" to 0x00,
        "USB" to 0x01,
        "AM" to 0x02,
        "CW" to 0x03,
        "RTTY" to 0x04,
        "FM" to 0x05,
        "CW-R" to 0x07,
        "RTTY-R" to 0x08,
        "DV" to 0x17
    )

    val BYTE_TO_MODE: Map<Byte, String> = MODE_TO_BYTE.entries.associate { it.value to it.key }

    /**
     * Build a CI-V frame: [0xFE][0xFE][TO][FROM][payload...][0xFD]
     */
    fun buildFrame(vararg payload: Byte): ByteArray {
        val frame = ByteArray(payload.size + 4)
        frame[0] = PREAMBLE
        frame[1] = PREAMBLE
        frame[2] = RADIO_ADDR
        frame[3] = CTRL_ADDR
        payload.copyInto(frame, 4)
        return frame + byteArrayOf(EOM)
    }

    /**
     * Encode frequency in Hz to 5-byte BCD, Little-Endian (LSB first), 1 Hz resolution.
     * Example: 145500000 Hz → [0x00, 0x00, 0x50, 0x45, 0x01]
     */
    fun encodeFrequencyBcd(frequencyHz: Long): ByteArray {
        val bcd = ByteArray(5)
        val digits = String.format("%010d", frequencyHz)
        // digits: "0145500000" → pairs from right to left into bytes LSB first
        // digits[8..9] = "00" → bcd[0] = 0x00 (1 Hz, 10 Hz)
        // digits[6..7] = "00" → bcd[1] = 0x00 (100 Hz, 1 kHz)
        // digits[4..5] = "50" → bcd[2] = 0x50 (10 kHz, 100 kHz)
        // digits[2..3] = "45" → bcd[3] = 0x45 (1 MHz, 10 MHz)
        // digits[0..1] = "01" → bcd[4] = 0x01 (100 MHz, 1 GHz)
        for (i in 0 until 5) {
            val pos = 8 - i * 2
            val high = digits[pos] - '0'
            val low = digits[pos + 1] - '0'
            bcd[i] = ((high shl 4) or low).toByte()
        }
        return bcd
    }

    /**
     * Decode 5-byte BCD Little-Endian frequency to Hz.
     */
    fun decodeFrequencyBcd(bcd: ByteArray): Long {
        var freq = 0L
        for (i in 4 downTo 0) {
            val b = bcd[i].toInt() and 0xFF
            val high = b shr 4
            val low = b and 0x0F
            freq = freq * 100 + high * 10 + low
        }
        return freq
    }

    // --- Command builders ---

    fun buildSetFreqCommand(frequencyHz: Long): ByteArray {
        val bcd = encodeFrequencyBcd(frequencyHz)
        return buildFrame(CMD_SET_FREQ, bcd[0], bcd[1], bcd[2], bcd[3], bcd[4])
    }

    fun buildReadFreqCommand(): ByteArray {
        return buildFrame(CMD_READ_FREQ)
    }

    fun buildReadModeCommand(): ByteArray {
        return buildFrame(CMD_READ_MODE)
    }

    fun buildSetModeCommand(mode: String): ByteArray? {
        val modeByte = MODE_TO_BYTE[mode.uppercase()] ?: return null
        return buildFrame(CMD_SET_MODE, modeByte, 0x01) // 0x01 = FIL1 (default filter)
    }

    fun buildSelectVfoCommand(vfoB: Boolean): ByteArray {
        return buildFrame(CMD_SELECT_VFO, if (vfoB) VFO_B else VFO_A)
    }

    fun buildSplitCommand(enabled: Boolean): ByteArray {
        return buildFrame(CMD_SPLIT, if (enabled) SPLIT_ON else SPLIT_OFF)
    }

    fun buildPttOnCommand(): ByteArray {
        return buildFrame(CMD_PTT, PTT_SUB, PTT_ON)
    }

    fun buildPttOffCommand(): ByteArray {
        return buildFrame(CMD_PTT, PTT_SUB, PTT_OFF)
    }

    /**
     * Set frequency on a specific VFO without switching the active VFO.
     * vfo: 0x00 = selected (active), 0x01 = unselected (background)
     */
    fun buildSetFreqOnVfoCommand(unselected: Boolean, frequencyHz: Long): ByteArray {
        val vfo = if (unselected) VFO_UNSELECTED else VFO_SELECTED
        val bcd = encodeFrequencyBcd(frequencyHz)
        return buildFrame(CMD_SET_FREQ_VFO, vfo, bcd[0], bcd[1], bcd[2], bcd[3], bcd[4])
    }

    // --- Response parsing ---

    /**
     * Check if the response frame is an ACK (0xFB).
     */
    fun isAck(frame: ByteArray): Boolean {
        // Minimum ACK frame: FE FE <to> <from> FB FD = 6 bytes
        return frame.size >= 6 && frame[4] == ACK
    }

    /**
     * Check if the response frame is a NAK (0xFA).
     */
    fun isNak(frame: ByteArray): Boolean {
        return frame.size >= 6 && frame[4] == NAK
    }

    /**
     * Extract frequency from a read-frequency response.
     * Frame: FE FE <to> <from> 03 <5 bytes BCD> FD
     */
    fun parseFreqResponse(frame: ByteArray): Long? {
        // Expected: FE FE E0 A4 03 d0 d1 d2 d3 d4 FD = 11 bytes
        if (frame.size < 11 || frame[4] != CMD_READ_FREQ) return null
        val bcd = frame.copyOfRange(5, 10)
        return decodeFrequencyBcd(bcd)
    }

    /**
     * Extract mode from a read-mode response.
     * Frame: FE FE <to> <from> 04 <mode> <filter> FD
     */
    fun parseModeResponse(frame: ByteArray): String? {
        // Expected: FE FE E0 A4 04 mode filter FD = 8 bytes
        if (frame.size < 8 || frame[4] != CMD_READ_MODE) return null
        return BYTE_TO_MODE[frame[5]]
    }
}
