package com.mantra.cqmixpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class FeedbackTest {
    private val rnd = Random(7)
    private fun music(): DoubleArray = DoubleArray(4096) { k -> if (k == 0) 0.0 else (1.0 / k) * (0.5 + rnd.nextDouble()) }

    @Test fun aSteadyRingingToneIsFeedback() {
        val f = Feedback(); var e: Feedback.Event? = null
        for (frame in 0 until 12) { val s = music(); s[427] = 50.0 * (1 + frame * 0.1); e = f.onSpectrum(s) ?: e }   // 427 bins = 2502 Hz
        assertNotNull(e); assertEquals(2502.0, e!!.hz, 6.0)
    }

    @Test fun aVoiceWithVibratoIsNot() {
        val f = Feedback(); var e: Feedback.Event? = null
        for (frame in 0 until 30) { val s = music(); s[427 + listOf(0, 3, 6, 3, 0, -3)[frame % 6]] = 50.0; e = f.onSpectrum(s) ?: e }
        assertNull(e)
    }

    @Test fun musicAloneIsNot() {
        val f = Feedback(); var e: Feedback.Event? = null
        repeat(40) { e = f.onSpectrum(music()) ?: e }
        assertNull(e)
    }

    @Test fun theMicThatRoseIsTheCulprit() {
        val before = floatArrayOf(-30f, -25f, -40f, -20f); val now = floatArrayOf(-29f, -24f, -12f, -21f)
        assertEquals(2, Feedback.culprit(before, now, listOf(0, 1, 2, 3)))
        assertNull(Feedback.culprit(before, before, listOf(0, 1, 2, 3)))
        val outB = FloatArray(8) { -30f }; val outN = outB.copyOf(); outN[1] = -10f
        assertEquals("OUT2", Feedback.ringingMix(outB, outN))
    }
}
