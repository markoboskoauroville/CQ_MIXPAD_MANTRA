package com.mantra.cqmixpad

/**
 * The Allen & Heath CQ MIDI protocol (firmware V1.2, issue 4; docs/CQ_MIDI_Protocol_V1_2_0_iss4.pdf), over TCP port
 * 51325, MIDI channel 1. Pure Kotlin, no Android: every number here is checked against the PDF's own examples in
 * Test (CqTest.kt).
 *
 * Measured on Marko's CQ-12T, 7.11.2026: it answers for Ip1..Ip10, ST1, USB, BT, FX1, FX2, Out1..Out6, DCA1..4;
 * not for Ip11..16, ST2, FX3, FX4.
 */
object Cq {
    const val PORT = 51325

    /** A source channel: its code is the LSB of its level-to-Main parameter (Ip1 = 0, ST1 = 0x18, FX1 = 0x3C ...). */
    data class Src(val id: String, val code: Int, val muteLsb: Int)

    val SOURCES: List<Src> = (0 until 16).map { Src("Ip${it + 1}", it, it) } + listOf(
        Src("ST1", 0x18, 0x18), Src("ST2", 0x1A, 0x1A), Src("USB", 0x1C, 0x1C), Src("BT", 0x1E, 0x1E),
        Src("FX1", 0x3C, 0x51), Src("FX2", 0x3D, 0x52), Src("FX3", 0x3E, 0x53), Src("FX4", 0x3F, 0x54),
    )

    /** The mixes a source can be sent to: MAIN (LR), OUT1..OUT6 (monitors; linked pairs use the odd one), FX1..FX4. */
    val MIXES = listOf("MAIN", "OUT1", "OUT2", "OUT3", "OUT4", "OUT5", "OUT6", "FX1", "FX2", "FX3", "FX4")

    fun src(id: String): Src? = SOURCES.firstOrNull { it.id.equals(id, ignoreCase = true) }

    private fun p(msb: Int, lsb: Int) = (msb shl 7) or lsb

    /** Level of a source into a mix (send level; MAIN = the channel fader). Null when the CQ has no such send. */
    fun sendParam(s: Src, mix: String): Int? = when {
        mix == "MAIN" -> p(0x40, s.code)
        mix.startsWith("OUT") -> p(0x40, 0x44) + 12 * s.code + (mix.removePrefix("OUT").toInt() - 1)
        mix.startsWith("FX") -> {
            val f = mix.removePrefix("FX").toInt()
            if (s.code in 0x3C..0x3F && s.code - 0x3C == f - 1) null else p(0x4C, 0x14) + 4 * s.code + (f - 1)
        }
        else -> null
    }

    /** The master level of a mix (Main LR, Out1..6, FX unit input). */
    fun masterParam(mix: String): Int? = when {
        mix == "MAIN" -> p(0x4F, 0x00)
        mix.startsWith("OUT") -> p(0x4F, mix.removePrefix("OUT").toInt())
        mix.startsWith("FX") -> p(0x4F, 0x0C + mix.removePrefix("FX").toInt())
        else -> null
    }

    fun dcaLevelParam(n: Int) = p(0x4F, 0x1F + n)          // DCA1 = 4F 20
    fun muteParam(s: Src) = p(0x00, s.muteLsb)
    fun masterMuteParam(mix: String): Int? = when {
        mix == "MAIN" -> p(0x00, 0x44)
        mix.startsWith("OUT") -> p(0x00, 0x44 + mix.removePrefix("OUT").toInt())
        else -> null
    }
    fun dcaMuteParam(n: Int) = p(0x02, n - 1)               // DCA1 = 02 00
    fun muteGroupParam(n: Int) = p(0x04, n - 1)             // MGRP1 = 04 00

    /** Pan to Main LR (mono) / balance (stereo). */
    fun panMainParam(s: Src) = p(0x50, s.code)
    /** Pan into a linked output pair: pair 1 = Out1/2, 2 = Out3/4, 3 = Out5/6. */
    fun panOutParam(s: Src, pair: Int) = p(0x50, 0x44) + 12 * s.code + 2 * (pair - 1)

    // ---- messages ----

    private fun nrpn(param: Int, vararg tail: Int): ByteArray {
        val b = ArrayList<Int>()
        b += listOf(0xB0, 0x63, (param shr 7) and 0x7F, 0xB0, 0x62, param and 0x7F)
        b += tail.toList()
        return ByteArray(b.size) { b[it].toByte() }
    }

    fun setValue(param: Int, value14: Int): ByteArray {
        val v = value14.coerceIn(0, 16383)
        return nrpn(param, 0xB0, 0x06, v shr 7, 0xB0, 0x26, v and 0x7F)
    }
    fun setMute(param: Int, on: Boolean) = nrpn(param, 0xB0, 0x06, 0x00, 0xB0, 0x26, if (on) 1 else 0)
    fun get(param: Int) = nrpn(param, 0xB0, 0x60, 0x7F)
    fun increment(param: Int) = nrpn(param, 0xB0, 0x60, 0x00)
    fun decrement(param: Int) = nrpn(param, 0xB0, 0x61, 0x00)

    fun scene(n: Int): ByteArray = byteArrayOf(0xB0.toByte(), 0x00, 0x00, 0xC0.toByte(), ((n - 1).coerceIn(0, 127)).toByte())
    fun softKeyPress(n: Int) = byteArrayOf(0x90.toByte(), (0x30 + n - 1).toByte(), 0x7F)
    fun softKeyRelease(n: Int) = byteArrayOf(0x80.toByte(), (0x30 + n - 1).toByte(), 0x00)

    // ---- levels: the audio taper table of the PDF (dB, VC, VF) ----

    private val TAPER: List<Pair<Double, Int>> = listOf(
        -89.0 to 0x0140, -85.0 to 0x0200, -80.0 to 0x0240, -75.0 to 0x0340, -70.0 to 0x0400, -65.0 to 0x0500,
        -60.0 to 0x0600, -55.0 to 0x0700, -50.0 to 0x0800, -45.0 to 0x0C00, -40.0 to 0x0F40, -38.0 to 0x1240,
        -36.0 to 0x1540, -35.0 to 0x1700, -34.0 to 0x1900, -33.0 to 0x1A40, -32.0 to 0x1C00, -31.0 to 0x1D40,
        -30.0 to 0x1F00, -29.0 to 0x2040, -28.0 to 0x2200, -27.0 to 0x2340, -26.0 to 0x2500, -25.0 to 0x2640,
        -24.0 to 0x2840, -23.0 to 0x2A00, -22.0 to 0x2B40, -21.0 to 0x2D00, -20.0 to 0x2E40, -19.0 to 0x3000,
        -18.0 to 0x3140, -17.0 to 0x3300, -16.0 to 0x3440, -15.0 to 0x3600, -14.0 to 0x3800, -13.0 to 0x3940,
        -12.0 to 0x3B00, -11.0 to 0x3C40, -10.0 to 0x3E00, -9.0 to 0x4140, -8.0 to 0x4440, -7.0 to 0x4800,
        -6.0 to 0x4B00, -5.0 to 0x4E40, -4.0 to 0x5240, -3.0 to 0x5640, -2.0 to 0x5A00, -1.0 to 0x5E00,
        0.0 to 0x6200, 1.0 to 0x6540, 2.0 to 0x6900, 3.0 to 0x6C40, 4.0 to 0x7000, 5.0 to 0x7340, 6.0 to 0x7540,
        7.0 to 0x7800, 8.0 to 0x7A40, 9.0 to 0x7D00, 10.0 to 0x7F40,
    ).map { (db, hex) -> db to (((hex shr 8) shl 7) or (hex and 0xFF)) }

    const val MIN_DB = -90.0   // shown as -inf
    const val MAX_DB = 10.0

    fun dbToValue(db: Double): Int {
        if (db <= MIN_DB) return 0
        if (db >= MAX_DB) return TAPER.last().second
        if (db <= TAPER.first().first) return TAPER.first().second
        for (i in 0 until TAPER.size - 1) {
            val (d0, v0) = TAPER[i]; val (d1, v1) = TAPER[i + 1]
            if (db in d0..d1) return (v0 + (v1 - v0) * (db - d0) / (d1 - d0)).toInt()
        }
        return TAPER.last().second
    }

    fun valueToDb(v: Int): Double {
        if (v <= 0) return MIN_DB
        if (v <= TAPER.first().second) return TAPER.first().first
        for (i in 0 until TAPER.size - 1) {
            val (d0, v0) = TAPER[i]; val (d1, v1) = TAPER[i + 1]
            if (v in v0..v1) return d0 + (d1 - d0) * (v - v0).toDouble() / (v1 - v0)
        }
        return MAX_DB
    }

    /** Pan: 0 = L100, 8192 = centre, 16383 = R100; percent -100..100. */
    fun panToValue(pct: Double): Int = if (pct >= 0) (8192 + pct / 100.0 * 8191).toInt() else (8192 + pct / 100.0 * 8192).toInt()
    fun valueToPan(v: Int): Double = if (v >= 8192) (v - 8192) / 8191.0 * 100 else (v - 8192) / 8192.0 * 100

    fun dbText(db: Double): String = if (db <= MIN_DB + 0.01) "-inf" else String.format(java.util.Locale.US, "%+.1f", db)

    // ---- reading the stream the CQ sends ----

    /** Turns the CQ's byte stream into (parameter, 14-bit value) events. Handles running status. */
    class Parser(val onValue: (param: Int, value: Int) -> Unit) {
        private var status = 0
        private var data1 = -1
        private var msb = 0; private var lsb = 0; private var vc = 0

        fun feed(bytes: ByteArray, n: Int) {
            for (i in 0 until n) byte(bytes[i].toInt() and 0xFF)
        }

        fun byte(b: Int) {
            if (b >= 0x80) { if (b < 0xF8) { status = b; data1 = -1 }; return }
            if (status and 0xF0 != 0xB0) return            // only control changes carry NRPN
            if (data1 < 0) { data1 = b; return }
            val cc = data1; data1 = -1
            when (cc) {
                0x63 -> msb = b
                0x62 -> lsb = b
                0x06 -> vc = b
                0x26 -> onValue((msb shl 7) or lsb, (vc shl 7) or b)
            }
        }
    }
}
