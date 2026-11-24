package com.abrai.zengate.policy

/**
 * Pure start/keep-ticking predicate for the 1/s notification ticker.
 * Sessions tick (wall-clock deadline) as well as free-time grace;
 * blocking/paused/dark screens never do.
 */
object TickGate {
    fun shouldTick(
        line: String,
        interactive: Boolean,
        active: Boolean,
        hasSession: Boolean,
        poolSec: Long,
    ): Boolean {
        if (!interactive || !active) return false
        if (!(line.startsWith("Open ") || line.startsWith("Free time"))) return false
        return hasSession || poolSec > 0
    }
}
