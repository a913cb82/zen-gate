package com.abrai.zengate.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationTextTest {
    @Test
    fun `session shows a live countdown and its end time`() {
        assertEquals(
            "Open · 4:12 left · until 21:56",
            NotificationText.session(252_000, "21:56"),
        )
    }

    @Test
    fun `session under a minute still reads m ss`() {
        assertEquals("Open · 0:42 left · until 21:56", NotificationText.session(42_000, "21:56"))
    }

    @Test
    fun `free pool reads seconds`() {
        assertEquals("Free time · 7s left", NotificationText.free(7))
    }

    @Test
    fun `blocking carries the daily open count`() {
        assertEquals("Blocking · 3 opens today", NotificationText.blocking(3))
    }

    @Test
    fun `whitelisted names the app`() {
        assertEquals("Chrome · unlimited", NotificationText.whitelisted("Chrome"))
    }

    @Test
    fun `paused reads minutes`() {
        assertEquals("Paused · 25 min left", NotificationText.paused(25))
    }
}
