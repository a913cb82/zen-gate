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
            // Item-3 exit: ubktouch + miui.systemui.plugin left this list.
            // The seeded user transparent set owns them now (see SeedTransparentPackages).
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
     * Transparent system chrome (hardcoded overlays only, plus user-declared):
     * looked *through*, never anchored on. Real survival surfaces (deskclock,
     * telecom/incallui/emergency) are never gated but are *used*, not
     * transparent — like any whitelisted app. Device overlays (ubktouch +
     * MIUI plugin) are user-owned via the seeded transparent set, not here.
     */
    val transparentSystemPackages: Set<String> =
        setOf(
            "com.android.systemui",
        )

    fun isTransparent(
        pkg: String,
        transparentPkgs: Set<String>,
    ): Boolean = pkg in transparentSystemPackages || pkg in transparentPkgs

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

    /**
     * Sticky-anchor decision for the window-event path: only real surfaces
     * anchor the status line and the no-window verdict. Transparent overlays
     * never anchor — not even gated ones the user marked transparent (the
     * look-through would be void otherwise). Gated apps anchor; whitelisted
     * apps and real survival surfaces (deskclock/telecom) anchor when not
     * transparent; IMEs, our own UI and empties never do (else every swipe
     * flaps the verdict and a whitelisted transient reads as free use).
     */
    fun shouldAnchor(
        pkg: String,
        ownPackage: String,
        extraIgnored: Set<String> = emptySet(),
        userWhitelist: Set<String> = emptySet(),
        transparentPkgs: Set<String> = emptySet(),
    ): Boolean {
        if (pkg.isEmpty()) return false
        if (pkg == ownPackage || pkg in extraIgnored) return false
        if (isTransparent(pkg, transparentPkgs)) return false
        return isGated(pkg, ownPackage, extraIgnored, userWhitelist) ||
            pkg in userWhitelist ||
            pkg in survivalPackages
    }

    fun isSessionActive(
        nowMs: Long,
        sessionExpiryMs: Long,
    ): Boolean = nowMs < sessionExpiryMs
}
