package com.abrai.zengate.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.abrai.zengate.CheckStatus
import com.abrai.zengate.GateStore
import com.abrai.zengate.SetupCheck
import com.abrai.zengate.SetupChecks
import com.abrai.zengate.policy.DurationFmt
import com.abrai.zengate.policy.ZenConfig

/** Setup and onboarding are the same screen: four things, then done. */
@Composable
fun setupScreen(
    store: GateStore,
    onBack: () -> Unit,
    onFix: (String) -> Unit,
) {
    val context = LocalContext.current
    val cfg by store.config.collectAsState(initial = ZenConfig())
    var refresh by remember { mutableIntStateOf(0) }
    val checks = remember(refresh) { SetupChecks.all(context) }
    val activity = context as? ComponentActivity
    DisposableEffect(activity) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) refresh++
            }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }
    zenScreen {
        backHeader("Setup", onBack)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            checks.forEach { check -> setupRow(check, onFix) }
            Spacer(Modifier.height(6.dp))
            howOpenWorks(cfg)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun setupRow(
    check: SetupCheck,
    onFix: (String) -> Unit,
) {
    zenCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    check.label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    modifier = Modifier.weight(1f),
                )
                when (check.status) {
                    CheckStatus.OK ->
                        Text("Granted", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Green)
                    else -> fixPill("Fix") { onFix(check.id) }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(check.detail, fontSize = 12.sp, color = Muted)
        }
    }
}

@Composable
private fun howOpenWorks(cfg: ZenConfig) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(BlockMid, RoundedCornerShape(18.dp))
                .padding(16.dp),
    ) {
        Column {
            Text("How an open works", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(10.dp))
            Text(
                "Quick — ${cfg.quickWaitSec}s wait, ${DurationFmt.clock(cfg.unlockPoolSec)} free, never counts",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.85f),
            )
            Text(
                "Full — ${cfg.baseWaitSec}s + ${cfg.waitIncrementSec}s per open, " +
                    "${DurationFmt.clock(cfg.sessionAllowSec)} free, +1",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(4.dp))
            Text("Count resets at midnight", fontSize = 12.sp, color = Amber)
        }
    }
}
