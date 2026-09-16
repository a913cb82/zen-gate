package com.abrai.zengate

import org.junit.Assert.assertEquals
import org.junit.Test

/** Block-screen Open button label follows the session knob in words. */
class BlockLabelTest {
    @Test
    fun `sub-minute sessions read in seconds`() {
        assertEquals("Open for 30 seconds", BlockActivity.openLabel(30))
        assertEquals("Open for 1 second", BlockActivity.openLabel(1))
    }

    @Test
    fun `exact minutes read in minutes`() {
        assertEquals("Open for 1 minute", BlockActivity.openLabel(60))
        assertEquals("Open for 5 minutes", BlockActivity.openLabel(300))
    }

    @Test
    fun `mixed durations read minutes and seconds`() {
        assertEquals("Open for 1 minute 38 seconds", BlockActivity.openLabel(98))
        assertEquals("Open for 1 minute 1 second", BlockActivity.openLabel(61))
        assertEquals("Open for 2 minutes 5 seconds", BlockActivity.openLabel(125))
    }
}
