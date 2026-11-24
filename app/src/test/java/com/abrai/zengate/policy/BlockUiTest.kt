package com.abrai.zengate.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Block-screen state mapping. The big countdown is the LONG (primary) wait;
 * the quick pool button can unlock earlier without moving it.
 */
class BlockUiTest {
    private fun ui(
        elapsed: Long,
        waitSec: Long = 18,
        quickWaitSec: Long = 5,
    ) = BlockUiFactory.from(
        waitSec = waitSec,
        quickWaitSec = quickWaitSec,
        elapsedSec = elapsed,
        usagesToday = 3,
        sessionAllowSec = 300,
        unlockPoolSec = 60,
    )

    @Test
    fun `big timer tracks the long wait at the start`() {
        val u = ui(elapsed = 2)
        assertEquals(16, u.fullRemainingSec)
        assertEquals(3, u.quickRemainingSec)
        assertFalse(u.fullReady)
        assertFalse(u.quickReady)
        assertEquals(2f / 18f, u.progress, 0.0001f)
    }

    @Test
    fun `quick unlocks while the long wait still runs`() {
        val u = ui(elapsed = 6)
        assertEquals(12, u.fullRemainingSec)
        assertEquals(0, u.quickRemainingSec)
        assertTrue(u.quickReady)
        assertFalse(u.fullReady)
    }

    @Test
    fun `both ready at zero`() {
        val u = ui(elapsed = 18)
        assertEquals(0, u.fullRemainingSec)
        assertEquals(0, u.quickRemainingSec)
        assertTrue(u.fullReady)
        assertTrue(u.quickReady)
        assertEquals(1f, u.progress, 0.0001f)
    }

    @Test
    fun `elapsed clamps past the wait`() {
        val u = ui(elapsed = 99)
        assertEquals(0, u.fullRemainingSec)
        assertEquals(0, u.quickRemainingSec)
        assertTrue(u.fullReady)
    }

    @Test
    fun `quick is hidden when its wait is zero`() {
        val u = ui(elapsed = 1, quickWaitSec = 0)
        assertFalse(u.showQuick)
    }

    @Test
    fun `labels follow the configured durations`() {
        val u = ui(elapsed = 0)
        assertEquals("Open for 1 min", u.quickLabel)
        assertEquals("Open for 5 min", u.fullLabel)
    }

    @Test
    fun `full wait floors at one second`() {
        val u = ui(elapsed = 0, waitSec = 0)
        assertEquals(1, u.fullRemainingSec)
    }
}
