package com.abrai.zengate

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.lifecycle.lifecycleScope
import com.abrai.zengate.policy.GatePolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** M1 placeholder + poll-test harness. Onboarding checklist + whitelist UI land in M4/M5. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Column {
                    Text("Zen Gate M1 — enable the accessibility service in Settings.")
                    Button(onClick = { runPollTest() }) {
                        Text("Start 60s poll test")
                    }
                }
            }
        }
    }

    private fun hasUsagePermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode =
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName,
            )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** TEMP DIAGNOSTIC (M1): poll UsageStatsManager head-to-head with the accessibility feed. */
    private fun runPollTest() {
        if (!hasUsagePermission()) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        lifecycleScope.launch {
            var lastQuery = System.currentTimeMillis() - 2000
            var lastKnown = ""
            repeat(60) {
                val now = System.currentTimeMillis()
                try {
                    val events = usm.queryEvents(lastQuery, now)
                    val event = UsageEvents.Event()
                    while (events.hasNextEvent()) {
                        events.getNextEvent(event)
                        if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                            lastKnown = event.packageName
                        }
                    }
                    lastQuery = now
                } catch (t: Throwable) {
                    Log.e("ZenPoll", "query failed", t)
                }
                Log.d("ZenPoll", "foreground=$lastKnown gated=${GatePolicy.isGated(lastKnown, packageName)}")
                delay(1000)
            }
        }
    }
}
