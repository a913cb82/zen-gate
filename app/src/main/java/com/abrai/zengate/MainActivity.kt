package com.abrai.zengate

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
                val store = remember { GateStore(this) }
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
                var screen by remember { mutableStateOf(Screen.Home) }
                val keyboard = LocalSoftwareKeyboardController.current
                // Always intercept: reading the live screen state here avoids a
                // stale enabled=false frame after < Home, which made one extra
                // back press appear to "stay" on Home. Hide the search keyboard
                // first so the IME cannot eat a back press of its own.
                BackHandler(enabled = true) {
                    keyboard?.hide()
                    val target = backTarget(screen)
                    if (target == null) finish() else screen = target
                }
                when (screen) {
                    Screen.Picker ->
                        pickerScreen(
                            store,
                            onBack = {
                                keyboard?.hide()
                                screen = Screen.Home
                            },
                            onOverlays = {
                                keyboard?.hide()
                                screen = Screen.Overlays
                            },
                        )
                    Screen.Overlays ->
                        overlayScreen(
                            store,
                            onBack = {
                                keyboard?.hide()
                                screen = Screen.Picker
                            },
                        )
                    Screen.Timers -> timersScreen(store, onBack = { screen = Screen.Home })
                    Screen.Setup ->
                        setupScreen(
                            store,
                            onBack = { screen = Screen.Home },
                            onFix = { id -> fixCheck(id) },
                        )
                    Screen.Home ->
                        homeScreen(
                            store,
                            onPicker = { screen = Screen.Picker },
                            onTimers = { screen = Screen.Timers },
                            onSetup = { screen = Screen.Setup },
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
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
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
