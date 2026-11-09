package com.mantra.cqmixpad.reference

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * MIX TO REFERENCE (task 54): from the live Main's octaves and the reference's (same measure), the four Main LR PEQ
 * gains that bring the one toward the other, in small steps and inside limits. Pure Kotlin, unit-tested.
 *
 * Model: a PEQ bell adds g * exp(-x^2 / (2 s^2)) dB at x octaves from its centre, s = width / 2 (width in octaves; a
 * shelf, width 0, adds g on its side, half at its frequency). Because a profile is a share of the whole, a level shift
 * c is free: least squares on (g1..g4, c) with a small ridge on g. The desk's RTA closes the loop, so a model this simple
 * is enough: each MATCH moves half way, at most [STEP] dB per band per press.
 */
object Matcher {
    data class Band(val hz: Double, val widthOct: Double, val gainDb: Double)
    data class Plan(val gains: DoubleArray, val fit: DoubleArray, val residual: DoubleArray, val notes: List<String>, val held: List<String>)

    const val STEP = 2.0          // dB per band per press
    const val LIMIT = 6.0         // dB from the gain before the first MATCH of the session
    val WEIGHT = doubleArrayOf(0.4, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.8, 0.5)
    /** Bands whose boost can ring a microphone (Feedback.kt looks for rings 180 Hz .. 9 kHz). */
    fun feedbackProne(hz: Double) = hz in 200.0..5000.0

    fun shape(b: Band, f: Double, band: Int): Double {
        val x = ln(f / b.hz) / ln(2.0)
        if (b.widthOct <= 0.01) {     // shelf: LF (band 0) below, HF (band 3) above
            val t = 1 / (1 + exp(-4 * (if (band == 0) -x else x)))
            return t
        }
        val s = b.widthOct / 2
        return exp(-x * x / (2 * s * s))
    }

    /** [live10], [ref10]: octave shares (dB) in one measure; [bands]: the Main PEQ as it is; [before]: its gains before
     *  this matching session; [fbGuard]: the feedback guard is on. */
    fun plan(live10: DoubleArray, ref10: DoubleArray, bands: List<Band>, before: DoubleArray, fbGuard: Boolean): Plan {
        val d = DoubleArray(10) { ref10[it] - live10[it] }
        val m = bands.size + 1
        val a = Array(10) { i -> DoubleArray(m) { j -> if (j < bands.size) shape(bands[j], Bands.OCT_HZ[i], j) else 1.0 } }
        val ata = Array(m) { DoubleArray(m) }; val atd = DoubleArray(m)
        for (i in 0 until 10) for (j in 0 until m) { atd[j] += WEIGHT[i] * a[i][j] * d[i]; for (k in 0 until m) ata[j][k] += WEIGHT[i] * a[i][j] * a[i][k] }
        for (j in bands.indices) ata[j][j] += 0.3
        val x = solve(ata, atd)
        val fitted = DoubleArray(10) { i -> (0 until bands.size).sumOf { j -> a[i][j] * x[j] } }
        val residual = DoubleArray(10) { d[it] - fitted[it] - x[bands.size] }
        val held = ArrayList<String>()
        val gains = DoubleArray(bands.size) { j ->
            val cur = bands[j].gainDb
            var step = (0.5 * x[j]).coerceIn(-STEP, STEP)
            var g = (cur + step).coerceIn(before[j] - LIMIT, before[j] + LIMIT).coerceIn(-15.0, 15.0)
            if (!fbGuard && feedbackProne(bands[j].hz) && g > maxOf(cur, before[j])) {
                held += String.format(java.util.Locale.US, "%s %.0f Hz: no boost while the feedback guard is off", NAMES[j], bands[j].hz)
                g = maxOf(cur, before[j]).coerceAtMost(g)
            }
            if (g != cur + step) step = g - cur
            (g * 2).roundToInt() / 2.0
        }
        val notes = ArrayList<String>()
        for (i in 0 until 10) {
            val r = residual[i]
            if (abs(r) < 3) continue
            val n = Bands.OCT_NAMES[i]
            notes += when {
                i <= 1 && r < 0 -> String.format(java.util.Locale.US, "%s Hz: %.0f dB more low end than the reference: HPF up on the vocal and guitar channels (100-120 Hz)", n, -r)
                i <= 1 -> String.format(java.util.Locale.US, "%s Hz: %.0f dB less low end than the reference: bass and mridangam low head up, or their HPF lower", n, r)
                i >= 8 && r < 0 -> String.format(java.util.Locale.US, "%s: %.0f dB brighter than the reference: HF shelf down on the cymbals/overheads and bright vocals", n, -r)
                i >= 8 -> String.format(java.util.Locale.US, "%s: %.0f dB duller than the reference: air (HF shelf) on the lead vocals", n, r)
                r < 0 -> String.format(java.util.Locale.US, "%s: %.0f dB too much against the reference: a narrow cut there on the loudest channel", n, -r)
                else -> String.format(java.util.Locale.US, "%s: %.0f dB missing against the reference: raise the channel that lives there", n, r)
            }
        }
        return Plan(gains, x, residual, notes, held)
    }

    val NAMES = listOf("LF", "LM", "HM", "HF")

    private fun solve(a0: Array<DoubleArray>, b0: DoubleArray): DoubleArray {
        val n = b0.size; val a = Array(n) { a0[it].copyOf() }; val b = b0.copyOf()
        for (c in 0 until n) {
            var p = c; for (r in c + 1 until n) if (abs(a[r][c]) > abs(a[p][c])) p = r
            val t = a[c]; a[c] = a[p]; a[p] = t; val tb = b[c]; b[c] = b[p]; b[p] = tb
            if (abs(a[c][c]) < 1e-12) continue
            for (r in 0 until n) if (r != c) { val f = a[r][c] / a[c][c]; for (k in c until n) a[r][k] -= f * a[c][k]; b[r] -= f * b[c] }
        }
        return DoubleArray(n) { if (abs(a[it][it]) < 1e-12) 0.0 else b[it] / a[it][it] }
    }
}
