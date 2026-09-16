package com.abrai.zengate

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.abrai.zengate.policy.ZenConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** M4: home + whitelist picker + knobs. All persisted in DataStore, effective immediately. */
class MainActivity : ComponentActivity() {
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The gate needs foreground presence (ScreenZen-shape): ensure it on every launch.
        try {
            startForegroundService(Intent(this, GateService::class.java))
        } catch (t: Throwable) {
            android.util.Log.e("ZenGate", "foreground presence start failed", t)
        }
        setContent {
            MaterialTheme {
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
                }
                var screen by remember { mutableStateOf("home") }
                zenTheme {
                    when (screen) {
                        "picker" -> pickerScreen(store, onBack = { screen = "home" })
                        "knobs" -> knobsScreen(store, onBack = { screen = "home" })
                        "setup" ->
                            setupScreen(
                                store,
                                onBack = { screen = "home" },
                                onFix = { id -> fixCheck(id) },
                            )
                        else ->
                            homeScreen(
                                store,
                                onPicker = { screen = "picker" },
                                onKnobs = { screen = "knobs" },
                                onSetup = { screen = "setup" },
                            )
                    }
                }
            }
        }
    }

    private fun fixCheck(id: String) {
        when (id) {
            SetupChecks.ID_A11Y -> startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            SetupChecks.ID_USAGE -> startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
            SetupChecks.ID_BATTERY ->
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:$packageName"),
                    ),
                )
            SetupChecks.ID_EXACT ->
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        android.net.Uri.parse("package:$packageName"),
                    ),
                )
            SetupChecks.ID_NOTIFICATIONS ->
                notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            else ->
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:$packageName"),
                    ),
                )
        }
    }
}

@Composable
private fun homeScreen(
    store: GateStore,
    onPicker: () -> Unit,
    onKnobs: () -> Unit,
    onSetup: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val enabled by store.enabled.collectAsState(initial = true)
    val whitelist by store.whitelist.collectAsState(initial = emptySet())
    val context = store.appContext
    val missing = remember { mutableStateOf(-1) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        missing.value = SetupChecks.missingAutoCount(context)
    }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (enabled) "Zen Gate: ON" else "Zen Gate: PAUSED")
        Text("${whitelist.size} apps whitelisted")
        if (missing.value > 0) {
            Text("${missing.value} setup items need attention")
        }
        Button(onClick = { scope.launch { store.setEnabled(!enabled) } }) {
            Text(if (enabled) "Disable gate" else "Enable gate")
        }
        Button(onClick = onPicker) { Text("Whitelisted apps") }
        Button(onClick = onKnobs) { Text("Timings") }
        Button(onClick = onSetup) { Text("Setup checklist") }
    }
}

private data class AppEntry(
    val label: String,
    val pkg: String,
)

private fun loadApps(pm: PackageManager): List<AppEntry> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm
        .queryIntentActivities(intent, 0)
        .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

@Composable
private fun pickerScreen(
    store: GateStore,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pm = store.appContext.packageManager
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.Default) { loadApps(pm) }
    }
    val whitelist by store.whitelist.collectAsState(initial = emptySet())
    var query by remember { mutableStateOf("") }
    // Ticked-top order freezes while toggling (re-sorts on filter/data change only),
    // so rows never teleport under the finger.
    val tickedAtFilter = remember(query, apps) { whitelist }
    val ordered =
        remember(query, apps, tickedAtFilter) {
            val all = apps ?: emptyList()
            val filtered =
                if (query.isBlank()) {
                    all
                } else {
                    all.filter {
                        it.label.contains(query, ignoreCase = true) || it.pkg.contains(query, ignoreCase = true)
                    }
                }
            filtered.sortedWith(compareBy({ it.pkg !in tickedAtFilter }, { it.label.lowercase() }))
        }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack) { Text("Back") }
            Spacer(Modifier.width(12.dp))
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(8.dp))
        if (apps == null) {
            Text("Loading apps…")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(ordered, key = { it.pkg }) { app ->
                val checked = app.pkg in whitelist
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(app.label)
                        Text(app.pkg, style = MaterialTheme.typography.bodySmall)
                    }
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { scope.launch { store.setWhitelisted(app.pkg, it) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun knobField(
    label: String,
    value: Long,
    onSet: (Long) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        TextField(
            value = text,
            onValueChange = {
                text = it
                it.toLongOrNull()?.let { v -> if (v >= 0) onSet(v) }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.width(110.dp),
        )
    }
}

@Composable
private fun knobsScreen(
    store: GateStore,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val cfg by store.config.collectAsState(initial = ZenConfig())
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        Button(onClick = onBack) { Text("Back") }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                knobField("Session allowance", cfg.sessionAllowSec) {
                    scope.launch { store.setKnob(GateStoreKeys.K_SESSION_ALLOW, it) }
                }
                knobField("Free pool on unlock", cfg.unlockPoolSec) {
                    scope.launch { store.setKnob(GateStoreKeys.K_UNLOCK_POOL, it) }
                }
                knobField("Base wait", cfg.baseWaitSec) {
                    scope.launch { store.setKnob(GateStoreKeys.K_BASE_WAIT, it) }
                }
                knobField("Wait increment", cfg.waitIncrementSec) {
                    scope.launch { store.setKnob(GateStoreKeys.K_WAIT_INC, it) }
                }
                knobField("Quick-open wait (0 hides)", cfg.quickWaitSec) {
                    scope.launch { store.setKnob(GateStoreKeys.K_QUICK_WAIT, it) }
                }
            }
        }
    }
}

@Composable
private fun knobField(
    label: String,
    value: Long,
    default: Long,
    onSet: (Long) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("$label (dflt $default)", modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        TextField(
            value = text,
            onValueChange = {
                text = it
                it.toLongOrNull()?.let { v -> if (v >= 0) onSet(v) }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.width(110.dp),
        )
    }
}

@Composable
private fun setupScreen(
    store: GateStore,
    onBack: () -> Unit,
    onFix: (String) -> Unit,
) {
    val context = store.appContext
    var refresh by remember { mutableStateOf(0) }
    val checks = remember(refresh) { SetupChecks.all(context) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack) { Text("Back") }
            Spacer(Modifier.width(12.dp))
            Button(onClick = { refresh++ }) { Text("Refresh") }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(checks, key = { it.id }) { check ->
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val dot =
                            when (check.status) {
                                CheckStatus.OK -> "● "
                                CheckStatus.MISSING -> "○ "
                                CheckStatus.MANUAL -> "◐ "
                            }
                        Text(dot + check.label, modifier = Modifier.weight(1f))
                        if (check.status != CheckStatus.OK) {
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { onFix(check.id) }) { Text("Fix") }
                        }
                    }
                    Text(check.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
