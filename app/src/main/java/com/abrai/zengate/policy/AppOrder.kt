package com.abrai.zengate.policy

/** One launchable app in the whitelist picker. */
data class AppRow(
    val label: String,
    val pkg: String,
)

/** Picker ordering: search filter, then allowed apps, each group alphabetical. */
object AppOrder {
    fun order(
        apps: List<AppRow>,
        allowed: Set<String>,
        query: String,
    ): List<AppRow> {
        val needle = query.trim()
        val filtered =
            if (needle.isEmpty()) {
                apps
            } else {
                apps.filter {
                    it.label.contains(needle, ignoreCase = true) ||
                        it.pkg.contains(needle, ignoreCase = true)
                }
            }
        return filtered.sortedWith(
            compareBy({ it.pkg !in allowed }, { it.label.lowercase() }, { it.pkg }),
        )
    }
}
