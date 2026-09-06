package com.abrai.zengate

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
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
        // Swallow Back: the wait is the only way through (kill switch lives in the shade).
        onBackPressedDispatcher.addCallback(this, AlwaysEnabledCallback())
        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                val waitSec = intent.getIntExtra(EXTRA_WAIT_SEC, WAIT_SEC).coerceAtLeast(1)
                val sessionMs = intent.getLongExtra(EXTRA_SESSION_MS, SESSION_MS).coerceAtLeast(1_000L)
                blockScreen(
                    blockedPkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty(),
                    waitSec = waitSec,
                    onUnlock = { pkg ->
                        scope.launch {
                            GateStore(this@BlockActivity)
                                .setSessionExpiryMs(System.currentTimeMillis() + sessionMs)
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
        // Reuse the running countdown; never stack or reset it.
        setIntent(intent)
    }

    override fun onDestroy() {
        isShowing = false
        super.onDestroy()
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
        const val EXTRA_SESSION_MS = "session_ms"
        const val WAIT_SEC = 30
        const val SESSION_MS = 300_000L
        private const val IGNORE_MS = 3_000L

        @Volatile var isShowing: Boolean = false
    }
}

@Composable
private fun blockScreen(
    blockedPkg: String,
    waitSec: Int,
    onUnlock: (String) -> Unit,
) {
    var remaining by remember { mutableIntStateOf(waitSec) }
    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1_000)
            remaining--
        }
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
            Text("Open for 5 minutes")
        }
    }
}
