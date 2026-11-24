package com.abrai.zengate.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abrai.zengate.GateStore
import com.abrai.zengate.policy.AppOrder
import com.abrai.zengate.policy.AppRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun pickerScreen(
    store: GateStore,
    onBack: () -> Unit,
    onOverlays: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppRow>?>(null) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.Default) { loadApps(context.packageManager) }
    }
    val whitelist by store.whitelist.collectAsState(initial = emptySet())
    val transparent by store.transparent.collectAsState(initial = emptySet())
    var query by remember { mutableStateOf("") }
    // Freeze the ON/OFF grouping while toggling so rows never jump under the
    // finger; re-snapshot only when the list loads or the search changes.
    var frozenAllowed by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(apps, query) {
        if (apps != null) frozenAllowed = whitelist
    }
    val ordered =
        remember(query, apps, frozenAllowed) {
            AppOrder.order(apps ?: emptyList(), frozenAllowed ?: whitelist, query)
        }

    zenScreen {
        backHeader(
            "Whitelist",
            onBack,
            trailing = {
                Text(
                    "Overlays",
                    color = Green,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier =
                        Modifier
                            .clickable(onClick = onOverlays)
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                )
            },
        )
        searchField(query) { query = it }
        Spacer(Modifier.height(12.dp))
        if (apps == null) {
            Text("Loading apps…", color = Muted, fontSize = 13.sp)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ordered, key = { it.pkg }) { app ->
                    pickerRow(
                        app = app,
                        on = app.pkg in whitelist,
                        overlay = app.pkg in transparent,
                        onToggle = { on -> scope.launch { store.setWhitelisted(app.pkg, on) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun searchField(
    value: String,
    onChange: (String) -> Unit,
) {
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text("Search apps…", color = Subtle, fontSize = 14.sp) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = Card,
                unfocusedContainerColor = Card,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Green,
            ),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    )
}

@Composable
private fun pickerRow(
    app: AppRow,
    on: Boolean,
    overlay: Boolean,
    showOverlayChip: Boolean = true,
    onToggle: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onToggle(!on) },
        shape = RoundedCornerShape(16.dp),
        color = Card,
        border = BorderStroke(1.dp, Border),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            letterTile(app.label, on)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    app.label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        app.pkg,
                        fontSize = 11.sp,
                        color = Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (overlay && showOverlayChip) {
                        Spacer(Modifier.width(8.dp))
                        overlayChip()
                    }
                }
            }
            onOffPill(on = on, onToggle = onToggle)
        }
    }
}

@Composable
fun overlayScreen(
    store: GateStore,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppRow>?>(null) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.Default) { loadApps(context.packageManager) }
    }
    val overlays by store.transparent.collectAsState(initial = emptySet())
    var query by remember { mutableStateOf("") }
    var frozenOverlay by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(apps, query) {
        if (apps != null) frozenOverlay = overlays
    }
    val ordered =
        remember(query, apps, frozenOverlay) {
            AppOrder.order(apps ?: emptyList(), frozenOverlay ?: overlays, query)
        }

    zenScreen {
        backHeader("Overlay apps", onBack, backLabel = "Whitelist")
        searchField(query) { query = it }
        Spacer(Modifier.height(12.dp))
        if (apps == null) {
            Text("Loading apps…", color = Muted, fontSize = 13.sp)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ordered, key = { it.pkg }) { app ->
                    pickerRow(
                        app = app,
                        on = app.pkg in overlays,
                        overlay = app.pkg in overlays,
                        showOverlayChip = false,
                        onToggle = { on -> scope.launch { store.setTransparent(app.pkg, on) } },
                    )
                }
            }
        }
    }
}

private fun loadApps(pm: PackageManager): List<AppRow> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm
        .queryIntentActivities(intent, 0)
        .map { AppRow(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
        .distinctBy { it.pkg }
}
