package com.abrai.zengate.policy

/**
 * Pure notification-status mapping (headless-tested). The Android side formats
 * [Session.expiryWallMs] to local time and posts the line; this decides which
 * line. Precedence: kill switch > live session > whitelisted foreground >
 * free pool > blocking.
 */
object GateStatus {
    sealed interface Status {
        data object Paused : Status

        data class Session(
            val expiryWallMs: Long,
        ) : Status

        data class Whitelisted(
            val pkg: String,
        ) : Status

        data class FreeTime(
            val poolSec: Long,
        ) : Status

        data class Blocking(
            val usagesToday: Int,
        ) : Status
    }

    fun describe(
        enabled: Boolean,
        sessionRemainingMs: Long,
        sessionExpiryWallMs: Long,
        poolSec: Long,
        usagesToday: Int,
        foregroundPkg: String?,
        userWhitelist: Set<String>,
    ): Status {
        if (!enabled) return Status.Paused
        if (sessionRemainingMs > 0) return Status.Session(sessionExpiryWallMs)
        if (foregroundPkg != null && foregroundPkg in userWhitelist) {
            return Status.Whitelisted(foregroundPkg)
        }
        if (poolSec > 0) return Status.FreeTime(poolSec)
        return Status.Blocking(usagesToday)
    }
}
