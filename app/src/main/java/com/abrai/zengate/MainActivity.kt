package com.abrai.zengate

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/** M2: gate status + kill switch. Whitelist UI lands in M4. */
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
                val store = remember { GateStore(this) }
                val scope = rememberCoroutineScope()
                val enabled by store.enabled.collectAsState(initial = true)
                Column {
                    Text(if (enabled) "Zen Gate: ON" else "Zen Gate: PAUSED")
                    Button(onClick = { scope.launch { store.setEnabled(!enabled) } }) {
                        Text(if (enabled) "Disable gate" else "Enable gate")
                    }
                }
            }
        }
    }
}
