package com.abrai.zengate.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockResetTest {
    @Test
    fun `brief exit keeps the wait`() {
        assertFalse(BlockReset.shouldReset(0))
        assertFalse(BlockReset.shouldReset(500))
        assertFalse(BlockReset.shouldReset(BlockReset.GRACE_MS))
    }

    @Test
    fun `real walk-away restarts the wait`() {
        assertTrue(BlockReset.shouldReset(BlockReset.GRACE_MS + 1))
        assertTrue(BlockReset.shouldReset(30_000))
    }
}
