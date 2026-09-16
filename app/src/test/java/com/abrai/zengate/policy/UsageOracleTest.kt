package com.abrai.zengate.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Headless pin of the usage-events foreground fold (Android query is a thin wrapper). */
class UsageOracleTest {
    @Test
    fun `oracle window is short by default, pool-scaled above that`() {
        // Small floor: stale roots must age out fast so the sticky anchor
        // (whitelist-aware) decides instead. A bigger live pool widens it.
        assertEquals(8_000, UsageOracle.windowMs(poolSec = 0))
        assertEquals(8_000, UsageOracle.windowMs(poolSec = 3))
        assertEquals(25_000, UsageOracle.windowMs(poolSec = 20))
        assertEquals(65_000, UsageOracle.windowMs(poolSec = 60))
    }

    @Test
    fun `static whitelisted app stays foreground`() {
        assertEquals(
            "com.ichi2.anki",
            UsageOracle.pickForeground(listOf(UsageOracle.fg("com.ichi2.anki"))),
        )
    }

    @Test
    fun `reveal to home reports home`() {
        assertEquals(
            "app.olauncher",
            UsageOracle.pickForeground(
                listOf(
                    UsageOracle.fg("com.ichi2.anki"),
                    UsageOracle.bg("com.ichi2.anki"),
                    UsageOracle.fg("app.olauncher"),
                ),
            ),
        )
    }

    @Test
    fun `background for another app is ignored`() {
        assertEquals(
            "com.beeper.android",
            UsageOracle.pickForeground(
                listOf(
                    UsageOracle.fg("com.beeper.android"),
                    UsageOracle.bg("com.ichi2.anki"),
                ),
            ),
        )
    }

    @Test
    fun `empty window reports unknown`() {
        assertNull(UsageOracle.pickForeground(emptyList()))
    }

    @Test
    fun `matching background clears`() {
        assertNull(
            UsageOracle.pickForeground(
                listOf(
                    UsageOracle.fg("com.ichi2.anki"),
                    UsageOracle.bg("com.ichi2.anki"),
                ),
            ),
        )
    }
}
