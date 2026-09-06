package com.abrai.zengate.policy

/** All knobs from PLAN.md §3b with spec defaults. Global, not per-app. */
data class ZenConfig(
    val unlockPoolSec: Long = 10,
    val refillAmountSec: Long = 20,
    val refillIntervalSec: Long = 300,
    val poolCapSec: Long = 20,
    val baseWaitSec: Long = 30,
    val waitIncrementSec: Long = 10,
    val sessionAllowSec: Long = 300,
    val sessionHardLimitSec: Long = 1_800,
)

/** Persisted engine state. Wall clocks in ms, durations in s/ms as named. */
data class PoolState(
    val poolSec: Long = 10,
    val usagesToday: Int = 0,
    val dayId: String = "",
    val lastRefillWallMs: Long = 0L,
    val sessionStartWallMs: Long = 0L,
    val sessionScreenOnMs: Long = 0L,
)

/**
 * Pure pool/session math. No Android imports — unit-tested headless.
 * Callers feed explicit timestamps; screen on/off accumulation is tracked
 * by the caller (GateService) via [sessionScreenOnMs].
 */
object PoolEngine {
    /** Rolling refill: +amount per elapsed interval, hard-capped. First call seeds the clock. */
    fun refill(
        state: PoolState,
        nowWallMs: Long,
        cfg: ZenConfig = ZenConfig(),
    ): PoolState {
        if (state.lastRefillWallMs == 0L) return state.copy(lastRefillWallMs = nowWallMs)
        val elapsedSec = (nowWallMs - state.lastRefillWallMs) / 1_000
        if (elapsedSec < cfg.refillIntervalSec) return state
        val intervals = elapsedSec / cfg.refillIntervalSec
        val pool = (state.poolSec + intervals * cfg.refillAmountSec).coerceAtMost(cfg.poolCapSec)
        return state.copy(
            poolSec = pool,
            lastRefillWallMs = state.lastRefillWallMs + intervals * cfg.refillIntervalSec * 1_000,
        )
    }

    fun drain(
        state: PoolState,
        seconds: Long,
    ): PoolState = state.copy(poolSec = (state.poolSec - seconds).coerceAtLeast(0))

    fun penaltySec(
        state: PoolState,
        cfg: ZenConfig = ZenConfig(),
    ): Long = cfg.baseWaitSec + cfg.waitIncrementSec * state.usagesToday

    /** Unlock: count it, start the session clocks, guarantee the grace pool. */
    fun unlock(
        state: PoolState,
        nowWallMs: Long,
        cfg: ZenConfig = ZenConfig(),
    ): PoolState =
        state.copy(
            usagesToday = state.usagesToday + 1,
            sessionStartWallMs = nowWallMs,
            sessionScreenOnMs = 0L,
            poolSec = state.poolSec.coerceAtLeast(cfg.unlockPoolSec),
        )

    fun hasSession(state: PoolState): Boolean = state.sessionStartWallMs != 0L

    /**
     * Remaining session ms: allowance burns screen-on time only, hard limit
     * burns wall time. Whichever hits zero first ends it.
     */
    fun sessionRemainingMs(
        state: PoolState,
        nowWallMs: Long,
        cfg: ZenConfig = ZenConfig(),
    ): Long {
        if (!hasSession(state)) return 0L
        val allowanceLeft = cfg.sessionAllowSec * 1_000 - state.sessionScreenOnMs
        val hardLeft = cfg.sessionHardLimitSec * 1_000 - (nowWallMs - state.sessionStartWallMs)
        return minOf(allowanceLeft, hardLeft).coerceAtLeast(0L)
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
            sessionStartWallMs = 0L,
            sessionScreenOnMs = 0L,
        )

    fun needsMidnightReset(
        state: PoolState,
        todayId: String,
    ): Boolean = state.dayId.isNotEmpty() && state.dayId != todayId
}
