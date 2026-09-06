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

    /** What a gated-app entry does: drain the pool, or block when it is empty. */
    enum class EnterVerdict { DRAIN, BLOCK }

    fun enterVerdict(poolSec: Long): EnterVerdict = if (poolSec > 0) EnterVerdict.DRAIN else EnterVerdict.BLOCK

    fun penaltySec(
        state: PoolState,
        cfg: ZenConfig = ZenConfig(),
    ): Long = cfg.baseWaitSec + cfg.waitIncrementSec * state.usagesToday

    /** Unlock: count it, set the wall-clock deadline, guarantee the grace pool. */
    fun unlock(
        state: PoolState,
        nowWallMs: Long,
        cfg: ZenConfig = ZenConfig(),
    ): PoolState =
        state.copy(
            usagesToday = state.usagesToday + 1,
            sessionExpiryWallMs = nowWallMs + cfg.sessionAllowSec * 1_000,
            poolSec = state.poolSec.coerceAtLeast(cfg.unlockPoolSec),
        )

    /** Session end: clear the deadline and top up the grace pool. */
    fun endSession(
        state: PoolState,
        cfg: ZenConfig = ZenConfig(),
    ): PoolState =
        state.copy(
            sessionExpiryWallMs = 0L,
            poolSec = state.poolSec.coerceAtLeast(cfg.unlockPoolSec),
        )

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
        nowWallMs: Long,
        cfg: ZenConfig = ZenConfig(),
    ): PoolState =
        PoolState(
            poolSec = cfg.unlockPoolSec,
            usagesToday = 0,
            dayId = todayId,
            sessionExpiryWallMs = 0L,
        )

    fun needsMidnightReset(
        state: PoolState,
        todayId: String,
    ): Boolean = state.dayId.isNotEmpty() && state.dayId != todayId

    /** Day id shifted by the configured reset time: before HH:MM counts as yesterday. */
    fun dayIdFor(
        nowWallMs: Long,
        hour: Int,
        minute: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowWallMs), zone)
        var date = now.toLocalDate()
        if (now.toLocalTime() < java.time.LocalTime.of(hour, minute)) {
            date = date.minusDays(1)
        }
        return date.toString()
    }
}
