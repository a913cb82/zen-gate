package com.abrai.zengate.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class AppOrderTest {
    private val apps =
        listOf(
            AppRow("Chrome", "com.android.chrome"),
            AppRow("anki", "com.ichi2.anki"),
            AppRow("Camera", "com.android.camera"),
            AppRow("Zen Gate", "com.abrai.zengate"),
        )
    private val allowed = setOf("com.ichi2.anki", "com.android.camera", "com.abrai.zengate")

    @Test
    fun `allowed apps come first, each group alphabetical`() {
        assertEquals(
            listOf("anki", "Camera", "Zen Gate", "Chrome"),
            AppOrder.order(apps, allowed, "").map { it.label },
        )
    }

    @Test
    fun `search matches label or package, case-insensitively`() {
        assertEquals(
            listOf("Chrome"),
            AppOrder.order(apps, allowed, "CHRO").map { it.label },
        )
        assertEquals(
            listOf("anki"),
            AppOrder.order(apps, allowed, "ichi2").map { it.label },
        )
    }

    @Test
    fun `own package is an ordinary row`() {
        assertEquals(
            listOf("Zen Gate"),
            AppOrder.order(apps, emptySet(), "zengate").map { it.label },
        )
    }
}
