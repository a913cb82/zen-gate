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
        if (rootPkg == null) return anchorOutcome(lastEventPkg, ownPkg, userWhitelist)
        if (!GatePolicy.isGated(rootPkg, ownPkg, emptySet(), userWhitelist)) {
            // Transparent system surface (survival list): the real app sits
            // behind the overlay, so decide by the sticky anchor. Our own UI
            // as root means the block is already up: never re-launch. A real
            // whitelisted use-surface (Anki) stays quiet instead.
            if (rootPkg != ownPkg && rootPkg in GatePolicy.survivalPackages) {
                return anchorOutcome(lastEventPkg, ownPkg, userWhitelist)
            }
            return Outcome.Skip
        }
        return Outcome.Launch(rootPkg)
    }

    /**
     * Sticky-anchor decision for no-window and transparent-overlay cases.
     * Gated anchor -> launch (static home/app, app behind overlay);
     * whitelisted/transient/unknown anchor -> stay quiet (the app exit
     * re-gates via the entry path).
     */
    private fun anchorOutcome(
        lastEventPkg: String?,
        ownPkg: String,
        userWhitelist: Set<String>,
    ): Outcome =
        if (lastEventPkg != null &&
            GatePolicy.isGated(lastEventPkg, ownPkg, emptySet(), userWhitelist)
        ) {
            Outcome.Launch(lastEventPkg)
        } else {
            Outcome.Skip
        }
}
