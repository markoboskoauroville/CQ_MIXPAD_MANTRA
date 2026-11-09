package com.mantra.cqmixpad.reference

import kotlin.math.log10
import kotlin.math.pow

/**
 * The bands every profile is measured in (task 54, 9.11.2026): 31 third-octave bands 20 Hz .. 20 kHz (centres
 * 1000 * 2^((i-17)/3), edges x 2^(+-1/6)), and the ten octaves 31.5 Hz .. 16 kHz summed from them (octave o = thirds
 * 3o+1 .. 3o+3, as Rta.feedDesk sums the desk's RTA). Every value is a band's SHARE OF THE WHOLE in dB: the measure of
 * SANTOSHI_CONCERT_MIX compare_ref.py, so a level change never moves a profile.
 *
 * Two ways of measuring meet here: the FILE measure (an FFT of real audio: a loaded MP3, the CQ's USB stream, a take) and
 * the DESK measure (the CQ-12T's own RTA, UDP type 23). The desk's analyser reads its bands differently (smoother, more at
 * the very low end), so the same music gives two curves. [CAL31] = desk minus file for Krishna Das, measured both ways
 * (the desk: tools/kd_desk.json, 8.11.2026; the file: the two references in ~/Music/SantoshiKoncert/reference through
 * this code's own Python twin), clamped to +-8 dB. Profiles are stored in the file measure; the desk RTA is brought into
 * it with [deskToFile31].
 */
object Bands {
    val THIRD_HZ = DoubleArray(31) { 1000.0 * 2.0.pow((it - 17) / 3.0) }
    val THIRD_NAMES = listOf("20", "25", "31", "40", "50", "63", "80", "100", "125", "160", "200", "250", "315", "400", "500",
        "630", "800", "1k", "1.25k", "1.6k", "2k", "2.5k", "3.15k", "4k", "5k", "6.3k", "8k", "10k", "12.5k", "16k", "20k")
    val OCT_HZ = doubleArrayOf(31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    val OCT_NAMES = listOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

    /** Krishna Das, the mean of the two references, FILE measure (whole files, 8192-point Hann FFT, mono = mean of L R). */
    val KD_FILE31 = doubleArrayOf(-49.53, -48.93, -46.41, -36.76, -25.36, -13.79, -17.33, -9.85, -12.00, -16.47, -14.71, -10.53,
        -11.55, -12.01, -12.86, -11.75, -10.47, -14.15, -14.65, -15.11, -17.21, -18.63, -18.97, -20.85, -22.25, -25.06, -24.83,
        -26.69, -32.09, -46.94, -111.93)
    /** Krishna Das through the desk's own RTA (tools/kd_desk.json bands31 as share of the whole). */
    val KD_DESK31 = doubleArrayOf(-42.33, -39.81, -35.72, -30.59, -24.52, -18.51, -16.24, -11.60, -9.89, -13.39, -13.39, -11.30,
        -12.36, -12.42, -11.57, -10.02, -9.19, -11.69, -13.36, -15.18, -16.80, -18.27, -19.17, -20.42, -22.55, -25.66, -25.80,
        -27.10, -31.53, -39.29, -49.46)
    /** Krishna Das octaves through the desk (Rta.KD, the target MATCH KD used until v42). */
    val KD_DESK10 = doubleArrayOf(-29.0, -13.8, -6.6, -7.5, -6.4, -6.3, -11.8, -15.7, -21.4, -30.8)
    val CAL31 = DoubleArray(31) { (KD_DESK31[it] - KD_FILE31[it]).coerceIn(-8.0, 8.0) }

    fun db(p: Double) = 10 * log10(p + 1e-30)
    fun pow10(db: Double) = 10.0.pow(db / 10)

    /** Powers to share of the whole (dB). */
    fun share(p: DoubleArray): DoubleArray { val t = p.sum().coerceAtLeast(1e-30); return DoubleArray(p.size) { 10 * log10(p[it] / t + 1e-12) } }
    /** 31 third-octave powers to the ten octave powers. */
    fun octPower(p31: DoubleArray) = DoubleArray(10) { o -> var s = 0.0; for (k in 3 * o + 1..minOf(3 * o + 3, 30)) s += p31[k]; s }
    /** 31 third-octave shares (dB) to ten octave shares (dB). */
    fun octShare(s31: DoubleArray) = share(octPower(DoubleArray(31) { pow10(s31[it]) }))
    /** Re-normalise a 31-band curve (dB, any level) to share of the whole. */
    fun renorm(s31: DoubleArray) = share(DoubleArray(s31.size) { pow10(s31[it]) })
    /** The desk's RTA (31 bands, dB, floor -110) in the file measure. */
    fun deskToFile31(deskDb: FloatArray) = renorm(DoubleArray(31) { val d = deskDb[it].toDouble(); if (d <= -105) -140.0 else d - CAL31[it] })
    /** A file-measure profile in the desk measure (for the desk's RTA strip and its numbers). */
    fun fileToDesk10(file31: DoubleArray) = octShare(DoubleArray(31) { file31[it] + CAL31[it] })
    /** Ten octaves only (the phone microphone): spread evenly into thirds. */
    fun octToThirds(s10: DoubleArray) = renorm(DoubleArray(31) { k -> val o = ((k - 1) / 3).coerceIn(0, 9); s10[o] - 4.77 })
}
