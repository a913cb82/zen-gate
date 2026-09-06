package com.abrai.zengate

/**
 * Synchronous in-memory mirror of [GateStore], kept fresh by [GateService]
 * (which is always alive). Lets [ZenGateService] and [SupervisorReceiver]
 * decide per event without I/O.
 */
object GateState {
    @Volatile var enabled: Boolean = true

    @Volatile var config: com.abrai.zengate.policy.ZenConfig =
        com.abrai.zengate.policy
            .ZenConfig()

    @Volatile var userWhitelist: Set<String> = emptySet()

    /** True once GateService has mirrored the store. Persist/decide gates on this. */
    @Volatile var storeLoaded: Boolean = false

    @Volatile var sessionExpiryMs: Long = 0L

    @Volatile var poolSec: Long = 10

    @Volatile var usagesToday: Int = 0

    @Volatile var lastGatedPkg: String? = null

    /** Sticky last foreground (any surface): alarm-time launches gate on this. */
    @Volatile var lastForegroundPkg: String? = null

    /** Elapsed timestamp of the sticky foreground (freshness is unknowable otherwise). */
    @Volatile var lastForegroundElapsedMs: Long = 0L

    @Volatile var drainEnterElapsedMs: Long = 0L

    @Volatile var ignorePkg: String? = null

    @Volatile var ignoreUntilElapsedMs: Long = 0L

    @Volatile var dayId: String = ""

    fun poolState(): com.abrai.zengate.policy.PoolState =
        com.abrai.zengate.policy.PoolState(
            poolSec = poolSec,
            usagesToday = usagesToday,
            dayId = dayId,
            sessionExpiryWallMs = sessionExpiryMs,
        )

    /** Write-through: every store write pairs with this so readers never see stale state. */
    fun applyPool(state: com.abrai.zengate.policy.PoolState) {
        poolSec = state.poolSec
        usagesToday = state.usagesToday
        dayId = state.dayId
        sessionExpiryMs = state.sessionExpiryWallMs
    }
}
