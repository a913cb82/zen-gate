package com.abrai.zengate.policy

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** All knobs from PLAN.md §3b with spec defaults. Global, not per-app. */
data class ZenConfig(
    val unlockPoolSec: Long = 10,
    val refillAmountSec: Long = 20,
    val refillIntervalSec: Long = 300,
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
    val lastRefillWallMs: Long = 0L,
    val sessionExpiryWallMs: Long = 0L,
    val sessionPending: Boolean = false,
)

/**
 * Pure pool/session math. No Android imports — unit-tested headless.
 * Sessions are plain wall-clock deadlines; expiry while whitelisted arms
 * [PoolState.sessionPending] instead of blocking immediately.
 */
object PoolEngine {
    /** Rolling refill: +amount per elapsed interval, capped at one refill amount. */
    fun refill(
        state: PoolState,
        nowWallMs: Long,
        cfg: ZenConfig = ZenConfig(),
    ): PoolState {
        if (state.lastRefillWallMs == 0L) return state.copy(lastRefillWallMs = nowWallMs)
        val elapsedSec = (nowWallMs - state.lastRefillWallMs) / 1_000
        if (elapsedSec < cfg.refillIntervalSec) return state
        val intervals = elapsedSec / cfg.refillIntervalSec
        val pool = (state.poolSec + intervals * cfg.refillAmountSec).coerceAtMost(cfg.refillAmountSec)
        return state.copy(
            poolSec = pool,
            lastRefillWallMs = state.lastRefillWallMs + intervals * cfg.refillIntervalSec * 1_000,
        )
    }

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
            sessionPending = false,
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
            lastRefillWallMs = nowWallMs,
            sessionExpiryWallMs = 0L,
            sessionPending = false,
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
