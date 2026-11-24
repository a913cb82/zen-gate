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
    fun `session end clears expiry and leaves pool alone`() {
        val s = PoolState(poolSec = 0, usagesToday = 2, sessionExpiryWallMs = t0 + 300_000)
        val out = PoolEngine.endSession(s)
        assertEquals(0, out.sessionExpiryWallMs)
        assertEquals(0, out.poolSec)
        assertEquals(2, out.usagesToday)
    }

    @Test
    fun `quick open grants pool without counting an unlock`() {
        // Quick-open button: free time, no session, no usage increment.
        val out = PoolEngine.phoneUnlock(PoolState(poolSec = 0, usagesToday = 2), t0, cfg)
        assertEquals(2, out.usagesToday)
        assertEquals(10, out.poolSec)
        assertEquals(0, out.sessionExpiryWallMs)
    }

    @Test
    fun `quick-open wait defaults to five seconds`() {
        assertEquals(5, ZenConfig().quickWaitSec)
    }

    @Test
    fun `pause length is a knob with a 30 minute default`() {
        assertEquals(1_800, ZenConfig().pauseDurationSec)
        assertEquals(1_200, ZenConfig(pauseDurationSec = 1_200).pauseDurationSec)
    }

    @Test
    fun `phone unlock outside session grants grace pool`() {
        val out = PoolEngine.phoneUnlock(PoolState(poolSec = 0, usagesToday = 2), t0, cfg)
        assertEquals(10, out.poolSec)
        assertEquals(2, out.usagesToday)
        assertEquals(0, out.sessionExpiryWallMs)
    }

    @Test
    fun `phone unlock on shrunk config clamps the pool down on grant`() {
        // Config shrank 60 -> 10: the next grant re-seeds exactly, so the
        // stale oversized pool cannot linger.
        val out = PoolEngine.phoneUnlock(PoolState(poolSec = 60, usagesToday = 2), t0, cfg)
        assertEquals(10, out.poolSec)
        assertEquals(2, out.usagesToday)
    }

    @Test
    fun `phone unlock inside session leaves pool alone`() {
        val s = PoolState(poolSec = 0, usagesToday = 2, sessionExpiryWallMs = t0 + 300_000)
        assertEquals(s, PoolEngine.phoneUnlock(s, t0, cfg))
    }

    @Test
    fun `phone unlock inside session never clamps a shrunk pool`() {
        // Clamp happens on grant only; a live session is untouched.
        val s = PoolState(poolSec = 60, usagesToday = 2, sessionExpiryWallMs = t0 + 300_000)
        assertEquals(s, PoolEngine.phoneUnlock(s, t0, cfg))
    }

    @Test
    fun `open tap grants session only, pool untouched`() {
        val out = PoolEngine.unlock(PoolState(poolSec = 0, usagesToday = 2), t0, cfg)
        assertEquals(3, out.usagesToday)
        assertEquals(0, out.poolSec)
        assertEquals(t0 + 300_000, out.sessionExpiryWallMs)
    }

    @Test
    fun `unlock saturates expiry instead of overflowing`() {
        val out = PoolEngine.unlock(PoolState(), t0, ZenConfig(sessionAllowSec = Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, out.sessionExpiryWallMs)
    }

    @Test
    fun `unlock caps usages at Int MAX`() {
        val out = PoolEngine.unlock(PoolState(usagesToday = Int.MAX_VALUE), t0, cfg)
        assertEquals(Int.MAX_VALUE, out.usagesToday)
    }

    @Test
    fun `drain floors at zero`() {
        assertEquals(7, PoolEngine.drain(PoolState(poolSec = 10), 3).poolSec)
        assertEquals(0, PoolEngine.drain(PoolState(poolSec = 2), 5).poolSec)
    }

    @Test
    fun `display pool subtracts open segment, floors at zero`() {
        assertEquals(7, PoolEngine.displayPoolSec(10, 1_000, 4_000))
        assertEquals(10, PoolEngine.displayPoolSec(10, null, 99_000))
        assertEquals(0, PoolEngine.displayPoolSec(2, 1_000, 9_000))
    }

    @Test
    fun `penalty escalates per unlock`() {
        assertEquals(30, PoolEngine.penaltySec(PoolState(usagesToday = 0), cfg))
        assertEquals(50, PoolEngine.penaltySec(PoolState(usagesToday = 2), cfg))
    }

    @Test
    fun `penalty never goes negative`() {
        val weird = ZenConfig(baseWaitSec = -100, waitIncrementSec = -10)
        assertEquals(0, PoolEngine.penaltySec(PoolState(usagesToday = 0), weird))
        assertEquals(0, PoolEngine.penaltySec(PoolState(usagesToday = 50), weird))
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
        assertTrue(PoolEngine.needsMidnightReset(PoolState(dayId = ""), "2026-09-06"))
        val out = PoolEngine.midnightReset("2026-09-06", cfg)
        assertEquals(0, out.usagesToday)
        assertEquals(10, out.poolSec)
        assertEquals("2026-09-06", out.dayId)
        assertEquals(0, out.sessionExpiryWallMs)
    }

    @Test
    fun `day id shifts at reset time`() {
        val zone = java.time.ZoneId.of("UTC")
        // 2001-09-09T01:46:40Z with reset 00:00 -> 09-09; with reset 06:00 -> 09-08.
        assertEquals("2001-09-09", PoolEngine.dayIdFor(1_000_000_000_000L, 0, 0, zone))
        assertEquals("2001-09-08", PoolEngine.dayIdFor(1_000_000_000_000L, 6, 0, zone))
    }

    @Test
    fun `day id clamps out-of-range reset times instead of throwing`() {
        val zone = java.time.ZoneId.of("UTC")
        assertEquals(
            PoolEngine.dayIdFor(1_000_000_000_000L, 23, 59, zone),
            PoolEngine.dayIdFor(1_000_000_000_000L, 25, 99, zone),
        )
        assertEquals(
            PoolEngine.dayIdFor(1_000_000_000_000L, 0, 0, zone),
            PoolEngine.dayIdFor(1_000_000_000_000L, -5, -9, zone),
        )
    }

    @Test
    fun `day id agrees with next reset across the DST gap`() {
        // America/New_York springs forward 2026-03-08 02:00 -> 03:00.
        val zone = java.time.ZoneId.of("America/New_York")
        val instants =
            listOf(
                1_773_043_200_000L, // 2026-03-08T00:00:00-05:00
                1_773_046_800_000L, // 2026-03-08T01:00:00-05:00
                1_773_050_400_000L, // 2026-03-08T03:00:00-04:00 (first valid minute)
                1_773_057_600_000L, // 2026-03-08T05:00:00-04:00
            )
        val resets = listOf(Triple(0, 0, "midnight"), Triple(2, 30, "gap"), Triple(6, 0, "morning"))
        for (now in instants) {
            for ((h, m, _) in resets) {
                val next =
                    com.abrai.zengate.GateAlarms
                        .nextResetMs(now, h, m, zone)
                val nextDate =
                    java.time.Instant
                        .ofEpochMilli(next)
                        .atZone(zone)
                        .toLocalDate()
                        .minusDays(1)
                        .toString()
                assertEquals(
                    "now=$now reset=$h:$m",
                    nextDate,
                    PoolEngine.dayIdFor(now, h, m, zone),
                )
            }
        }
    }

    @Test
    fun `user whitelist exempts apps`() {
        val white = setOf("com.example.app")
        val own = "com.abrai.zengate"
        assertFalse(GatePolicy.isGated("com.example.app", own, emptySet(), white))
        assertTrue(GatePolicy.isGated("com.instagram.android", own, emptySet(), white))
    }

    @Test
    fun `pause is a wall-clock comparison`() {
        // Fixed 30-min kill switch: paused until the deadline passes it.
        assertTrue(GatePolicy.isPaused(1_000, 999))
        assertFalse(GatePolicy.isPaused(1_000, 1_000))
        assertFalse(GatePolicy.isPaused(0, 999_999_999))
    }

    @Test
    fun `pause remaining floors at zero`() {
        assertEquals(600, GatePolicy.pauseRemainingMs(1_000, 400))
        assertEquals(0, GatePolicy.pauseRemainingMs(1_000, 1_000))
        assertEquals(0, GatePolicy.pauseRemainingMs(1_000, 5_000))
    }

    @Test
    fun `pause minutes are a ceiling`() {
        // "back in 30 min" holds until actually under 29:00; 0 stays 0.
        assertEquals(30, GatePolicy.pauseMinLeft(30 * 60 * 1_000))
        assertEquals(30, GatePolicy.pauseMinLeft(29 * 60 * 1_000 + 1))
        assertEquals(29, GatePolicy.pauseMinLeft(29 * 60 * 1_000))
        assertEquals(1, GatePolicy.pauseMinLeft(60_000))
        assertEquals(1, GatePolicy.pauseMinLeft(1))
        assertEquals(0, GatePolicy.pauseMinLeft(0))
    }

    @Test
    fun `settle segment deducts whole elapsed seconds`() {
        // Screen-off path: the open segment is owed to the pool.
        assertEquals(7L, PoolEngine.settleSegment(PoolState(poolSec = 10), 1_000, 4_000)?.poolSec)
        assertEquals(0L, PoolEngine.settleSegment(PoolState(poolSec = 2), 1_000, 9_000)?.poolSec)
    }

    @Test
    fun `settle segment with no open segment owes nothing`() {
        assertEquals(null, PoolEngine.settleSegment(PoolState(poolSec = 10), null, 99_000))
    }

    @Test
    fun `settle segment under a second keeps the pool`() {
        assertEquals(10L, PoolEngine.settleSegment(PoolState(poolSec = 10), 1_000, 1_500)?.poolSec)
    }
}
