package com.abrai.zengate.policy

/**
 * M1: pure gate decision. Survival list is hardcoded per PLAN.md §3a;
 * user whitelist arrives in M4. No Android imports — unit-testable headless.
 */
object GatePolicy {
    val survivalPackages: Set<String> =
        setOf(
            "com.android.server.telecom",
            "com.android.incallui",
            "com.android.emergency",
            "com.android.systemui",
            "com.google.android.deskclock",
        )

    fun isGated(
        foregroundPackage: String,
        ownPackage: String,
        extraIgnored: Set<String> = emptySet(),
    ): Boolean {
        if (foregroundPackage.isEmpty()) return false
        if (foregroundPackage == ownPackage) return false
        if (foregroundPackage in extraIgnored) return false
        return foregroundPackage !in survivalPackages
    }
}
