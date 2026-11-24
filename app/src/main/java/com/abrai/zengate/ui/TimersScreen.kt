package com.abrai.zengate.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abrai.zengate.GateStore
import com.abrai.zengate.GateStoreKeys
import com.abrai.zengate.policy.DurationFmt
import com.abrai.zengate.policy.ZenConfig
import kotlinx.coroutines.launch

private data class KnobEdit(
    val title: String,
    val sub: String,
    val unit: String,
    val initial: Long,
    val min: Long,
    val max: Long,
    val commit: (Long) -> Unit,
)

@Composable
fun timersScreen(
    store: GateStore,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val cfg by store.config.collectAsState(initial = ZenConfig())
    var edit by remember { mutableStateOf<KnobEdit?>(null) }

    fun save(
        key: androidx.datastore.preferences.core.Preferences.Key<Long>,
        value: Long,
    ) = scope.launch { store.setKnob(key, value) }

    zenScreen {
        backHeader("Timers", onBack)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            settingRow(
                title = "Long session",
                sub = "full open length",
                value = DurationFmt.clock(cfg.sessionAllowSec),
            ) {
                edit =
                    KnobEdit(
                        "Long session",
                        "Full open length in seconds",
                        "seconds",
                        cfg.sessionAllowSec,
                        30,
                        3600,
                    ) { save(GateStoreKeys.K_SESSION_ALLOW, it) }
            }
            settingRow(
                title = "Long base wait",
                sub = "floor of the full wait",
                value = "${cfg.baseWaitSec}s",
            ) {
                edit =
                    KnobEdit("Long base wait", "Floor of the full wait", "seconds", cfg.baseWaitSec, 1, 300) {
                        save(GateStoreKeys.K_BASE_WAIT, it)
                    }
            }
            settingRow(
                title = "Per-open increment",
                sub = "added per full open today",
                value = "+${cfg.waitIncrementSec}s",
            ) {
                edit =
                    KnobEdit(
                        "Per-open increment",
                        "Added per full open today",
                        "seconds",
                        cfg.waitIncrementSec,
                        0,
                        60,
                    ) { save(GateStoreKeys.K_WAIT_INC, it) }
            }
            settingRow(
                title = "Free pool",
                sub = "quick open length",
                value = DurationFmt.clock(cfg.unlockPoolSec),
            ) {
                edit =
                    KnobEdit("Free pool", "Quick open length", "seconds", cfg.unlockPoolSec, 1, 3600) {
                        save(GateStoreKeys.K_UNLOCK_POOL, it)
                    }
            }
            settingRow(
                title = "Short wait",
                sub = "time before quick open",
                value = if (cfg.quickWaitSec == 0L) "off" else "${cfg.quickWaitSec}s",
            ) {
                edit =
                    KnobEdit("Short wait", "Time before quick open", "seconds", cfg.quickWaitSec, 0, 300) {
                        save(GateStoreKeys.K_QUICK_WAIT, it)
                    }
            }
            zenCard(border = Border) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Quick open", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Spacer(Modifier.height(2.dp))
                        Text("short unlock · never adds to the count", fontSize = 12.sp, color = Muted)
                    }
                    Switch(
                        checked = cfg.quickWaitSec > 0,
                        onCheckedChange = { on ->
                            save(GateStoreKeys.K_QUICK_WAIT, if (on) 5L else 0L)
                        },
                        colors =
                            SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Green,
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = Quiet,
                                uncheckedBorderColor = Quiet,
                            ),
                    )
                }
            }
            // Pause knob is in minutes; the display below keeps the remainder
            // note so a non-round second value is never hidden.
            val pauseMin = cfg.pauseDurationSec / 60
            val pauseRem = cfg.pauseDurationSec % 60
            settingRow(
                title = "Pause length",
                sub = "kill switch · auto-resumes",
                value = if (pauseRem == 0L) "${pauseMin}m" else "${pauseMin}m ${pauseRem}s",
            ) {
                edit =
                    KnobEdit(
                        "Pause length",
                        "How long the kill switch stays on",
                        "minutes",
                        pauseMin,
                        1,
                        480,
                    ) { save(GateStoreKeys.K_PAUSE_SEC, it * 60) }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFFFFF7E6),
                border = BorderStroke(1.dp, Amber),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Rescue (adb break-glass)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "adb shell pm disable-user --user 0 com.abrai.zengate",
                        fontSize = 11.sp,
                        color = AmberInk,
                    )
                }
            }
            Text(
                "full wait = ${cfg.baseWaitSec}s + ${cfg.waitIncrementSec}s × full opens today",
                fontSize = 11.sp,
                color = Subtle,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
            )
        }
    }

    val open = edit
    if (open != null) {
        numberDialog(
            edit = open,
            onDismiss = { edit = null },
            onCommit = {
                open.commit(it)
                edit = null
            },
        )
    }
}

@Composable
private fun settingRow(
    title: String,
    sub: String,
    value: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = Card,
        border = BorderStroke(1.dp, Border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(2.dp))
                Text(sub, fontSize = 12.sp, color = Muted)
            }
            Spacer(Modifier.width(10.dp))
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Green)
        }
    }
}

@Composable
private fun numberDialog(
    edit: KnobEdit,
    onDismiss: () -> Unit,
    onCommit: (Long) -> Unit,
) {
    var text by remember(edit) { mutableStateOf(edit.initial.toString()) }
    val value = text.toLongOrNull()
    // Enforce the full min..max range; out-of-range commits are disabled.
    val valid = value != null && value >= edit.min && value <= edit.max
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        title = { Text(edit.title, color = Ink) },
        text = {
            Column {
                Text(edit.sub, fontSize = 12.sp, color = Muted)
                Text(
                    "Allowed: ${edit.min}..${edit.max} ${edit.unit}",
                    fontSize = 11.sp,
                    color = Muted,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    suffix = { Text(edit.unit, color = Muted) },
                    isError = !valid,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = value
                if (v != null && v >= edit.min && v <= edit.max) onCommit(v)
            }, enabled = valid) {
                Text("Save", color = if (valid) Green else Subtle, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) }
        },
    )
}
