package com.abrai.zengate.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoolEngineTest {
    private val cfg = ZenConfig()
    private val t0 = 1_000_000_000L

    @Test
    fun `enter verdict drains when pool remains, blocks when empty`() {
        assertEquals(PoolEngine.EnterVerdict.DRAIN, PoolEngine.enterVerdict(1))
        assertEquals(PoolEngine.EnterVerdict.DRAIN, PoolEngine.enterVerdict(20))
        assertEquals(PoolEngine.EnterVerdict.BLOCK, PoolEngine.enterVerdict(0))
    }

    @Test
    fun `session end tops up grace pool and clears expiry`() {
        val s = PoolState(poolSec = 0, usagesToday = 2, sessionExpiryWallMs = t0 + 300_000)
        val out = PoolEngine.endSession(s, cfg)
        assertEquals(0, out.sessionExpiryWallMs)
        assertEquals(10, out.poolSec)
        assertEquals(2, out.usagesToday)
        val rich = PoolState(poolSec = 18, sessionExpiryWallMs = t0 + 1)
        assertEquals(18, PoolEngine.endSession(rich, cfg).poolSec)
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
    fun `unlock sets wall-clock expiry`() {
        val out = PoolEngine.unlock(PoolState(poolSec = 0, usagesToday = 2), t0, cfg)
        assertEquals(3, out.usagesToday)
        assertEquals(10, out.poolSec)
        assertEquals(t0 + 300_000, out.sessionExpiryWallMs)
    }

    @Test
    fun `unlock keeps larger existing pool`() {
        val out = PoolEngine.unlock(PoolState(poolSec = 18), t0, cfg)
        assertEquals(18, out.poolSec)
    }

    @Test
    fun `session remaining is wall-clock time to expiry`() {
        assertEquals(
            240_000,
            PoolEngine.sessionRemainingMs(PoolState(sessionExpiryWallMs = t0 + 300_000), t0 + 60_000, cfg),
        )
        assertEquals(0, PoolEngine.sessionRemainingMs(PoolState(sessionExpiryWallMs = t0 + 300_000), t0 + 300_000, cfg))
        assertEquals(0, PoolEngine.sessionRemainingMs(PoolState(sessionExpiryWallMs = t0 + 300_000), t0 + 400_000, cfg))
        assertEquals(0, PoolEngine.sessionRemainingMs(PoolState(), t0, cfg))
    }

    @Test
    fun `midnight resets counters pool and session`() {
        val s = PoolState(poolSec = 0, usagesToday = 5, dayId = "2026-09-05", sessionExpiryWallMs = t0)
        assertTrue(PoolEngine.needsMidnightReset(s, "2026-09-06"))
        assertFalse(PoolEngine.needsMidnightReset(s, "2026-09-05"))
        assertFalse(PoolEngine.needsMidnightReset(PoolState(dayId = ""), "2026-09-06"))
        val out = PoolEngine.midnightReset("2026-09-06", t0, cfg)
        assertEquals(0, out.usagesToday)
        assertEquals(10, out.poolSec)
        assertEquals("2026-09-06", out.dayId)
        assertEquals(0, out.sessionExpiryWallMs)
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
