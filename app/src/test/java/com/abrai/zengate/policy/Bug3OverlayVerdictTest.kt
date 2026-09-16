package com.abrai.zengate.policy

import com.abrai.zengate.policy.DeadlineVerdict.Outcome.Launch
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BUG 3 repro (RED): whitelisted survival overlay (UbikiTouch,
 * eu.toneiv.ubktouch) usage-foreground over a gated app (Instagram).
 * Sticky lastEventPkg still anchors Instagram (survival never anchors,
 * ZenGateService.kt:61), but the live root is survival -> verdict Skips,
 * so every pool-expiry alarm while the overlay is up stays quiet.
 * Desired: Launch anchored at the sticky gated pkg.
 */
class Bug3OverlayVerdictTest {
    @Test
    fun `survival overlay over gated app launches for the sticky anchor`() {
        assertEquals(
            Launch("com.instagram.android"),
            DeadlineVerdict.decide(
                enabled = true,
                sessionRemainingMs = 0,
                rootPkg = "eu.toneiv.ubktouch",
                lastEventPkg = "com.instagram.android",
                ownPkg = "com.abrai.zengate",
                userWhitelist = setOf("com.ichi2.anki", "eu.toneiv.ubktouch"),
                keyguardLocked = false,
                interactive = true,
            ),
        )
    }
}
