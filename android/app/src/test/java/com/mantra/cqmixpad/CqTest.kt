package com.mantra.cqmixpad

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every example message of the CQ MIDI Protocol V1.2 issue 4 PDF, rebuilt by Cq. */
class CqTest {
    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    private fun src(id: String) = Cq.src(id)!!

    @Test fun mutes() {
        assertArrayEquals(hex("B0 63 00 B0 62 00 B0 06 00 B0 26 01"), Cq.setMute(Cq.muteParam(src("Ip1")), true))
        assertArrayEquals(hex("B0 63 00 B0 62 44 B0 06 00 B0 26 00"), Cq.setMute(Cq.masterMuteParam("MAIN")!!, false))
        assertArrayEquals(hex("B0 63 00 B0 62 02 B0 60 00"), Cq.increment(Cq.muteParam(src("Ip3"))))
    }

    @Test fun levels() {
        assertArrayEquals(hex("B0 63 40 B0 62 00 B0 06 62 B0 26 00"), Cq.setValue(Cq.sendParam(src("Ip1"), "MAIN")!!, Cq.dbToValue(0.0)))
        assertArrayEquals(hex("B0 63 40 B0 62 00 B0 06 2E B0 26 40"), Cq.setValue(Cq.sendParam(src("Ip1"), "MAIN")!!, Cq.dbToValue(-20.0)))
        assertArrayEquals(hex("B0 63 41 B0 62 49 B0 06 4E B0 26 40"), Cq.setValue(Cq.sendParam(src("Ip12"), "OUT2")!!, Cq.dbToValue(-5.0)))
        assertArrayEquals(hex("B0 63 4F B0 62 05 B0 06 73 B0 26 40"), Cq.setValue(Cq.masterParam("OUT5")!!, Cq.dbToValue(5.0)))
        assertArrayEquals(hex("B0 63 46 B0 62 20 B0 06 3E B0 26 00"), Cq.setValue(Cq.sendParam(src("FX2"), "OUT1")!!, Cq.dbToValue(-10.0)))
        assertArrayEquals(hex("B0 63 4E B0 62 05 B0 06 36 B0 26 00"), Cq.setValue(Cq.sendParam(src("FX1"), "FX2")!!, Cq.dbToValue(-15.0)))
        assertArrayEquals(hex("B0 63 4F B0 62 20 B0 06 0F B0 26 40"), Cq.setValue(Cq.dcaLevelParam(1), Cq.dbToValue(-40.0)))
        assertArrayEquals(hex("B0 63 46 B0 62 2E B0 60 00"), Cq.increment(Cq.sendParam(src("FX3"), "OUT3")!!))
        assertEquals(null, Cq.sendParam(src("FX1"), "FX1"))
        // the table rows: ST1 → FX1 = 4C 74, USB → OUT1 = 43 14, BT → FX4 = 4D 0F
        assertEquals((0x4C shl 7) or 0x74, Cq.sendParam(src("ST1"), "FX1"))
        assertEquals((0x43 shl 7) or 0x14, Cq.sendParam(src("USB"), "OUT1"))
        assertEquals((0x4D shl 7) or 0x0F, Cq.sendParam(src("BT"), "FX4"))
    }

    @Test fun pans() {
        assertArrayEquals(hex("B0 63 50 B0 62 00 B0 06 40 B0 26 00"), Cq.setValue(Cq.panMainParam(src("Ip1")), Cq.panToValue(0.0)))
        assertArrayEquals(hex("B0 63 50 B0 62 00 B0 06 7F B0 26 7F"), Cq.setValue(Cq.panMainParam(src("Ip1")), Cq.panToValue(100.0)))
        assertArrayEquals(hex("B0 63 50 B0 62 00 B0 06 00 B0 26 00"), Cq.setValue(Cq.panMainParam(src("Ip1")), Cq.panToValue(-100.0)))
        assertEquals((0x50 shl 7) or 0x60, Cq.panOutParam(src("Ip3"), 3))
        assertEquals((0x56 shl 7) or 0x14, Cq.panOutParam(src("FX1"), 1))
        assertEquals((0x51 shl 7) or 0x30, Cq.panOutParam(src("Ip10"), 1))
    }

    @Test fun getsScenesKeys() {
        assertArrayEquals(hex("B0 63 52 B0 62 64 B0 60 7F"), Cq.get(Cq.panOutParam(src("ST1"), 1)))
        assertArrayEquals(hex("B0 63 4F B0 62 0E B0 60 7F"), Cq.get(Cq.masterParam("FX2")!!))
        assertArrayEquals(hex("B0 00 00 C0 06"), Cq.scene(7))
        assertArrayEquals(hex("90 31 7F"), Cq.softKeyPress(2))
    }

    @Test fun parserReadsTheMixersAnswer() {
        // measured from the real CQ-12T, 7.11.2026
        val got = ArrayList<Pair<Int, Int>>()
        val p = Cq.Parser { a, b -> got += a to b }
        val b = hex("b0 63 00 b0 62 00 b0 06 00 b0 26 01 b0 63 40 b0 62 00 b0 06 64 b0 26 40")
        p.feed(b, b.size)
        assertEquals(listOf(0 to 1, (0x40 shl 7) to ((0x64 shl 7) or 0x40)), got)
        assertEquals(0.5, Cq.valueToDb((0x64 shl 7) or 0x40), 0.6)
    }
}
