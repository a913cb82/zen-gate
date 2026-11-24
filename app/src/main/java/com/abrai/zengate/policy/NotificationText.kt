package com.abrai.zengate.policy

/** Notification status lines. Pure so the shade copy is pinned headlessly. */
object NotificationText {
    private fun clock(ms: Long): String {
        val s = (ms / 1_000).coerceAtLeast(0)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    fun session(
        remainingMs: Long,
        endLabel: String,
    ): String = "Open · ${clock(remainingMs)} left · until $endLabel"

    fun free(poolSec: Long): String = "Free time · ${poolSec.coerceAtLeast(0)}s left"

    fun blocking(usagesToday: Int): String = "Blocking · $usagesToday opens today"

    fun whitelisted(label: String): String = "$label · unlimited"

    fun paused(minLeft: Long): String = "Paused · $minLeft min left"
}
