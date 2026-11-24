package com.abrai.zengate.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Fixed palette from the mock: warm paper app, deep navy block.
val Paper = Color(0xFFF5F2EB)
val Ink = Color(0xFF161A22)
val Card = Color(0xFFFFFFFF)
val Border = Color(0xFFE5E0D3)
val Green = Color(0xFF1A6B4A)
val Mint = Color(0xFF7FD1A8)
val Amber = Color(0xFFE8B44A)
val AmberInk = Color(0xFF4A3700)
val Muted = Color(0xFF6B7280)
val Subtle = Color(0xFF999999)
val Soft = Color(0xFFF0EDE4)
val Quiet = Color(0xFFE7E2D5)
val QuickBg = Color(0xFFDCEFE4)

// Block screen
val BlockTop = Color(0xFF16213E)
val BlockMid = Color(0xFF0C1222)
val BlockBottom = Color(0xFF0A1A1A)
val OnMint = Color(0xFF0A1A12)

private val ZenColors =
    lightColorScheme(
        primary = Green,
        onPrimary = Color.White,
        secondary = Mint,
        onSecondary = OnMint,
        tertiary = Amber,
        onTertiary = AmberInk,
        background = Paper,
        onBackground = Ink,
        surface = Card,
        onSurface = Ink,
        surfaceVariant = Soft,
        onSurfaceVariant = Muted,
        outline = Border,
        error = Color(0xFFB3261E),
        onError = Color.White,
    )

/** The app deliberately ignores system dark mode: paper + ink are the identity. */
@Composable
fun zenTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ZenColors, content = content)
}
