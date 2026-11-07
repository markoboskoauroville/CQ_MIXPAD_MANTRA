package com.mantra.cqmixpad

import kotlin.math.abs
import kotlin.math.log10

/**
 * Feedback detection (Marko, 7.11.2026: "build feedback detection in app to detect which mic is causing it").
 *
 * Feedback is one tone that rings: very narrow (the bins either side are far below it), steady in pitch (a voice
 * has vibrato, feedback has none), and growing or held at a high level. The phone's FFT (Rta) feeds [onSpectrum]
 * every ~85 ms; when one peak keeps those three properties for about half a second, [suspect] names its frequency.
 * The caller then looks at the desk's own meters to find which microphone rose with it, and which output rings.
 * Pure Kotlin, no Android: tested on a desk.
 */
class Feedback(private val rate: Int = 48000, private val n: Int = 8192) {
    data class Event(val hz: Double, val prominenceDb: Double, val levelDb: Double)

    private var bin = -1; private var hits = 0; private var lastLevel = -200.0; private var grew = 0
    var lastEvent: Event? = null; private set

    /** power[k] for k in 0 until n/2. Returns an event when a ringing tone has held for ~0.5 s. */
    fun onSpectrum(power: DoubleArray): Event? {
        val lo = (180.0 * n / rate).toInt(); val hi = (9000.0 * n / rate).toInt().coerceAtMost(power.size - 4)
        var k = lo; var best = 0.0
        for (i in lo..hi) if (power[i] > best) { best = power[i]; k = i }
        if (best <= 0) { reset(); return null }
        val db = 10 * log10(best + 1e-30)
        // narrow: the median of the 60 bins around it (skipping the peak's own +-3) is far below
        val around = ArrayList<Double>()
        for (i in (k - 33)..(k + 33)) if (i in 1 until power.size && abs(i - k) > 3) around += power[i]
        around.sort()
        val med = 10 * log10(around[around.size / 2] + 1e-30)
        val prom = db - med
        val sameTone = bin >= 0 && abs(k - bin) <= 1                     // no vibrato: within one bin (5.9 Hz)
        if (prom >= 30 && sameTone) {
            hits++
            if (db >= lastLevel - 0.5) grew++                              // growing or holding
        } else { hits = if (prom >= 30) 1 else 0; grew = 0 }
        bin = k; lastLevel = db
        if (hits >= 6 && grew >= 4) {                                       // ~0.5 s ringing
            val hz = k.toDouble() * rate / n
            hits = 0; grew = 0
            return Event(hz, prom, db).also { lastEvent = it }
        }
        return null
    }

    fun reset() { bin = -1; hits = 0; grew = 0; lastLevel = -200.0 }

    companion object {
        /**
         * Which input rang: the one whose level rose most between [before] and [now] (dBFS per input), and is loud.
         * Null when nobody rose by 3 dB (then it is not one microphone, or the meters are off).
         */
        fun culprit(before: FloatArray, now: FloatArray, candidates: List<Int>): Int? {
            var best = -1; var rise = 3f
            for (i in candidates) {
                if (i >= before.size || i >= now.size) continue
                val r = now[i] - before[i]
                if (r > rise && now[i] > -40f) { rise = r; best = i }
            }
            return best.takeIf { it >= 0 }
        }

        /** Which output rings: the output (0..5 = Out1..6, 6/7 = Main L/R) that rose most; "MAIN" or "OUTn". */
        fun ringingMix(before: FloatArray, now: FloatArray): String {
            var best = "MAIN"; var rise = 0f
            for (i in 0 until minOf(8, before.size, now.size)) {
                val r = now[i] - before[i]
                if (r > rise) { rise = r; best = if (i >= 6) "MAIN" else "OUT${i + 1}" }
            }
            return best
        }
    }
}
