package com.abrai.zengate

import org.junit.Assert.assertEquals
import org.junit.Test

/** Boot restart backoff: first shot fast, one bounded backstop, then silent. */
class BootReceiverTest {
    @Test
    fun `restart delays are one fast shot plus one backstop`() {
        assertEquals(listOf(60_000L, 180_000L), BootReceiver.restartDelaysMs())
    }
}
