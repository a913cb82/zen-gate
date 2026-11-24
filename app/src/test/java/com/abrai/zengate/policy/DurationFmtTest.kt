package com.abrai.zengate.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFmtTest {
    @Test
    fun `clock formats mm ss`() {
        assertEquals("00:03", DurationFmt.clock(3))
        assertEquals("00:18", DurationFmt.clock(18))
        assertEquals("01:06", DurationFmt.clock(66))
    }

    @Test
    fun `clock rolls into hours when needed`() {
        assertEquals("1:00:00", DurationFmt.clock(3_600))
    }

    @Test
    fun `card wait value is seconds until a minute`() {
        assertEquals("0s", DurationFmt.waitValue(0))
        assertEquals("3s", DurationFmt.waitValue(3))
        assertEquals("16s", DurationFmt.waitValue(16))
        assertEquals("01:40", DurationFmt.waitValue(100))
    }

    @Test
    fun `open labels stay compact`() {
        assertEquals("Open for 1 s", DurationFmt.openLabel(1))
        assertEquals("Open for 15 s", DurationFmt.openLabel(15))
        assertEquals("Open for 1 min", DurationFmt.openLabel(60))
        assertEquals("Open for 5 min", DurationFmt.openLabel(300))
        assertEquals("Open for 1 min 38 s", DurationFmt.openLabel(98))
    }
}
