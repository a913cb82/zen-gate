package com.abrai.zengate.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatePolicyTest {
    private val own = "com.abrai.zengate"

    @Test
    fun `locked out exactly when dark or keyguard up`() {
        assertTrue(GatePolicy.isLockedOut(interactive = false, keyguardLocked = false))
        assertTrue(GatePolicy.isLockedOut(interactive = false, keyguardLocked = true))
        assertTrue(GatePolicy.isLockedOut(interactive = true, keyguardLocked = true))
        assertFalse(GatePolicy.isLockedOut(interactive = true, keyguardLocked = false))
    }

    @Test
    fun `cache refresh due on force past throttle, or never forced`() {
        assertTrue(GatePolicy.refreshDue(nowMs = 10_000, lastForcedMs = 0, force = true))
        assertFalse(GatePolicy.refreshDue(nowMs = 12_000, lastForcedMs = 10_000, force = true))
        assertFalse(GatePolicy.refreshDue(nowMs = 99_000, lastForcedMs = 0, force = false))
    }

    @Test
    fun `transparency covers hardcoded systemui plus the user set`() {
        val custom = setOf("com.example.overlay")
        assertTrue(GatePolicy.isTransparent("com.android.systemui", emptySet()))
        assertTrue(GatePolicy.isTransparent("com.example.overlay", custom))
        assertFalse(GatePolicy.isTransparent("com.ichi2.anki", custom))
        assertFalse(GatePolicy.isTransparent("com.instagram.android", custom))
    }

    @Test
    fun `device overlays are user-owned, not hardcoded`() {
        // Item-3 exit: ubktouch/plugin left the hardcoded sets; the seeded
        // user set carries them.
        assertFalse(GatePolicy.survivalPackages.contains("eu.toneiv.ubktouch"))
        assertFalse(GatePolicy.survivalPackages.contains("miui.systemui.plugin"))
        assertFalse(GatePolicy.isTransparent("eu.toneiv.ubktouch", emptySet()))
        assertFalse(GatePolicy.isTransparent("miui.systemui.plugin", emptySet()))
        val seeded = setOf("eu.toneiv.ubktouch", "miui.systemui.plugin")
        assertTrue(GatePolicy.isTransparent("eu.toneiv.ubktouch", seeded))
        assertTrue(GatePolicy.isTransparent("miui.systemui.plugin", seeded))
    }

    @Test
    fun `entry routes transparent overlays as pass-through`() {
        val seeded = setOf("eu.toneiv.ubktouch", "miui.systemui.plugin")
        assertEquals(GatePolicy.EntryRoute.Pass, GatePolicy.routeEntry("miui.systemui.plugin", own, transparentPkgs = seeded))
        assertEquals(GatePolicy.EntryRoute.Pass, GatePolicy.routeEntry("eu.toneiv.ubktouch", own, transparentPkgs = seeded))
        assertEquals(GatePolicy.EntryRoute.Pass, GatePolicy.routeEntry("com.android.systemui", own))
        assertEquals(
            GatePolicy.EntryRoute.Pass,
            GatePolicy.routeEntry("com.ichi2.anki", own, userWhitelist = setOf("com.ichi2.anki")),
        )
        assertEquals(GatePolicy.EntryRoute.Gate, GatePolicy.routeEntry("com.instagram.android", own))
        assertEquals(GatePolicy.EntryRoute.Gate, GatePolicy.routeEntry("app.olauncher", own))
        // Without the seed, exited overlays route to gate (seed contract).
        assertEquals(GatePolicy.EntryRoute.Gate, GatePolicy.routeEntry("miui.systemui.plugin", own))
    }

    @Test
    fun `transparency wins over gating when anchoring`() {
        // A gated app the user marked transparent is looked through: it must
        // never anchor the sticky verdict.
        assertFalse(
            GatePolicy.shouldAnchor(
                "com.instagram.android",
                own,
                emptySet(),
                emptySet(),
                setOf("com.instagram.android"),
            ),
        )
    }

    @Test
    fun `anchor covers gated, whitelisted, and real survival surfaces`() {
        val imes = setOf("com.google.android.inputmethod.latin")
        // Gated always anchors.
        assertTrue(GatePolicy.shouldAnchor("com.instagram.android", own))
        // Whitelisted real app anchors.
        assertTrue(GatePolicy.shouldAnchor("com.ichi2.anki", own, emptySet(), setOf("com.ichi2.anki")))
        // Real survival surfaces anchor even with no whitelist entry.
        assertTrue(GatePolicy.shouldAnchor("com.google.android.deskclock", own))
        assertTrue(GatePolicy.shouldAnchor("com.android.server.telecom", own))
    }

    @Test
    fun `own package anchors like a real surface`() {
        // Settings browsing must move the sticky anchor, or a blind deadline
        // fires for the stale gated app behind it (the Clock shape, self-hosted).
        assertTrue(GatePolicy.shouldAnchor(own, own))
    }

    @Test
    fun `anchor excludes overlays, imes, and empties`() {
        val imes = setOf("com.google.android.inputmethod.latin")
        val seeded = setOf("eu.toneiv.ubktouch", "miui.systemui.plugin")
        assertFalse(GatePolicy.shouldAnchor("eu.toneiv.ubktouch", own, emptySet(), emptySet(), seeded))
        assertFalse(GatePolicy.shouldAnchor("com.android.systemui", own))
        assertFalse(GatePolicy.shouldAnchor("miui.systemui.plugin", own, emptySet(), emptySet(), seeded))
        // User-declared transparent whitelisted app must not read as free use.
        assertFalse(
            GatePolicy.shouldAnchor(
                "com.ichi2.anki",
                own,
                emptySet(),
                setOf("com.ichi2.anki"),
                setOf("com.ichi2.anki"),
            ),
        )
        assertFalse(GatePolicy.shouldAnchor("com.google.android.inputmethod.latin", own, imes))
        assertFalse(GatePolicy.shouldAnchor("", own))
    }

    @Test
    fun `real survival surfaces are used, not transparent`() {
        // deskclock/telecom are never gated but are real fullscreen apps:
        // they must anchor and Skip like any whitelisted app.
        assertFalse(GatePolicy.isTransparent("com.google.android.deskclock", emptySet()))
        assertFalse(GatePolicy.isTransparent("com.android.server.telecom", emptySet()))
        assertFalse(GatePolicy.isTransparent("com.android.incallui", emptySet()))
        assertFalse(GatePolicy.isTransparent("com.android.emergency", emptySet()))
    }

    @Test
    fun `gated apps are gated`() {
        assertTrue(GatePolicy.isGated("com.instagram.android", own))
        assertTrue(GatePolicy.isGated("app.olauncher", own))
        assertTrue(GatePolicy.isGated("com.android.settings", own))
    }

    @Test
    fun `survival list is never gated`() {
        for (pkg in GatePolicy.survivalPackages) {
            assertFalse(pkg, GatePolicy.isGated(pkg, own))
        }
    }

    @Test
    fun `exited overlays gate by default, user set quiets them`() {
        // Item-3 exit: no hardcoded exemption remains; the seeded user set
        // carries them (see device-overlays test above).
        assertTrue(GatePolicy.isGated("eu.toneiv.ubktouch", own))
        assertTrue(GatePolicy.isGated("miui.systemui.plugin", own))
    }

    @Test
    fun `own package is never gated`() {
        assertFalse(GatePolicy.isGated(own, own))
    }

    @Test
    fun `empty package is ignored`() {
        assertFalse(GatePolicy.isGated("", own))
    }

    @Test
    fun `extra ignored packages are never gated`() {
        val imes = setOf("com.google.android.inputmethod.latin", "eu.toneiv.ubktouch")
        for (pkg in imes) {
            assertFalse(pkg, GatePolicy.isGated(pkg, own, imes))
        }
        assertTrue(GatePolicy.isGated("com.instagram.android", own, imes))
    }

    @Test
    fun `dialer is gated until user whitelist lands in M4`() {
        assertTrue(GatePolicy.isGated("com.google.android.dialer", own))
    }

    @Test
    fun `session active only before expiry`() {
        assertTrue(GatePolicy.isSessionActive(1000L, 2000L))
        assertFalse(GatePolicy.isSessionActive(2000L, 2000L))
        assertFalse(GatePolicy.isSessionActive(3000L, 2000L))
        assertFalse(GatePolicy.isSessionActive(1000L, 0L))
    }
}
