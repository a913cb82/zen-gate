package com.abrai.zengate.policy

/**
 * Block-screen away-grace: a brief accidental exit (edge gesture, home
 * swipe) keeps the countdown; only a real walk-away restarts it.
 * Pure so the threshold is pinned headlessly.
 */
object BlockReset {
    const val GRACE_MS = 3_000L

    fun shouldReset(awayMs: Long): Boolean = awayMs > GRACE_MS
}
