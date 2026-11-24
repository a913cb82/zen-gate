package com.abrai.zengate

internal enum class Screen { Home, Picker, Overlays, Timers, Setup }

/**
 * Back target for the in-app screens. Null means the activity should finish:
 * Home is the root, so one back always exits instead of looping on itself.
 */
internal fun backTarget(screen: Screen): Screen? =
    when (screen) {
        Screen.Home -> null
        Screen.Overlays -> Screen.Picker
        else -> Screen.Home
    }
