package com.mantra.cqmixpad.reference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class ReferenceTest {
    private fun sine(hz: Double, amp: Double, sec: Double, rate: Int = 48000, ch: Int = 1): FloatArray {
        val n = (sec * rate).toInt(); return FloatArray(n * ch) { i -> (amp * sin(2 * PI * hz * (i / ch) / rate)).toFloat() }
    }

    @Test fun sineLandsInItsThird() {
        val a = Analyzer(48000, 1); val x = sine(1000.0, 0.5, 3.0); a.feed(x, x.size)
        val p = a.result("t", "t", "test")
        val best = p.thirds.indices.maxByOrNull { p.thirds[it] }!!
        assertEquals(17, best)
        assertTrue(p.thirds[17] > -0.5)
        assertEquals(5, p.octaves.indices.maxByOrNull { p.octaves[it] })
    }

    @Test fun loudnessOfAFullScaleSine() {
        // BS.1770: a 997 Hz sine at 0 dBFS in one channel reads -3.01 LUFS
        val a = Analyzer(48000, 1); val x = sine(997.0, 1.0, 5.0); a.feed(x, x.size)
        val l = a.result("t", "t", "t").lufs!!
        assertEquals(-3.01, l, 0.15)
    }

    @Test fun widthAndCrest() {
        val rate = 48000; val n = rate * 2
        val x = FloatArray(n * 2) { i -> val t = (i / 2).toDouble() / rate; (0.5 * sin(2 * PI * 440 * t)).toFloat() }   // L = R
        val a = Analyzer(rate, 2); a.feed(x, n)
        val p = a.result("t", "t", "t")
        assertTrue(p.widthDb!! < -60)               // mono in stereo: no side
        assertEquals(3.01, p.crestDb!!, 0.1)        // a sine's crest
    }

    @Test fun mainPairOfSixteen() {
        val rate = 48000; val n = rate * 2
        val x = FloatArray(n * 16) { i -> val c = i % 16; val t = (i / 16).toDouble() / rate
            if (c == 14 || c == 15) (0.3 * sin(2 * PI * 250 * t)).toFloat() else (0.3 * sin(2 * PI * 4000 * t)).toFloat() }
        val a = Analyzer(rate, 16, intArrayOf(14, 15)); a.feed(x, n)
        val p = a.result("t", "t", "t")
        assertEquals(3, p.octaves.indices.maxByOrNull { p.octaves[it] })   // 250 Hz, not the 4 kHz of the other channels
    }

    @Test fun calibrationRoundTripsKd() {
        val d = Bands.fileToDesk10(Bands.KD_FILE31)
        for (i in 1 until 9) assertEquals("octave $i", Bands.KD_DESK10[i], d[i], 1.6)
    }

    private val flat = listOf(Matcher.Band(63.0, 1.5, 0.0), Matcher.Band(250.0, 1.5, 0.0), Matcher.Band(2000.0, 1.5, 0.0), Matcher.Band(10000.0, 1.5, 0.0))

    @Test fun matchMovesTowardTheReferenceInSmallSteps() {
        val ref = Bands.KD_DESK10
        val live = DoubleArray(10) { ref[it] + if (it >= 8) 6.0 else 0.0 }     // 6 dB too bright
        val p = Matcher.plan(live, ref, flat, DoubleArray(4), fbGuard = true)
        assertTrue("HF cut", p.gains[3] < 0)
        assertTrue("small step", p.gains.all { abs(it) <= Matcher.STEP })
    }

    @Test fun neverPastTheLimit() {
        val ref = Bands.KD_DESK10
        val live = DoubleArray(10) { ref[it] - if (it >= 8) 20.0 else 0.0 }
        val bands = flat.mapIndexed { j, b -> if (j == 3) b.copy(gainDb = 5.5) else b }
        val p = Matcher.plan(live, ref, bands, DoubleArray(4), fbGuard = true)
        assertTrue(p.gains[3] <= Matcher.LIMIT)
    }

    @Test fun noMidBoostWithoutTheGuard() {
        val ref = Bands.KD_DESK10
        val live = DoubleArray(10) { ref[it] - if (it in 5..6) 6.0 else 0.0 }   // 1-2 kHz missing
        val p = Matcher.plan(live, ref, flat, DoubleArray(4), fbGuard = false)
        assertTrue(p.gains[2] <= 0.0)
        assertTrue(p.held.isNotEmpty())
        val q = Matcher.plan(live, ref, flat, DoubleArray(4), fbGuard = true)
        assertTrue(q.gains[2] > 0.0)
    }

    @Test fun matchedIsQuiet() {
        val p = Matcher.plan(Bands.KD_DESK10, Bands.KD_DESK10, flat, DoubleArray(4), fbGuard = true)
        assertTrue(p.gains.all { it == 0.0 }); assertTrue(p.notes.isEmpty())
    }
}
