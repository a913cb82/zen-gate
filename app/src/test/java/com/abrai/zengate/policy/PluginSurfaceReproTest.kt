package com.abrai.zengate.policy

import com.abrai.zengate.policy.DeadlineVerdict.Outcome.Launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Item-3 migration: hardcoded exit for plugin surfaces; the user set owns them. */
class PluginSurfaceReproTest {
    private val own = "com.abrai.zengate"
    private val plugin = "miui.systemui.plugin"

    @Test fun `miui plugin gates without user transparency`() {
        assertTrue(GatePolicy.isGated(plugin, own))
        assertFalse(GatePolicy.isTransparent(plugin, emptySet()))
    }

    @Test fun `miui plugin over gated app launches for the sticky anchor`() {
        assertEquals(
            Launch("com.instagram.android"),
            DeadlineVerdict.decide(
                enabled = true,
                sessionRemainingMs = 0,
                rootPkg = plugin,
                lastEventPkg = "com.instagram.android",
                ownPkg = own,
                userWhitelist = setOf("com.ichi2.anki"),
                transparentPkgs = setOf(plugin),
                keyguardLocked = false,
                interactive = true,
            ),
        )
    }

    @Test fun `miui plugin never anchors once user-transparent`() {
        assertFalse(
            GatePolicy.shouldAnchor(plugin, own, emptySet(), emptySet(), setOf(plugin)),
        )
    }
}
