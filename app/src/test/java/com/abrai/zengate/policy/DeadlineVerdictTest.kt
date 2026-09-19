package com.abrai.zengate.policy

import com.abrai.zengate.policy.DeadlineVerdict.Outcome.Launch
import com.abrai.zengate.policy.DeadlineVerdict.Outcome.Skip
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Alarm-time launch decision on the LIVE foreground window (no sticky state,
 * so transient overlays and reveal-without-event races cannot poison it).
 * Reproduces the 22:00-22:02 incidents headlessly.
 */
class DeadlineVerdictTest {
    private val own = "com.abrai.zengate"
    private val whitelist = setOf("com.ichi2.anki")
    private val launcher = "app.olauncher"

    @Test
    fun `reveal to home launches for the live launcher window`() {
        // 22:02 incident: Anki closed without emitting an event; the live root
        // is the launcher, so the deadline must launch (was: skipped forever).
        assertEquals(
            Launch(launcher),
            DeadlineVerdict.decide(
                enabled = true,
                sessionRemainingMs = 0,
                rootPkg = launcher,
                lastEventPkg = launcher,
                ownPkg = own,
                userWhitelist = whitelist,
                keyguardLocked = false,
                interactive = true,
            ),
        )
    }

    @Test
    fun `transparent overlay root falls through to the sticky anchor`() {
        // Declared overlay (not survival) over a gated app: decide by the anchor.
        assertEquals(
            Launch("com.instagram.android"),
            DeadlineVerdict.decide(
                enabled = true,
                sessionRemainingMs = 0,
                rootPkg = "com.example.overlay",
                lastEventPkg = "com.instagram.android",
                ownPkg = own,
                userWhitelist = whitelist,
                transparentPkgs = setOf("com.example.overlay"),
                keyguardLocked = false,
                interactive = true,
            ),
        )
    }

    @Test
    fun `transparent overlay over whitelisted app stays quiet`() {
        assertEquals(
            Skip,
            DeadlineVerdict.decide(
                enabled = true,
                sessionRemainingMs = 0,
                rootPkg = "com.example.overlay",
                lastEventPkg = "com.ichi2.anki",
                ownPkg = own,
                userWhitelist = whitelist,
                transparentPkgs = setOf("com.example.overlay"),
                keyguardLocked = false,
                interactive = true,
            ),
        )
    }

    @Test
    fun `static whitelisted app never launches`() {
        // 21:56 incident: live root is Anki -> stay quiet; its exit emits
        // window events and the entry path blocks then.
        assertEquals(
            Skip,
            DeadlineVerdict.decide(
                enabled = true,
                sessionRemainingMs = 0,
                rootPkg = "com.ichi2.anki",
                lastEventPkg = launcher,
                ownPkg = own,
                userWhitelist = whitelist,
                keyguardLocked = false,
                interactive = true,
            ),
        )
    }

    @Test
    fun `own dismiss transition does not poison the decision`() {
        // 22:00 incident: sticky last-foreground was our own dismissing block.
        // The live root decides; own package simply never launches.
        assertEquals(
            Skip,
            DeadlineVerdict.decide(
                enabled = true,
                sessionRemainingMs = 0,
                rootPkg = own,
                lastEventPkg = launcher,
                ownPkg = own,
                userWhitelist = whitelist,
                keyguardLocked = false,
                interactive = true,
            ),
        )
    }

    @Test
    fun `no window with sticky home event launches for the anchor`() {
        assertEquals(
            Launch(launcher),
            DeadlineVerdict.decide(
                enabled = true,
                sessionRemainingMs = 0,
                rootPkg = null,
                lastEventPkg = launcher,
                ownPkg = own,
                userWhitelist = whitelist,
                keyguardLocked = false,
                interactive = true,
            ),
        )
    }

    @Test
    fun `no window with sticky whitelisted event stays quiet`() {
        // Stale-Anki strand guard: the sticky event is whitelisted, so the
        // deadline must not launch; the app exit re-gates via the entry path.
        assertEquals(
            Skip,
            DeadlineVerdict.decide(
                enabled = true,
                sessionRemainingMs = 0,
                rootPkg = null,
                lastEventPkg = "com.ichi2.anki",
                ownPkg = own,
                userWhitelist = whitelist,
                keyguardLocked = false,
                interactive = true,
            ),
        )
    }

    @Test
    fun `no window with sticky own-package event stays quiet`() {
        // Settings browsing anchors self, so a blind deadline stays quiet;
        // the app exit re-gates via the entry path.
        assertEquals(
            Skip,
            DeadlineVerdict.decide(
                enabled = true,
                sessionRemainingMs = 0,
                rootPkg = null,
                lastEventPkg = own,
                ownPkg = own,
                userWhitelist = whitelist,
                keyguardLocked = false,
                interactive = true,
            ),
        )
    }

    @Test
    fun `live clock root with stale launcher anchor stays quiet`() {
        // Whitelisted deskclock is a real app, not an overlay: the live root
        // decides Skip even when the sticky anchor is still the launcher.
        assertEquals(
            Skip,
            DeadlineVerdict.decide(
                enabled = true,
                sessionRemainingMs = 0,
                rootPkg = "com.google.android.deskclock",
                lastEventPkg = launcher,
                ownPkg = own,
                userWhitelist = whitelist + "com.google.android.deskclock",
                keyguardLocked = false,
                interactive = true,
            ),
        )
    }

    @Test
    fun `no window with nothing known stays quiet`() {
        assertEquals(
            Skip,
            DeadlineVerdict.decide(
                enabled = true,
                sessionRemainingMs = 0,
                rootPkg = null,
                lastEventPkg = null,
                ownPkg = own,
                userWhitelist = whitelist,
                keyguardLocked = false,
                interactive = true,
            ),
        )
    }

    @Test
    fun `keyguard and dark screen never launch`() {
        assertEquals(
            Skip,
            DeadlineVerdict.decide(
                enabled = true,
                sessionRemainingMs = 0,
                rootPkg = null,
                lastEventPkg = launcher,
                ownPkg = own,
                userWhitelist = whitelist,
                keyguardLocked = true,
                interactive = true,
            ),
        )
        assertEquals(
            Skip,
            DeadlineVerdict.decide(
                enabled = true,
                sessionRemainingMs = 0,
                rootPkg = launcher,
                lastEventPkg = launcher,
                ownPkg = own,
                userWhitelist = whitelist,
                keyguardLocked = false,
                interactive = false,
            ),
        )
    }

    @Test
    fun `disabled gate and live session never launch`() {
        val live =
            DeadlineVerdict.decide(
                enabled = true,
                sessionRemainingMs = 30_000,
                rootPkg = launcher,
                lastEventPkg = launcher,
                ownPkg = own,
                userWhitelist = whitelist,
                keyguardLocked = false,
                interactive = true,
            )
        assertEquals(Skip, live)
        val off =
            DeadlineVerdict.decide(
                enabled = false,
                sessionRemainingMs = 0,
                rootPkg = launcher,
                lastEventPkg = launcher,
                ownPkg = own,
                userWhitelist = whitelist,
                keyguardLocked = false,
                interactive = true,
            )
        assertEquals(Skip, off)
    }
}
