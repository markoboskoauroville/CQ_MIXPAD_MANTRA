package com.mantra.cqmixpad

import org.junit.Assert.assertEquals
import org.junit.Test

/** The scales calibrated 8.11.2026 on CQ MixPad's own screen (cq_mixpad_protocol "Calibrated scales"). */
class ScalesTest {
    private fun near(want: Double, got: Double, pct: Double = 0.3) = assertEquals(want, got, want * pct / 100)

    @Test fun frequency() {
        near(20.0, Recorder.hzOf(10697)); near(105.0, Recorder.hzOf(21723)); near(920.0, Recorder.hzOf(36152))
        near(3000.0, Recorder.hzOf(44009)); near(9980.0, Recorder.hzOf(52000))
        assertEquals(36152.0, Recorder.rawHz(920.0).toDouble(), 30.0)
    }

    @Test fun times() {
        near(0.15, Recorder.msOf(12966)); near(14.0, Recorder.msOf(24702)); near(200.0, Recorder.msOf(31584)); near(2.27, Recorder.msOf(20000))
        assertEquals(24702.0, Recorder.rawMs(14.0).toDouble(), 3.0)
    }

    @Test fun tables() {
        assertEquals(15, Recorder.ratioIndex(1.9)); assertEquals(20, Recorder.ratioIndex(3.0)); assertEquals(40, Recorder.ratioIndex(1000.0))
        assertEquals(19, Recorder.widthIndex(1.0 / 3)); assertEquals(25, Recorder.widthIndex(0.11)); assertEquals(6, Recorder.widthIndex(1.0))
    }
}
