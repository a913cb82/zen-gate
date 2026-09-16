package com.abrai.zengate.policy

/**
 * Stage-1 transparency rule (pure core). Window kinds come from joining
 * accessibility-event windowIds to getWindows() (see ZenGateService probe);
 * the service caches pkg -> kind with a short TTL, and this rule decides on
 * the cached kind only. Unknown (including blind API) -> NOT transparent, so
 * behavior degrades to the package-list status quo.
 */
object OverlayRole {
    /** Window kinds that matter for transparency (mirrors AccessibilityWindowInfo types). */
    enum class Kind {
        APPLICATION,
        ACCESSIBILITY_OVERLAY,
        INPUT_METHOD,
        SYSTEM,
        OTHER,
    }

    /**
     * True only for a positively-identified non-application window kind:
     * overlay-only packages (gesture helpers, shade plugins) are pass-through
     * and must not anchor launches or blind the last-gated anchor.
     */
    fun isTransparent(cachedKind: Kind?): Boolean = cachedKind != null && cachedKind != Kind.APPLICATION
}
