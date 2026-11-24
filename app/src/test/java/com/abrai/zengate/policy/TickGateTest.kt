package com.abrai.zengate.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TickGateTest {
    @Test
    fun `session ticks with empty pool on lit screen`() {
        // Regression: the ticker excluded sessions, freezing "Open · 4:59".
        assertTrue(TickGate.shouldTick("Open · 4:59 left · until 12:37", true, true, true, 0))
    }

    @Test
    fun `free grace ticks while pool remains`() {
        assertTrue(TickGate.shouldTick("Free time · 57s left", true, true, false, 57))
    }

    @Test
    fun `stops when pool drains with no session`() {
        assertFalse(TickGate.shouldTick("Free time · 0s left", true, true, false, 0))
    }

    @Test
    fun `never ticks blocking lines`() {
        assertFalse(TickGate.shouldTick("Blocking · 0 opens today", true, true, false, 0))
    }

    @Test
    fun `never ticks dark paused or inactive`() {
        assertFalse(TickGate.shouldTick("Open · 4:59 left · until 12:37", false, true, true, 0))
        assertFalse(TickGate.shouldTick("Open · 4:59 left · until 12:37", true, false, true, 0))
        assertFalse(TickGate.shouldTick("Paused · 30 min left", true, false, false, 0))
    }
}
