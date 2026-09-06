package com.abrai.zengate.policy

/**
 * Pure alarm-time launch decision. Inputs are all live-at-decision facts —
 * chiefly the accessibility service's current active-window root — so stale
 * sticky state (transient overlays, reveal-without-event races, our own
 * dismiss transitions) cannot poison it. Unit-tested headless.
 */
object DeadlineVerdict {
    sealed interface Outcome {
        /** Show the block anchored at [pkg] (label + post-unlock re-entry). */
        data class Launch(
            val pkg: String,
        ) : Outcome

        /** Stay quiet; the window-event entry path owns the next transition. */
        data object Skip : Outcome
    }

    fun decide(
        enabled: Boolean,
        sessionRemainingMs: Long,
        /** Live active-window root package; null when none is visible. */
        rootPkg: String?,
        /** Last gated surface; anchor when no window is visible. */
        fallbackPkg: String?,
        ownPkg: String,
        userWhitelist: Set<String>,
        keyguardLocked: Boolean,
        interactive: Boolean,
    ): Outcome {
        if (!enabled) return Outcome.Skip
        if (sessionRemainingMs > 0) return Outcome.Skip
        if (!interactive || keyguardLocked) return Outcome.Skip
        if (rootPkg == null) {
            // No visible window on a lit, unlocked screen (reveal race): anchor
            // at the last gated surface; the label may be stale but the wait is
            // real and the next event re-gates precisely.
            return if (!fallbackPkg.isNullOrEmpty()) Outcome.Launch(fallbackPkg) else Outcome.Skip
        }
        if (!GatePolicy.isGated(rootPkg, ownPkg, emptySet(), userWhitelist)) return Outcome.Skip
        return Outcome.Launch(rootPkg)
    }
}
