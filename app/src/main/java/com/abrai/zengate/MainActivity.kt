package com.abrai.zengate

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

/** M1 placeholder. Onboarding checklist + whitelist UI land in M4/M5. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The gate needs foreground presence (ScreenZen-shape): ensure it on every launch.
        try {
            startForegroundService(Intent(this, GateService::class.java))
        } catch (t: Throwable) {
            android.util.Log.e("ZenGate", "foreground presence start failed", t)
        }
        setContent {
            MaterialTheme {
                Text("Zen Gate M1 — enable the accessibility service in Settings.")
            }
        }
    }
}
