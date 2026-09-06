package com.abrai.zengate.policy

import com.abrai.zengate.policy.GateStatus.Status.Blocking
import com.abrai.zengate.policy.GateStatus.Status.FreeTime
import com.abrai.zengate.policy.GateStatus.Status.Paused
import com.abrai.zengate.policy.GateStatus.Status.Session
import com.abrai.zengate.policy.GateStatus.Status.Whitelisted
import org.junit.Assert.assertEquals
import org.junit.Test

/** Headless pin of the notification status mapping. */
class GateStatusTest {
    @Test
    fun `paused beats everything`() {
        assertEquals(
            Paused,
            GateStatus.describe(false, 60_000, 0, 10, 3, "com.beeper.android", emptySet()),
        )
    }

    @Test
    fun `live session reports its deadline`() {
        assertEquals(
            Session(1_000_060_000L),
            GateStatus.describe(true, 60_000, 1_000_060_000L, 0, 3, "com.beeper.android", emptySet()),
        )
    }

    @Test
    fun `whitelisted foreground reports unlimited`() {
        assertEquals(
            Whitelisted("com.ichi2.anki"),
            GateStatus.describe(
                true,
                0,
                0,
                0,
                3,
                "com.ichi2.anki",
                setOf("com.ichi2.anki"),
            ),
        )
    }

    @Test
    fun `pool leftovers report free time`() {
        assertEquals(
            FreeTime(7),
            GateStatus.describe(true, 0, 0, 7, 3, "app.olauncher", emptySet()),
        )
    }

    @Test
    fun `empty pool with no session is blocking`() {
        assertEquals(
            Blocking(3),
            GateStatus.describe(true, 0, 0, 0, 3, "app.olauncher", emptySet()),
        )
    }

    @Test
    fun `block on screen forces blocking even over whitelisted overlay`() {
        assertEquals(
            Blocking(3),
            GateStatus.describe(
                true,
                0,
                0,
                0,
                3,
                "com.ichi2.anki",
                setOf("com.ichi2.anki"),
                blockShowing = true,
            ),
        )
    }

    @Test
    fun `unknown foreground falls back to pool rules`() {
        assertEquals(
            FreeTime(5),
            GateStatus.describe(true, 0, 0, 5, 1, null, emptySet()),
        )
    }
}
