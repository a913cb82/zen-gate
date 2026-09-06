package com.abrai.zengate.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatePolicyTest {
    private val own = "com.abrai.zengate"

    @Test
    fun `gated apps are gated`() {
        assertTrue(GatePolicy.isGated("com.instagram.android", own))
        assertTrue(GatePolicy.isGated("app.olauncher", own))
        assertTrue(GatePolicy.isGated("com.android.settings", own))
    }

    @Test
    fun `survival list is never gated`() {
        for (pkg in GatePolicy.survivalPackages) {
            assertFalse(pkg, GatePolicy.isGated(pkg, own))
        }
    }

    @Test
    fun `own package is never gated`() {
        assertFalse(GatePolicy.isGated(own, own))
    }

    @Test
    fun `empty package is ignored`() {
        assertFalse(GatePolicy.isGated("", own))
    }

    @Test
    fun `dialer is gated until user whitelist lands in M4`() {
        assertTrue(GatePolicy.isGated("com.google.android.dialer", own))
    }
}
