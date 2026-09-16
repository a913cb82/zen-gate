package com.abrai.zengate.policy

import org.junit.Assert.assertFalse
import org.junit.Test

/** BUG 2 repro: MIUI plugin surfaces (torch/volume) are gated but unlistable in the picker. */
class PluginSurfaceReproTest {
    private val own = "com.abrai.zengate"

    @Test fun `miui systemui plugin should not gate`() {
        assertFalse(GatePolicy.isGated("miui.systemui.plugin", own))
    }
}
