package com.abrai.zengate.ui

import android.content.Intent
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.abrai.zengate.BlockActivity
import com.abrai.zengate.EngineSnapshot
import com.abrai.zengate.GateService
import com.abrai.zengate.GateState
import com.abrai.zengate.GateStore
import com.abrai.zengate.SetupChecks
import com.abrai.zengate.policy.DurationFmt
import com.abrai.zengate.policy.GatePolicy
import com.abrai.zengate.policy.GateStatus
import com.abrai.zengate.policy.PoolEngine
import com.abrai.zengate.policy.ZenConfig
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun homeScreen(
    store: GateStore,
    onPicker: () -> Unit,
    onTimers: () -> Unit,
    onSetup: () -> Unit,
) {
    val context = LocalContext.current
    val pauseUntil by store.pauseUntil.collectAsState(initial = null)
    val snapshot by store.snapshot.collectAsState(initial = null)
    val whitelist by store.whitelist.collectAsState(initial = null)
    val cfg by store.config.collectAsState(initial = null)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // 1s ticker bound to the resumed lifecycle: started on resume, cancelled
    // on pause so Home never ticks in the background. livePool stays derived.
    var ticking by remember { mutableStateOf(true) }
    val lifecycleOwner = context as? ComponentActivity
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> ticking = true
                    Lifecycle.Event.ON_PAUSE -> ticking = false
                    else -> Unit
                }
            }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }
    LaunchedEffect(ticking) {
        while (ticking) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    // Refresh the setup badge every resume, like SetupScreen does.
    var refreshTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) refreshTick++
            }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }
    val missing = remember(refreshTick) { SetupChecks.missingAutoCount(context) }
    val send =
        remember(context) {
            { action: String ->
                runCatching {
                    context.startForegroundService(Intent(context, GateService::class.java).setAction(action))
                }
            }
        }
    // Loading gate: no nav rows until the first config + whitelist emission.
    val pauseUntilV = pauseUntil
    val snapshotV = snapshot
    val whitelistV = whitelist
    val cfgV = cfg
    if (pauseUntilV == null || snapshotV == null || whitelistV == null || cfgV == null) {
        zenScreen {
            Spacer(Modifier.height(10.dp))
            Text("Zen Gate", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
            Spacer(Modifier.height(16.dp))
            Text("Loading…", color = Muted, fontSize = 13.sp)
        }
        return
    }
    val paused = GatePolicy.isPaused(pauseUntilV, now)
    val pauseMin = GatePolicy.pauseMinLeft(GatePolicy.pauseRemainingMs(pauseUntilV, now))
    val sessionMs = PoolEngine.sessionRemainingMs(snapshotV.poolState(), now, cfgV)
    val livePool =
        PoolEngine.displayPoolSec(
            snapshotV.poolSec,
            GateState.drainEnterElapsedMs,
            SystemClock.elapsedRealtime(),
        )
    val status =
        GateStatus.describe(
            enabled = !paused,
            sessionRemainingMs = sessionMs,
            sessionExpiryWallMs = snapshotV.sessionExpiryMs,
            poolSec = livePool,
            usagesToday = snapshotV.usagesToday,
            foregroundPkg = null,
            userWhitelist = emptySet(),
            blockShowing = BlockActivity.isShowing,
            pauseMinLeft = pauseMin,
        )
    val penalty = PoolEngine.penaltySec(snapshotV.poolState(), cfgV)

    zenScreen {
        Spacer(Modifier.height(10.dp))
        Text("Zen Gate", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
        Spacer(Modifier.height(16.dp))

        homeStatusCard(
            status = status,
            now = now,
            cfg = cfgV,
            snapshot = snapshotV,
            livePool = livePool,
            sessionMs = sessionMs,
            pauseMin = pauseMin,
            pauseUntil = pauseUntilV,
            penalty = penalty,
        )

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            statTile(
                modifier = Modifier.weight(1f).height(104.dp),
                value = snapshotV.usagesToday.toString(),
                label = "full opens today",
                sub = "resets midnight",
            )
            actionTile(
                modifier = Modifier.weight(1f).height(104.dp),
                title = if (paused) "Resume" else "Pause",
                sub = if (paused) "gate runs now" else "${cfgV.pauseDurationSec / 60} min break",
                caption = if (paused) "tap to re-enable" else "auto-resumes",
                onClick = {
                    send(if (paused) GateService.ACTION_RESUME else GateService.ACTION_PAUSE)
                },
            )
        }

        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            navRow(
                title = "Whitelist",
                subtitle = if (whitelistV.isEmpty()) "No apps selected" else "${whitelistV.size} apps · tap to edit",
                onClick = onPicker,
            )
            navRow(
                title = "Timers",
                subtitle =
                    DurationFmt.clock(cfgV.sessionAllowSec) + " / " +
                        DurationFmt.clock(cfgV.unlockPoolSec) + " / " +
                        "${cfgV.baseWaitSec}s +${cfgV.waitIncrementSec}s",
                onClick = onTimers,
            )
            navRow(
                title = "Setup",
                subtitle =
                    if (missing >
                        0
                    ) {
                        "$missing item${if (missing == 1) "" else "s"} need attention"
                    } else {
                        "All set"
                    },
                onClick = onSetup,
                trailing =
                    if (missing > 0) {
                        {
                            Box(
                                Modifier
                                    .background(Amber, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 9.dp, vertical = 4.dp),
                            ) {
                                Text("$missing", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AmberInk)
                            }
                        }
                    } else {
                        null
                    },
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun homeStatusCard(
    status: GateStatus.Status,
    now: Long,
    cfg: ZenConfig,
    snapshot: EngineSnapshot,
    livePool: Long,
    sessionMs: Long,
    pauseMin: Long,
    pauseUntil: Long,
    penalty: Long,
) {
    val title: String
    val primary: String?
    val sub: String
    val meta: String
    val metaRight: String?
    val dot: androidx.compose.ui.graphics.Color
    when (status) {
        is GateStatus.Status.Paused -> {
            title = "PAUSED"
            primary = "$pauseMin min"
            sub = "resumes ${clockLabel(pauseUntil)} · no waits, no blocks"
            meta = "Gate off until then"
            metaRight = null
            dot = Amber
        }
        is GateStatus.Status.Session -> {
            title = "OPEN"
            primary = "${DurationFmt.clock(sessionMs / 1_000)} left"
            sub =
                "ends ${clockLabel(
                    status.expiryWallMs,
                )} · full ${DurationFmt.openLabel(cfg.sessionAllowSec).removePrefix("Open for ")}"
            meta = "${snapshot.usagesToday} full opens today"
            metaRight = null
            dot = Mint
        }
        is GateStatus.Status.Whitelisted -> {
            title = "UNLIMITED"
            primary = null
            sub = "this app is whitelisted"
            meta = "${snapshot.usagesToday} full opens today"
            metaRight = null
            dot = Mint
        }
        is GateStatus.Status.FreeTime -> {
            title = "FREE TIME"
            primary = "${livePool}s left"
            sub = "the gate returns when it runs out"
            meta = "${snapshot.usagesToday} full opens today"
            metaRight = null
            dot = Mint
        }
        is GateStatus.Status.Blocking -> {
            title = "BLOCKING NOW"
            primary = null
            sub = "No free time left. Wait to open again."
            meta =
                "Full: ${cfg.baseWaitSec}s + ${cfg.waitIncrementSec}s × ${status.usagesToday} = ${penalty}s · Quick: ${cfg.quickWaitSec}s"
            metaRight = "${status.usagesToday} opens"
            dot = Amber
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = BlockMid,
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(dot, RoundedCornerShape(5.dp)))
                Spacer(Modifier.width(12.dp))
                Text(
                    title,
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (primary != null) {
                Spacer(Modifier.height(14.dp))
                Text(
                    primary,
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                sub,
                color =
                    androidx.compose.ui.graphics.Color.White
                        .copy(alpha = 0.7f),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    meta,
                    color = Amber,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (metaRight != null) {
                    Text(
                        metaRight,
                        color =
                            androidx.compose.ui.graphics.Color.White
                                .copy(alpha = 0.6f),
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun actionTile(
    modifier: Modifier,
    title: String,
    sub: String,
    caption: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Green,
        onClick = onClick,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                color = androidx.compose.ui.graphics.Color.White,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                sub,
                fontSize = 12.sp,
                color =
                    androidx.compose.ui.graphics
                        .Color(0xFFCDE8D6),
            )
            Text(
                caption,
                fontSize = 11.sp,
                color =
                    androidx.compose.ui.graphics
                        .Color(0xFF9FD0B4),
            )
        }
    }
}

private fun clockLabel(wallMs: Long): String =
    try {
        Instant
            .ofEpochMilli(wallMs)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm"))
    } catch (t: Throwable) {
        "--:--"
    }
