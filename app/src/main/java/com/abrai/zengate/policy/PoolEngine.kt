package com.abrai.zengate.policy

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** All knobs from PLAN.md §3b with spec defaults. Global, not per-app. */
data class ZenConfig(
    val unlockPoolSec: Long = 10,
    val baseWaitSec: Long = 30,
    val waitIncrementSec: Long = 10,
    val sessionAllowSec: Long = 300,
    /** Quick-open button countdown; 0 hides the button. */
    val quickWaitSec: Long = 5,
    /** Kill-switch length; editable in the Timers screen. */
    val pauseDurationSec: Long = 30 * 60,
    val resetHour: Int = 0,
    val resetMinute: Int = 0,
)

/** Persisted engine state. Wall clocks in ms, durations in s/ms as named. */
data class PoolState(
    val poolSec: Long = 10,
    val usagesToday: Int = 0,
    val dayId: String = "",
    val sessionExpiryWallMs: Long = 0L,
)

/**
 * Pure pool/session math. No Android imports — unit-tested headless.
 * Sessions are plain wall-clock deadlines. Session end tops up the grace pool;
 * the next gated entry drains it before any block.
 */
object PoolEngine {
    fun drain(
        state: PoolState,
        seconds: Long,
    ): PoolState = state.copy(poolSec = (state.poolSec - seconds).coerceAtLeast(0))

    /**
     * Settle an open drain segment against the clock: deduct whole elapsed
     * seconds from the pool. Null segment means no segment is open (fresh
     * grant, post-block, launcher transit) — callers then owe nothing.
     */
    fun settleSegment(
        state: PoolState,
        enterElapsedMs: Long?,
        nowElapsedMs: Long,
    ): PoolState? {
        val enter = enterElapsedMs ?: return null
        val spent = ((nowElapsedMs - enter) / 1_000).coerceAtLeast(0)
        return drain(state, spent)
    }

    /** What a gated-app entry does: drain the pool, or block when it is empty. */
    enum class EnterVerdict { DRAIN, BLOCK }

    /**
     * Live display value: the snapshot pool minus the open segment's elapsed
     * wall-clock (floored at zero). Null segment -> snapshot as-is.
     */
    fun displayPoolSec(
        poolSec: Long,
        segmentStartElapsedMs: Long?,
        nowElapsedMs: Long,
    ): Long {
        val start = segmentStartElapsedMs ?: return poolSec
        return (poolSec - (nowElapsedMs - start) / 1_000).coerceAtLeast(0)
    }

    fun enterVerdict(poolSec: Long): EnterVerdict = if (poolSec > 0) EnterVerdict.DRAIN else EnterVerdict.BLOCK

    fun penaltySec(
        state: PoolState,
        cfg: ZenConfig = ZenConfig(),
    ): Long = (cfg.baseWaitSec + cfg.waitIncrementSec * state.usagesToday).coerceAtLeast(0)

    /**
     * Phone unlock (fingerprint): outside a session this re-seeds the grace
     * pool to exactly cfg.unlockPoolSec. Max-only would let a stale oversized
     * pool linger after the knob shrinks, so the grant clamps both ways;
     * inside a session it changes nothing.
     */
    fun phoneUnlock(
        state: PoolState,
        nowWallMs: Long,
        cfg: ZenConfig = ZenConfig(),
    ): PoolState =
        if (sessionRemainingMs(state, nowWallMs, cfg) > 0) {
            state
        } else {
            state.copy(poolSec = cfg.unlockPoolSec.coerceAtLeast(0))
        }

    @Deprecated(
        "Pass explicit nowWallMs; service call sites are owned by another agent.",
        ReplaceWith("phoneUnlock(state, nowWallMs, cfg)"),
    )
    fun phoneUnlock(
        state: PoolState,
        cfg: ZenConfig = ZenConfig(),
    ): PoolState = phoneUnlock(state, 0L, cfg)

    /** Open tap: count it and start the timed session. The pool is untouched. */
    fun unlock(
        state: PoolState,
        nowWallMs: Long,
        cfg: ZenConfig = ZenConfig(),
    ): PoolState {
        // Saturating add: absurd sessionAllowSec values pin the deadline at
        // Long.MAX_VALUE instead of wrapping negative.
        val allowMs =
            if (cfg.sessionAllowSec <= 0) {
                0L
            } else if (cfg.sessionAllowSec > (Long.MAX_VALUE - nowWallMs) / 1_000) {
                Long.MAX_VALUE - nowWallMs
            } else {
                cfg.sessionAllowSec * 1_000
            }
        return state.copy(
            usagesToday = if (state.usagesToday == Int.MAX_VALUE) Int.MAX_VALUE else state.usagesToday + 1,
            sessionExpiryWallMs = nowWallMs + allowMs,
        )
    }

    /** Session end: clear the deadline; the pool is left alone (block follows). */
    fun endSession(state: PoolState): PoolState = state.copy(sessionExpiryWallMs = 0L)

    fun hasSession(state: PoolState): Boolean = state.sessionExpiryWallMs != 0L

    /** Remaining session ms: wall-clock time to the deadline, floored at zero. */
    fun sessionRemainingMs(
        state: PoolState,
        nowWallMs: Long,
        cfg: ZenConfig = ZenConfig(),
    ): Long {
        if (!hasSession(state)) return 0L
        return (state.sessionExpiryWallMs - nowWallMs).coerceAtLeast(0L)
    }

    /** Midnight: fresh counters, grace pool, any running session ends. */
    fun midnightReset(
        todayId: String,
        cfg: ZenConfig = ZenConfig(),
    ): PoolState =
        PoolState(
            poolSec = cfg.unlockPoolSec,
            usagesToday = 0,
            dayId = todayId,
            sessionExpiryWallMs = 0L,
        )

    @Deprecated(
        "nowWallMs was ignored; service call sites are owned by another agent.",
        ReplaceWith("midnightReset(todayId, cfg)"),
    )
    fun midnightReset(
        todayId: String,
        nowWallMs: Long,
        cfg: ZenConfig = ZenConfig(),
    ): PoolState = midnightReset(todayId, cfg)

    fun needsMidnightReset(
        state: PoolState,
        todayId: String,
    ): Boolean = state.dayId != todayId

    /**
     * Day id shifted by the configured reset time: before HH:MM counts as
     * yesterday. Out-of-range hour/minute are clamped (never throws), so a
     * corrupt knob still yields a stable id.
     */
    fun dayIdFor(
        nowWallMs: Long,
        hour: Int,
        minute: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowWallMs), zone)
        var date = now.toLocalDate()
        if (now.toLocalTime() < java.time.LocalTime.of(h, m)) {
            date = date.minusDays(1)
        }
        return date.toString()
    }
}
