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
        /** Last real surface (sticky, never nulled); anchors the no-window case. */
        lastEventPkg: String?,
        ownPkg: String,
        userWhitelist: Set<String>,
        keyguardLocked: Boolean,
        interactive: Boolean,
    ): Outcome {
        if (!enabled) return Outcome.Skip
        if (sessionRemainingMs > 0) return Outcome.Skip
        if (!interactive || keyguardLocked) return Outcome.Skip
        if (rootPkg == null) {
            // No usage transitions on a lit screen (static surface, blind a11y
            // root): the sticky last real surface decides. Gated -> launch
            // (static home/app); whitelisted/transient/unknown -> stay quiet
            // (the app exit re-gates via the entry path).
            return if (lastEventPkg != null &&
                GatePolicy.isGated(lastEventPkg, ownPkg, emptySet(), userWhitelist)
            ) {
                Outcome.Launch(lastEventPkg)
            } else {
                Outcome.Skip
            }
        }
        if (!GatePolicy.isGated(rootPkg, ownPkg, emptySet(), userWhitelist)) return Outcome.Skip
        return Outcome.Launch(rootPkg)
    }
}
