package com.abrai.zengate

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.abrai.zengate.policy.BlockUiFactory
import com.abrai.zengate.policy.DurationFmt
import com.abrai.zengate.ui.BlockBottom
import com.abrai.zengate.ui.BlockMid
import com.abrai.zengate.ui.BlockTop
import com.abrai.zengate.ui.Mint
import com.abrai.zengate.ui.OnMint
import com.abrai.zengate.ui.zenTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fully opaque block. The countdown is the long/primary wait; the quick pool
 * button can unlock earlier without moving the big timer. Leaving the screen
 * restarts the wait (it only ever runs while visible).
 */
class BlockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        isShowing = true
        if (currentPkg.isEmpty()) {
            currentPkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        }
        Log.d(TAG, "block created pkg=$currentPkg")
        onBackPressedDispatcher.addCallback(this, AlwaysEnabledCallback())
        val activityScope = lifecycleScope
        setContent {
            zenTheme {
                val waitSec = intent.getIntExtra(EXTRA_WAIT_SEC, WAIT_SEC).coerceAtLeast(1)
                val tick by resetTick
                val resetKey = "$currentPkg/$tick/$waitSec"
                val cfg = GateState.config
                val pkg = currentPkg.ifEmpty { intent.getStringExtra(EXTRA_PACKAGE).orEmpty() }
                val pkgFallback = pkg.substringAfterLast('.').ifEmpty { "Zen Gate" }
                val label by produceState(initialValue = pkgFallback, pkg) {
                    value = withContext(Dispatchers.IO) { appLabel(pkg) }
                }
                blockScreen(
                    appLabel = label,
                    waitSec = waitSec,
                    quickWaitSec = cfg.quickWaitSec.toInt().coerceAtLeast(0),
                    sessionAllowSec = cfg.sessionAllowSec,
                    unlockPoolSec = cfg.unlockPoolSec,
                    usagesToday = GateState.usagesToday,
                    resetKey = resetKey,
                    onStale = { finish() },
                    onQuick = {
                        activityScope.launch {
                            val wall = System.currentTimeMillis()
                            val store = GateStore(this@BlockActivity)
                            val cfgNow = GateState.config
                            val granted =
                                com.abrai.zengate.policy.PoolEngine
                                    .phoneUnlock(rolledBase(wall, cfgNow), wall, cfgNow)
                            store.savePool(granted)
                            GateState.applyPool(granted)
                            GateState.drainEnterElapsedMs = SystemClock.elapsedRealtime()
                            GateAlarms.schedulePoolExpiry(this@BlockActivity, granted.poolSec * 1_000)
                            Log.d(
                                TAG,
                                "quick unlock pkg=$pkg usages=${granted.usagesToday} pool=${granted.poolSec}",
                            )
                            GateState.ignorePkg = pkg
                            GateState.ignoreUntilElapsedMs = SystemClock.elapsedRealtime() + IGNORE_MS
                            launchBlocked(pkg)
                            finish()
                        }
                    },
                    onUnlock = {
                        activityScope.launch {
                            val wall = System.currentTimeMillis()
                            val store = GateStore(this@BlockActivity)
                            val cfgNow = GateState.config
                            val unlocked =
                                com.abrai.zengate.policy.PoolEngine
                                    .unlock(rolledBase(wall, cfgNow), wall, cfgNow)
                            store.savePool(unlocked)
                            GateState.applyPool(unlocked)
                            Log.d(
                                TAG,
                                "unlock pkg=$pkg usages=${unlocked.usagesToday} pool=${unlocked.poolSec}",
                            )
                            GateAlarms.scheduleSessionEnd(
                                this@BlockActivity,
                                cfgNow.sessionAllowSec * 1_000,
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
        wasPaused = false
        // Fresh instances must take the new intent's package (singleInstance
        // reuse otherwise resurrects a stale anchor: unlock opened the wrong app).
        currentPkg = ""
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        isShowing = false
        // Any exit (home press, screen off, shade tug) invalidates the wait.
        // onResume restarts it fresh: the countdown only ever runs while shown.
        // Skip when finishing: the unlock path finishes deliberately and must
        // not bump the tick on the way out.
        if (!isFinishing) wasPaused = true
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

    /** Pool with midnight rollover applied; shared by both unlock taps. */
    private fun rolledBase(
        wall: Long,
        cfg: com.abrai.zengate.policy.ZenConfig,
    ): com.abrai.zengate.policy.PoolState {
        val base = GateState.poolState()
        val today =
            com.abrai.zengate.policy.PoolEngine
                .dayIdFor(wall, cfg.resetHour, cfg.resetMinute)
        return if (com.abrai.zengate.policy.PoolEngine
                .needsMidnightReset(base, today)
        ) {
            com.abrai.zengate.policy.PoolEngine
                .midnightReset(today, cfg)
        } else {
            base
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

    private fun appLabel(pkg: String): String {
        if (pkg.isEmpty()) return "Zen Gate"
        return runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))
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

        /** Open-button label in compact form ("Open for 5 min"). Pure. */
        fun openLabel(sessionAllowSec: Long): String = DurationFmt.openLabel(sessionAllowSec)

        private const val TAG = "ZenGate"
    }
}

@Composable
private fun blockScreen(
    appLabel: String,
    waitSec: Int,
    quickWaitSec: Int,
    sessionAllowSec: Long,
    unlockPoolSec: Long,
    usagesToday: Int,
    resetKey: String,
    onStale: () -> Unit,
    onQuick: () -> Unit,
    onUnlock: () -> Unit,
) {
    // Survives rotation: elapsed seconds are saved, keyed on resetKey which
    // includes waitSec so a new wait never reuses a stale value.
    var elapsed by rememberSaveable(resetKey) { mutableIntStateOf(0) }
    // Guard against double-tap: disable both cards after the first unlock tap.
    var unlocking by remember(resetKey) { mutableStateOf(false) }
    val maxWait = maxOf(waitSec, quickWaitSec)
    // Stale-block watcher: runs for the whole composition. A later unlock or a
    // pause must still dismiss, even after the countdown has finished.
    // Pre-check before the first delay so an already-stale block goes now.
    LaunchedEffect(Unit) {
        val dismissGen = GateState.blockDismissGen

        fun shouldDismiss(): Boolean = GateState.blockDismissGen != dismissGen || GateState.poolSec > 0
        if (GateState.blockDismissGen != dismissGen) {
            Log.d("ZenGate", "pause requested under block; dismissing")
            onStale()
            return@LaunchedEffect
        }
        if (GateState.poolSec > 0) {
            Log.d("ZenGate", "grace granted under block; dismissing")
            onStale()
            return@LaunchedEffect
        }
        while (true) {
            delay(500)
            if (!shouldDismiss()) continue
            if (GateState.blockDismissGen != dismissGen) {
                Log.d("ZenGate", "pause requested under block; dismissing")
            } else {
                Log.d("ZenGate", "grace granted under block; dismissing")
            }
            onStale()
            return@LaunchedEffect
        }
    }
    // Deadline-anchored countdown: drift-free, rotation-safe via saved elapsed.
    LaunchedEffect(resetKey) {
        val deadline = SystemClock.elapsedRealtime() + maxWait * 1_000L
        while (true) {
            val remainingMs = deadline - SystemClock.elapsedRealtime()
            elapsed = ((maxWait * 1_000L - remainingMs) / 1_000L).toInt().coerceIn(0, maxWait)
            if (remainingMs <= 0) break
            delay(250)
        }
        Log.d("ZenGate", "wait complete")
    }
    val ui =
        BlockUiFactory.from(
            waitSec = waitSec.toLong(),
            quickWaitSec = quickWaitSec.toLong(),
            elapsedSec = elapsed.toLong(),
            usagesToday = usagesToday,
            sessionAllowSec = sessionAllowSec,
            unlockPoolSec = unlockPoolSec,
        )
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(listOf(BlockTop, BlockMid, BlockBottom)),
                ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                appLabel,
                fontSize = 13.sp,
                letterSpacing = 1.5.sp,
                color = Color.White.copy(alpha = 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(40.dp))
            Text(
                DurationFmt.clock(ui.fullRemainingSec),
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Spacer(Modifier.height(18.dp))
            waitBar(maxWait, resetKey)
            Spacer(Modifier.height(52.dp))
            if (ui.showQuick) {
                openCard(
                    title = ui.quickLabel,
                    right = DurationFmt.waitValue(ui.quickRemainingSec),
                    ready = ui.quickReady && !unlocking,
                    primary = false,
                    onClick = {
                        if (!unlocking) {
                            unlocking = true
                            onQuick()
                        }
                    },
                )
                Spacer(Modifier.height(14.dp))
            }
            openCard(
                title = ui.fullLabel,
                right = DurationFmt.waitValue(ui.fullRemainingSec),
                ready = ui.fullReady && !unlocking,
                primary = true,
                onClick = {
                    if (!unlocking) {
                        unlocking = true
                        onUnlock()
                    }
                },
            )
            Spacer(Modifier.height(44.dp))
            Text(
                "${ui.usagesToday} full opens today",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.4f),
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun waitBar(
    waitSec: Int,
    resetKey: String,
) {
    val progress = remember(resetKey) { Animatable(0f) }
    LaunchedEffect(resetKey) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec =
                tween(
                    durationMillis = (waitSec * 1000).coerceAtLeast(1),
                    easing = LinearEasing,
                ),
        )
    }
    Box(
        modifier =
            Modifier
                .width(240.dp)
                .height(6.dp)
                .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(3.dp)),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(progress.value.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(
                        Brush.linearGradient(listOf(com.abrai.zengate.ui.Amber, Mint)),
                        RoundedCornerShape(3.dp),
                    ),
        )
    }
}

@Composable
private fun openCard(
    title: String,
    right: String,
    ready: Boolean,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val fill =
        when {
            ready && primary -> Mint
            ready -> Color.Transparent
            else -> Color.White.copy(alpha = 0.06f)
        }
    val border =
        when {
            ready && !primary -> BorderStroke(2.dp, Mint)
            ready -> null
            else -> BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
        }
    val titleColor =
        when {
            ready && primary -> OnMint
            ready -> Color.White
            else -> Color.White.copy(alpha = 0.55f)
        }
    val rightColor =
        when {
            ready && primary -> OnMint
            ready -> Mint
            else -> Color.White.copy(alpha = 0.5f)
        }
    // Locked cards announce when they become available, e.g. "Open available in 12s".
    val description = if (ready) title else "$title available in $right"
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(76.dp)
                .clip(shape)
                .clickable(enabled = ready, role = Role.Button, onClick = onClick)
                .semantics {
                    contentDescription = description
                    if (!ready) disabled()
                },
        shape = shape,
        color = fill,
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = titleColor)
            Text(right, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = rightColor)
        }
    }
}
