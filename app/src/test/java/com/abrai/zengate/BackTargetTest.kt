package com.abrai.zengate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackTargetTest {
    @Test
    fun `back from home finishes`() {
        assertNull(backTarget(Screen.Home))
    }

    @Test
    fun `back from overlays returns to whitelist`() {
        assertEquals(Screen.Picker, backTarget(Screen.Overlays))
    }

    @Test
    fun `back from sub screens returns home`() {
        assertEquals(Screen.Home, backTarget(Screen.Picker))
        assertEquals(Screen.Home, backTarget(Screen.Timers))
        assertEquals(Screen.Home, backTarget(Screen.Setup))
    }
}
