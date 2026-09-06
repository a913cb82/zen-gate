package com.abrai.zengate

/**
 * Synchronous in-memory mirror of [GateStore], kept fresh by [GateService]
 * (which is always alive). Lets [ZenGateService] and [SupervisorReceiver]
 * decide per event without I/O.
 */
object GateState {
    @Volatile var enabled: Boolean = true

    /** True once GateService has mirrored the store. Persist/decide gates on this. */
    @Volatile var storeLoaded: Boolean = false

    @Volatile var sessionExpiryMs: Long = 0L

    @Volatile var poolSec: Long = 10

    @Volatile var usagesToday: Int = 0

    @Volatile var lastGatedPkg: String? = null

    @Volatile var drainEnterElapsedMs: Long = 0L

    @Volatile var sessionSegmentStartElapsedMs: Long = 0L

    @Volatile var ignorePkg: String? = null

    @Volatile var ignoreUntilElapsedMs: Long = 0L

    fun poolState(): com.abrai.zengate.policy.PoolState =
        com.abrai.zengate.policy.PoolState(
            poolSec = poolSec,
            usagesToday = usagesToday,
            dayId = dayId,
            lastRefillWallMs = lastRefillMs,
            sessionStartWallMs = sessionStartMs,
            sessionScreenOnMs = sessionScreenOnMs,
        )

    /** Write-through: every store write pairs with this so readers never see stale state. */
    fun applyPool(state: com.abrai.zengate.policy.PoolState) {
        poolSec = state.poolSec
        usagesToday = state.usagesToday
        dayId = state.dayId
        lastRefillMs = state.lastRefillWallMs
        sessionStartMs = state.sessionStartWallMs
        sessionScreenOnMs = state.sessionScreenOnMs
    }

    @Volatile var dayId: String = ""

    @Volatile var lastRefillMs: Long = 0L

    @Volatile var sessionStartMs: Long = 0L

    @Volatile var sessionScreenOnMs: Long = 0L
}
