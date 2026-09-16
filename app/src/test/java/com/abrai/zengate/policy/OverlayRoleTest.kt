package com.abrai.zengate.policy

import com.abrai.zengate.policy.OverlayRole.Kind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage-1 transparency rule (pure core): only a positively-identified
 * non-application window kind reads as transparent. Unknown (blind API,
 * unmapped) degrades to current behavior, never to a free pass.
 */
class OverlayRoleTest {
    @Test
    fun `overlay kind is transparent`() {
        assertTrue(OverlayRole.isTransparent(Kind.ACCESSIBILITY_OVERLAY))
        assertTrue(OverlayRole.isTransparent(Kind.SYSTEM))
        assertTrue(OverlayRole.isTransparent(Kind.INPUT_METHOD))
        assertTrue(OverlayRole.isTransparent(Kind.OTHER))
    }

    @Test
    fun `application kind is real use`() {
        assertFalse(OverlayRole.isTransparent(Kind.APPLICATION))
    }

    @Test
    fun `unknown kind degrades to current behavior`() {
        assertFalse(OverlayRole.isTransparent(null))
    }
}
