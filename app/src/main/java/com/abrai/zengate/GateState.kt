package com.abrai.zengate

/**
 * Synchronous in-memory mirror of [GateStore], kept fresh by [GateService]
 * (which is always alive). Lets [ZenGateService] decide per event without I/O.
 */
object GateState {
    @Volatile var enabled: Boolean = true

    @Volatile var sessionExpiryMs: Long = 0L

    @Volatile var ignorePkg: String? = null

    @Volatile var ignoreUntilElapsedMs: Long = 0L
}
