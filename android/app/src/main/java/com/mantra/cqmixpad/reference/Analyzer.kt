package com.mantra.cqmixpad.reference

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A long-term profile: what LOAD REFERENCE stores and what a take or the live Main is compared with. */
data class Profile(
    val id: String,
    val name: String,
    /** 31 third-octave shares of the whole (dB), FILE measure (Bands). */
    val thirds: DoubleArray,
    /** Integrated loudness (BS.1770 K-weighting, -70 / -10 gates), LUFS. */
    val lufs: Double?,
    /** Sample peak, dBFS. */
    val peakDb: Double?,
    /** Crest factor: peak minus RMS, dB. */
    val crestDb: Double?,
    /** Loudness range (3 s short-term, 10th to 95th percentile), LU. */
    val lra: Double?,
    /** Side over mid energy, dB (null for mono); about -10 = narrow, -3 = wide. */
    val widthDb: Double?,
    val seconds: Double,
    val builtin: Boolean = false,
    val source: String = "",
) {
    val octaves: DoubleArray get() = Bands.octShare(thirds)
    /** The octaves in the desk measure: measured for Krishna Das, converted for the rest. */
    val deskOctaves: DoubleArray get() = if (id == RefStoreIds.KD) Bands.KD_DESK10 else Bands.fileToDesk10(thirds)
}

object RefStoreIds { const val KD = "kd" }

/**
 * Streams PCM (any rate, any channel count, float -1..1, interleaved) into a [Profile]: the spectrum of the mono mix
 * (mean of the channels, 8192-point Hann FFT blocks), BS.1770 loudness, peak, crest, loudness range, stereo width.
 * [pair] picks the two channels that make the programme (Main L/R = 14/15 of the CQ's 16-channel USB stream); null =
 * all channels (a file). [rollSec] > 0 keeps a rolling window for the live curve. Pure Kotlin: unit-tested on the JVM.
 */
class Analyzer(val rate: Int, val channels: Int, private val pair: IntArray? = null, rollSec: Double = 0.0) {
    private val n = 8192
    private val win = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
    private val bandOf = IntArray(n / 2) { k ->
        val f = k.toDouble() * rate / n
        if (k == 0) -1 else Bands.THIRD_HZ.indexOfFirst { f >= it * 2.0.pow(-1 / 6.0) && f < it * 2.0.pow(1 / 6.0) }
    }
    private val buf = DoubleArray(n); private var fill = 0
    private val sum31 = DoubleArray(31); var blocks = 0; private set
    private val rollCap = if (rollSec > 0) maxOf(1, (rollSec * rate / n).toInt()) else 0
    private val roll = ArrayDeque<DoubleArray>()

    private val chs: IntArray = pair ?: IntArray(channels) { it }
    // K-weighting, two biquads per programme channel
    private val kw = Array(chs.size) { doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0) }
    private val c1: DoubleArray = shelf(rate); private val c2: DoubleArray = highPass(rate)
    private val sub = maxOf(1, rate / 10)       // 100 ms sub-blocks
    private var subFill = 0; private var subAcc = 0.0
    private val subs = ArrayList<Double>()      // mean square per 100 ms, K-weighted, summed over channels
    private var peak = 0.0; private var sq = 0.0; private var count = 0L
    private var mid = 0.0; private var side = 0.0
    val frames: Long get() = count

    private fun biquad(c: DoubleArray, s: DoubleArray, o: Int, x: Double): Double {
        val y = c[0] * x + c[1] * s[o] + c[2] * s[o + 1] - c[3] * s[o + 2] - c[4] * s[o + 3]
        s[o + 1] = s[o]; s[o] = x; s[o + 3] = s[o + 2]; s[o + 2] = y; return y
    }

    /** [x] interleaved, [frameCount] frames of [channels] samples. */
    fun feed(x: FloatArray, frameCount: Int) {
        for (f in 0 until frameCount) {
            val base = f * channels
            var m = 0.0; var ms = 0.0
            for ((ci, c) in chs.withIndex()) {
                val v = x[base + c].toDouble()
                m += v
                val a = abs(v); if (a > peak) peak = a
                sq += v * v
                val y = biquad(c2, kw[ci], 4, biquad(c1, kw[ci], 0, v))
                ms += y * y
            }
            if (chs.size >= 2) { val l = x[base + chs[0]].toDouble(); val r = x[base + chs[1]].toDouble(); mid += (l + r) * (l + r) / 4; side += (l - r) * (l - r) / 4 }
            count++
            subAcc += ms; subFill++
            if (subFill == sub) { synchronized(subs) { subs += subAcc / sub }; subAcc = 0.0; subFill = 0 }
            buf[fill++] = m / chs.size
            if (fill == n) { block(); fill = 0 }
        }
    }

    private fun block() {
        val re = DoubleArray(n) { buf[it] * win[it] }; val im = DoubleArray(n)
        fft(re, im)
        val p = DoubleArray(31)
        for (k in 1 until n / 2) { val b = bandOf[k]; if (b >= 0) p[b] += re[k] * re[k] + im[k] * im[k] }
        for (i in 0 until 31) sum31[i] += p[i]
        blocks++
        if (rollCap > 0) synchronized(roll) { roll.addLast(p); while (roll.size > rollCap) roll.removeFirst() }
    }

    /** The rolling window's thirds (share, dB), null until a block has come. */
    fun liveThirds(): DoubleArray? = synchronized(roll) { if (roll.isEmpty()) null else Bands.share(DoubleArray(31) { i -> roll.sumOf { it[i] } }) }
    fun liveSeconds(): Double = synchronized(roll) { roll.size } * n.toDouble() / rate
    fun resetRoll() = synchronized(roll) { roll.clear() }
    /** The level of the last 400 ms (K-weighted, LUFS-ish), for "is there music". */
    fun momentary(): Double = synchronized(subs) { if (subs.size < 4) -120.0 else -0.691 + 10 * log10(subs.takeLast(4).average() + 1e-30) }

    fun loudness(): Double? {
        if (subs.size < 4) return null
        val blk = (0..subs.size - 4).map { i -> (subs[i] + subs[i + 1] + subs[i + 2] + subs[i + 3]) / 4 }
        val l = blk.map { -0.691 + 10 * log10(it + 1e-30) }
        val abs = blk.filterIndexed { i, _ -> l[i] > -70 }; if (abs.isEmpty()) return null
        val rel = -0.691 + 10 * log10(abs.average()) - 10
        val g = blk.filterIndexed { i, _ -> l[i] > -70 && l[i] > rel }; if (g.isEmpty()) return null
        return -0.691 + 10 * log10(g.average())
    }

    fun range(): Double? {
        if (subs.size < 30) return null
        val st = (0..subs.size - 30 step 10).map { i -> -0.691 + 10 * log10(subs.subList(i, i + 30).average() + 1e-30) }.filter { it > -70 }
        if (st.size < 2) return null
        val rel = -0.691 + 10 * log10(st.map { 10.0.pow((it + 0.691) / 10) }.average()) - 20
        val g = st.filter { it > rel }.sorted(); if (g.size < 2) return null
        fun pct(p: Double) = g[((g.size - 1) * p).toInt()]
        return pct(0.95) - pct(0.10)
    }

    fun result(id: String, name: String, source: String): Profile {
        if (fill > n / 4) { for (i in fill until n) buf[i] = 0.0; block(); fill = 0 }
        val pk = if (peak > 0) 20 * log10(peak) else null
        val rms = if (count > 0) 10 * log10(sq / (count * chs.size) + 1e-30) else null
        return Profile(id, name, Bands.share(sum31), loudness(), pk, if (pk != null && rms != null) pk - rms else null, range(),
            if (chs.size >= 2 && mid > 0) 10 * log10(side / mid + 1e-12) else null, count.toDouble() / rate, false, source)
    }

    companion object {
        // libebur128's K-weighting for any sample rate (ITU-R BS.1770-4; at 48 kHz exactly the standard's table):
        // b0 b1 b2 a1 a2, a0 = 1. Checked: a 997 Hz full-scale sine in one channel reads -3.01 LUFS at 48 and 44.1 kHz.
        fun shelf(rate: Int): DoubleArray {
            val f0 = 1681.974450955533; val g = 3.999843853973347; val q = 0.7071752369554196
            val k = Math.tan(PI * f0 / rate); val vh = 10.0.pow(g / 20); val vb = vh.pow(0.4996667741545416); val a0 = 1 + k / q + k * k
            return doubleArrayOf((vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
        }
        fun highPass(rate: Int): DoubleArray {
            val f0 = 38.13547087602444; val q = 0.5003270373238773
            val k = Math.tan(PI * f0 / rate); val a0 = 1 + k / q + k * k
            return doubleArrayOf(1.0, -2.0, 1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
        }
        /** In-place radix-2 FFT (as Rta's). */
        fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size; var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
            }
            var len = 2
            while (len <= n) {
                val ang = -2 * PI / len
                val wr = cos(ang); val wi = sin(ang)
                for (i in 0 until n step len) {
                    var cr = 1.0; var ci = 0.0
                    for (k in 0 until len / 2) {
                        val a = i + k; val b = a + len / 2
                        val vr = re[b] * cr - im[b] * ci; val vi = re[b] * ci + im[b] * cr
                        re[b] = re[a] - vr; im[b] = im[a] - vi; re[a] += vr; im[a] += vi
                        val t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t
                    }
                }
                len = len shl 1
            }
        }
    }
}
