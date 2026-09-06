package com.abrai.zengate.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoolEngineTest {
    private val cfg = ZenConfig()
    private val t0 = 1_000_000_000L

    @Test
    fun `first refill seeds clock without granting`() {
        val out = PoolEngine.refill(PoolState(poolSec = 5), t0, cfg)
        assertEquals(5, out.poolSec)
        assertEquals(t0, out.lastRefillWallMs)
    }

    @Test
    fun `refill grants per interval and caps`() {
        val s = PoolState(poolSec = 5, lastRefillWallMs = t0)
        assertEquals(5, PoolEngine.refill(s, t0 + 299_000, cfg).poolSec)
        val one = PoolEngine.refill(s, t0 + 300_000, cfg)
        assertEquals(20, one.poolSec) // 5 + 20 capped at 20
        assertEquals(t0 + 300_000, one.lastRefillWallMs)
        val three = PoolEngine.refill(s, t0 + 900_000, cfg)
        assertEquals(20, three.poolSec)
    }

    @Test
    fun `drain floors at zero`() {
        assertEquals(7, PoolEngine.drain(PoolState(poolSec = 10), 3).poolSec)
        assertEquals(0, PoolEngine.drain(PoolState(poolSec = 2), 5).poolSec)
    }

    @Test
    fun `penalty escalates per unlock`() {
        assertEquals(30, PoolEngine.penaltySec(PoolState(usagesToday = 0), cfg))
        assertEquals(50, PoolEngine.penaltySec(PoolState(usagesToday = 2), cfg))
    }

    @Test
    fun `unlock counts guarantees pool and starts clocks`() {
        val out = PoolEngine.unlock(PoolState(poolSec = 0, usagesToday = 2), t0, cfg)
        assertEquals(3, out.usagesToday)
        assertEquals(10, out.poolSec)
        assertEquals(t0, out.sessionStartWallMs)
        assertEquals(0, out.sessionScreenOnMs)
    }

    @Test
    fun `unlock keeps larger existing pool`() {
        val out = PoolEngine.unlock(PoolState(poolSec = 18), t0, cfg)
        assertEquals(18, out.poolSec)
    }

    @Test
    fun `session remaining is min of allowance and hard limit`() {
        val s = PoolState(sessionStartWallMs = t0, sessionScreenOnMs = 60_000)
        assertEquals(240_000, PoolEngine.sessionRemainingMs(s, t0 + 60_000, cfg))
        // Hard limit binds: 1700s wall elapsed (100s left) but full allowance left.
        val old = PoolState(sessionStartWallMs = t0, sessionScreenOnMs = 0)
        assertEquals(100_000, PoolEngine.sessionRemainingMs(old, t0 + 1_700_000, cfg))
        // No session.
        assertEquals(0, PoolEngine.sessionRemainingMs(PoolState(), t0, cfg))
        // Expired allowance.
        val spent = PoolState(sessionStartWallMs = t0, sessionScreenOnMs = 300_000)
        assertEquals(0, PoolEngine.sessionRemainingMs(spent, t0 + 300_000, cfg))
    }

    @Test
    fun `midnight resets counters pool and session`() {
        val s = PoolState(poolSec = 0, usagesToday = 5, dayId = "2026-09-05", sessionStartWallMs = t0)
        assertTrue(PoolEngine.needsMidnightReset(s, "2026-09-06"))
        assertFalse(PoolEngine.needsMidnightReset(s, "2026-09-05"))
        assertFalse(PoolEngine.needsMidnightReset(PoolState(dayId = ""), "2026-09-06"))
        val out = PoolEngine.midnightReset("2026-09-06", t0, cfg)
        assertEquals(0, out.usagesToday)
        assertEquals(10, out.poolSec)
        assertEquals("2026-09-06", out.dayId)
        assertEquals(0, out.sessionStartWallMs)
    }

    @Test
    fun `next reset is next HH MM occurrence UTC`() {
        val zone = java.time.ZoneId.of("UTC")
        // 2001-09-09T01:46:40Z, reset 00:00 -> 2001-09-10T00:00:00Z.
        assertEquals(
            1_000_080_000_000L,
            com.abrai.zengate.GateAlarms
                .nextResetMs(1_000_000_000_000L, 0, 0, zone),
        )
        // Same instant, reset 06:00 -> today 2001-09-09T06:00:00Z.
        assertEquals(
            1_000_015_200_000L,
            com.abrai.zengate.GateAlarms
                .nextResetMs(1_000_000_000_000L, 6, 0, zone),
        )
    }

    @Test
    fun `day id shifts at reset time`() {
        val zone = java.time.ZoneId.of("UTC")
        // 2001-09-09T01:46:40Z with reset 00:00 -> 09-09; with reset 06:00 -> 09-08.
        assertEquals("2001-09-09", PoolEngine.dayIdFor(1_000_000_000_000L, 0, 0, zone))
        assertEquals("2001-09-08", PoolEngine.dayIdFor(1_000_000_000_000L, 6, 0, zone))
    }

    @Test
    fun `user whitelist exempts apps`() {
        val white = setOf("com.example.app")
        val own = "com.abrai.zengate"
        assertFalse(GatePolicy.isGated("com.example.app", own, emptySet(), white))
        assertTrue(GatePolicy.isGated("com.instagram.android", own, emptySet(), white))
    }
}
