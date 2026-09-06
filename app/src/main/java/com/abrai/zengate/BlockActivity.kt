package com.abrai.zengate

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * M2: fully opaque block. Fixed 30s wait (M3 makes it escalating), then Open
 * grants a fixed 5-min session and returns to the gated app.
 */
class BlockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isShowing = true
        if (currentPkg.isEmpty()) {
            currentPkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        }
        Log.d(TAG, "block created pkg=$currentPkg")
        // Swallow Back: the wait is the only way through (kill switch lives in the shade).
        onBackPressedDispatcher.addCallback(this, AlwaysEnabledCallback())
        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                val waitSec = intent.getIntExtra(EXTRA_WAIT_SEC, WAIT_SEC).coerceAtLeast(1)
                // resetTick is snapshot state: bumping it recomposes with a fresh countdown.
                // Companion vars alone would not retrigger composition (M2 lesson).
                val tick by resetTick
                val resetKey = currentPkg + "/" + tick
                blockScreen(
                    blockedPkg = currentPkg.ifEmpty { intent.getStringExtra(EXTRA_PACKAGE).orEmpty() },
                    waitSec = waitSec,
                    resetKey = resetKey,
                    onStale = { finish() },
                    onUnlock = { pkg ->
                        scope.launch {
                            val wall = System.currentTimeMillis()
                            val store = GateStore(this@BlockActivity)
                            val cfg = GateState.config
                            val today =
                                com.abrai.zengate.policy.PoolEngine.dayIdFor(
                                    wall,
                                    cfg.resetHour,
                                    cfg.resetMinute,
                                )
                            val base =
                                GateState.poolState().let {
                                    if (com.abrai.zengate.policy.PoolEngine
                                            .needsMidnightReset(it, today)
                                    ) {
                                        com.abrai.zengate.policy.PoolEngine
                                            .midnightReset(today, wall, cfg)
                                    } else {
                                        it
                                    }
                                }
                            val unlocked =
                                com.abrai.zengate.policy.PoolEngine
                                    .unlock(base, wall, cfg)
                            store.savePool(unlocked)
                            GateState.applyPool(unlocked)
                            Log.d(TAG, "unlock pkg=$pkg usages=${unlocked.usagesToday} pool=${unlocked.poolSec}")
                            GateAlarms.scheduleSessionEnd(
                                this@BlockActivity,
                                cfg.sessionAllowSec * 1_000,
                            )
                            GateState.ignorePkg = pkg
                            GateState.ignoreUntilElapsedMs = SystemClock.elapsedRealtime() + IGNORE_MS
                            launchBlocked(pkg)
                            finish()
                        }
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Same package: reuse the running countdown (no strobe, no reset).
        // Different package (launch races): re-gate for the new package.
        val pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        if (pkg.isNotEmpty() && pkg != currentPkg) {
            Log.d(TAG, "re-gate old=$currentPkg new=$pkg")
            currentPkg = pkg
            resetTick.intValue++
        }
        setIntent(intent)
    }

    override fun onDestroy() {
        isShowing = false
        // Fresh instances must take the new intent's package (singleInstance
        // reuse otherwise resurrects a stale anchor: unlock opened the wrong app).
        currentPkg = ""
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        // Visibility (not just existence) drives the notification posture.
        isShowing = false
        // Any exit (home press, screen off, shade tug) invalidates the wait.
        // onResume restarts it fresh: the countdown only ever runs while shown.
        wasPaused = true
    }

    override fun onResume() {
        super.onResume()
        isShowing = true
        if (wasPaused) {
            wasPaused = false
            Log.d(TAG, "resumed after exit; wait reset pkg=$currentPkg")
            resetTick.intValue++
        }
    }

    private fun launchBlocked(pkg: String) {
        val launch =
            packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch != null) {
            startActivity(launch)
        } else {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        }
    }

    private class AlwaysEnabledCallback : androidx.activity.OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = Unit
    }

    companion object {
        const val EXTRA_PACKAGE = "blocked_package"

        // Test hooks: adb can launch the block with short limits without touching product constants.
        const val EXTRA_WAIT_SEC = "wait_sec"
        const val WAIT_SEC = 30
        private const val IGNORE_MS = 3_000L

        @Volatile var isShowing: Boolean = false

        @Volatile var currentPkg: String = ""

        @Volatile private var wasPaused: Boolean = false

        // Snapshot state (not @Volatile): bumping recomposes the countdown.
        val resetTick = mutableIntStateOf(0)

        private const val TAG = "ZenGate"
    }
}

@Composable
private fun blockScreen(
    blockedPkg: String,
    waitSec: Int,
    resetKey: String,
    onStale: () -> Unit,
    onUnlock: (String) -> Unit,
) {
    var remaining by remember(resetKey) { mutableIntStateOf(waitSec) }
    // Stale-block watcher: runs for the whole composition (the countdown above
    // exits once the wait completes, but a later unlock must still dismiss).
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            if (GateState.poolSec > 0) {
                Log.d("ZenGate", "grace granted under block; dismissing")
                onStale()
                return@LaunchedEffect
            }
        }
    }
    LaunchedEffect(resetKey) {
        while (remaining > 0) {
            delay(1_000)
            remaining--
        }
        Log.d("ZenGate", "wait complete pkg=$blockedPkg")
    }
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("ZEN GATE", color = Color.White, fontSize = 28.sp)
        Spacer(Modifier.height(16.dp))
        Text(blockedPkg, color = Color.Gray, fontSize = 14.sp)
        Spacer(Modifier.height(32.dp))
        Text("$remaining", color = Color.White, fontSize = 72.sp)
        Spacer(Modifier.height(32.dp))
        Button(onClick = { onUnlock(blockedPkg) }, enabled = remaining == 0) {
            Text(
                "Open for ${GateState.config.sessionAllowSec / 60} minutes",
            )
        }
    }
}
