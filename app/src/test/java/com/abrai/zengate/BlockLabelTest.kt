package com.abrai.zengate

import org.junit.Assert.assertEquals
import org.junit.Test

/** Block-screen Open button labels follow the knobs in compact form. */
class BlockLabelTest {
    @Test
    fun `whole minutes read in minutes`() {
        assertEquals("Open for 1 min", BlockActivity.openLabel(60))
        assertEquals("Open for 5 min", BlockActivity.openLabel(300))
    }

    @Test
    fun `sub-minute grants read in seconds`() {
        assertEquals("Open for 15 s", BlockActivity.openLabel(15))
        assertEquals("Open for 1 s", BlockActivity.openLabel(1))
    }

    @Test
    fun `mixed durations keep both parts`() {
        assertEquals("Open for 1 min 38 s", BlockActivity.openLabel(98))
        assertEquals("Open for 2 min 5 s", BlockActivity.openLabel(125))
    }
}
