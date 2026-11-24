package com.abrai.zengate

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.abrai.zengate.ui.homeScreen
import com.abrai.zengate.ui.overlayScreen
import com.abrai.zengate.ui.pickerScreen
import com.abrai.zengate.ui.setupScreen
import com.abrai.zengate.ui.timersScreen
import com.abrai.zengate.ui.zenTheme

/** Home, whitelist picker, timers, setup. All persisted in DataStore, effective immediately. */
class MainActivity : ComponentActivity() {
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) openNotificationSettings()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle =
                SystemBarStyle.light(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                ),
            navigationBarStyle =
                SystemBarStyle.light(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                ),
        )
        // The gate needs foreground presence (ScreenZen-shape): ensure it on every launch.
        try {
            startForegroundService(Intent(this, GateService::class.java))
        } catch (t: Throwable) {
            android.util.Log.e("ZenGate", "foreground presence start failed", t)
        }
        setContent {
            zenTheme {
                val appCtx = applicationContext
                val store = remember { GateStore(appCtx) }
                LaunchedEffect(Unit) {
                    store.seedDefaults(
                        setOf(
                            "com.google.android.dialer",
                            "com.google.android.apps.messaging",
                            "com.abrai.zengate",
                        ),
                        version = 2,
                    )
                    store.seedTransparent(
                        SeedTransparentPackages,
                        version = SEED_TRANSPARENT_VERSION_CURRENT,
                    )
                }
                // Ordinal saver: Screen survives rotation/recreation via its ordinal.
                var screenOrdinal by rememberSaveable { mutableIntStateOf(Screen.Home.ordinal) }
                val screen: Screen =
                    Screen.entries[screenOrdinal.coerceIn(0, Screen.entries.size - 1)]

                fun go(target: Screen) {
                    screenOrdinal = target.ordinal
                }
                val keyboard = LocalSoftwareKeyboardController.current
                // Per-screen handler only: on Home there is no handler so the
                // system back finishes the activity (global fallback).
                // Search fields handle IME Done separately; hiding here covers
                // the IME-visible back press before navigating.
                BackHandler(enabled = screen != Screen.Home) {
                    keyboard?.hide()
                    val target = backTarget(screen)
                    if (target != null) go(target)
                }
                when (screen) {
                    Screen.Picker ->
                        pickerScreen(
                            store,
                            onBack = {
                                keyboard?.hide()
                                go(Screen.Home)
                            },
                            onOverlays = {
                                keyboard?.hide()
                                go(Screen.Overlays)
                            },
                        )
                    Screen.Overlays ->
                        overlayScreen(
                            store,
                            onBack = {
                                keyboard?.hide()
                                go(Screen.Picker)
                            },
                        )
                    Screen.Timers -> timersScreen(store, onBack = { go(Screen.Home) })
                    Screen.Setup ->
                        setupScreen(
                            store,
                            onBack = { go(Screen.Home) },
                            onFix = { id -> fixCheck(id) },
                        )
                    Screen.Home ->
                        homeScreen(
                            store,
                            onPicker = { go(Screen.Picker) },
                            onTimers = { go(Screen.Timers) },
                            onSetup = { go(Screen.Setup) },
                        )
                }
            }
        }
    }

    private fun fixCheck(id: String) {
        when (id) {
            SetupChecks.ID_NOTIFICATIONS -> {
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    openNotificationSettings()
                } else {
                    notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            SetupChecks.ID_BATTERY ->
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                }.onFailure {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }.onFailure { t -> Log.e("ZenGate", "battery settings unavailable", t) }
                }
            SetupChecks.ID_A11Y -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            SetupChecks.ID_USAGE -> startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    private fun openNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        )
    }
}
