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
            // Unlock-handoff overlay on this device: transient (fires at unlock,
            // never again), so it must never anchor alarm-time launches.
            "eu.toneiv.ubktouch",
            // MIUI system plugin: torch/volume/overlay surfaces with no launcher
            // activity (unlistable in the picker). System UI, never gated.
            "miui.systemui.plugin",
        )

    /**
     * Locked-or-dark suppression: the gate never applies while the keyguard is
     * up or the screen is off (lockscreen camera/torch/secure surfaces).
     * Single named seam so ROM keyguard nuances stay swappable + testable.
     */
    fun isLockedOut(
        interactive: Boolean,
        keyguardLocked: Boolean,
    ): Boolean = !interactive || keyguardLocked

    /**
     * Forced package-cache refresh (new keyboard/launcher installs) with
     * throttle: a force past the throttle wins; unforced never does here
     * (TTL staleness stays inside the resolvers).
     */
    fun refreshDue(
        nowMs: Long,
        lastForcedMs: Long,
        force: Boolean,
        throttleMs: Long = 5_000,
    ): Boolean = force && nowMs - lastForcedMs > throttleMs

    /**
     * Transparent system chrome (survival list plus user-declared overlays):
     * looked *through*, never anchored on. Whitelisted real apps are NOT
     * transparent — they are *used*.
     */
    fun isTransparent(
        pkg: String,
        transparentPkgs: Set<String>,
    ): Boolean = pkg in survivalPackages || pkg in transparentPkgs

    fun isGated(
        foregroundPackage: String,
        ownPackage: String,
        extraIgnored: Set<String> = emptySet(),
        userWhitelist: Set<String> = emptySet(),
    ): Boolean {
        if (foregroundPackage.isEmpty()) return false
        if (foregroundPackage == ownPackage) return false
        if (foregroundPackage in extraIgnored) return false
        if (foregroundPackage in userWhitelist) return false
        return foregroundPackage !in survivalPackages
    }

    fun isSessionActive(
        nowMs: Long,
        sessionExpiryMs: Long,
    ): Boolean = nowMs < sessionExpiryMs
}
