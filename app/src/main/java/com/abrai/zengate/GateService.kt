package com.abrai.zengate

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.abrai.zengate.policy.PoolEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Persistent foreground presence for the gate (ScreenZen-shape). Mirrors
 * [GateStore] into [GateState], tracks screen on/off for session accounting,
 * and hosts the notification kill switch. Engine deadlines live in [GateAlarms].
 */
class GateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: GateStore
    private var lastResetHour: Int = -1
    private var lastResetMinute: Int = -1
    private val screenReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenOff()
                    Intent.ACTION_SCREEN_ON -> onScreenOn()
                    Intent.ACTION_USER_PRESENT -> onPhoneUnlock()
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        store = GateStore(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Zen Gate", NotificationManager.IMPORTANCE_MIN),
        )
        startForeground(NOTIFICATION_ID, buildNotification(true), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        scope.launch {
            combine(
                store.enabled,
                store.snapshot,
                store.config,
                store.whitelist,
                GateState.lastEventPkg,
            ) { enabled, snap, cfg, list, fg ->
                GateState.enabled = enabled
                GateState.poolSec = snap.poolSec
                GateState.usagesToday = snap.usagesToday
                GateState.dayId = snap.dayId
                GateState.sessionExpiryMs = snap.sessionExpiryMs
                GateState.config = cfg
                GateState.userWhitelist = list
                GateState.storeLoaded = true
                if (cfg.resetHour != lastResetHour || cfg.resetMinute != lastResetMinute) {
                    lastResetHour = cfg.resetHour
                    lastResetMinute = cfg.resetMinute
                    GateAlarms.scheduleMidnight(this@GateService, lastResetHour, lastResetMinute)
                }
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    val line = statusLine(enabled, snap.poolState(), cfg, list, fg)
                    if (line != lastStatusLine) {
                        lastStatusLine = line
                        manager.notify(NOTIFICATION_ID, buildNotification(enabled))
                    }
                }
            }.collect { }
        }
        registerReceiver(
            screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_ON).apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                // Dynamic (not manifest): HyperOS SmartPower denies this broadcast
                // to background manifest receivers; the live FGS gets it directly.
                addAction(Intent.ACTION_USER_PRESENT)
            },
            RECEIVER_NOT_EXPORTED,
        )
        GateAlarms.scheduleMidnight(this, GateState.config.resetHour, GateState.config.resetMinute)
        Log.d(TAG, "foreground presence active")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_TOGGLE) {
            scope.launch {
                store.setEnabled(!GateState.enabled)
                Log.d(TAG, "kill switch toggled")
            }
        }
        if (intent?.action == ACTION_DEADLINE) {
            onDeadline(intent.getStringExtra(EXTRA_DEADLINE).orEmpty())
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (t: Throwable) {
            Log.e(TAG, "unregister failed", t)
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onScreenOff() {
        Log.d(TAG, "screen off")
        if (!GateState.storeLoaded) return
        val elapsed = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        scope.launch {
            val cfg = GateState.config
            var snap = GateState.poolState()
            // Pause pool drain. Sessions are wall-clock: unaffected by screen state.
            if (GateState.lastGatedPkg != null &&
                GateState.drainEnterElapsedMs != 0L &&
                !PoolEngine.hasSession(snap)
            ) {
                val spent = ((elapsed - GateState.drainEnterElapsedMs) / 1_000).coerceAtLeast(0)
                snap = PoolEngine.drain(snap, spent)
                GateState.drainEnterElapsedMs = 0L
                GateAlarms.cancelPoolExpiry(this@GateService)
                Log.d(TAG, "screen off: drained $spent pool=${snap.poolSec}")
            }
            store.savePool(snap)
        }
    }

    private fun onPhoneUnlock() {
        if (!GateState.storeLoaded) return
        scope.launch {
            val snap = GateState.poolState()
            val cur = PoolEngine.phoneUnlock(snap, System.currentTimeMillis(), GateState.config)
            if (cur != snap) {
                GateState.applyPool(cur)
                store.savePool(cur)
                // Open the drain segment now: the unlock reveal often emits no
                // window event, and without this the grace clock never starts.
                GateState.drainEnterElapsedMs = SystemClock.elapsedRealtime()
                GateAlarms.schedulePoolExpiry(this@GateService, cur.poolSec * 1_000)
                Log.d(TAG, "phone unlock; grace pool=${cur.poolSec}")
            }
        }
    }

    private fun onScreenOn() {
        Log.d(TAG, "screen on")
        if (!GateState.storeLoaded) return
        val elapsed = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        scope.launch {
            val cfg = GateState.config
            val snap = GateState.poolState()
            store.savePool(snap)
            if (!PoolEngine.hasSession(snap) &&
                GateState.lastGatedPkg != null &&
                snap.poolSec > 0 &&
                GateState.drainEnterElapsedMs == 0L
            ) {
                GateState.drainEnterElapsedMs = elapsed
                GateAlarms.schedulePoolExpiry(this@GateService, snap.poolSec * 1_000)
            }
        }
    }

    private var lastStatusLine: String? = null

    private fun statusLine(
        enabled: Boolean,
        snap: com.abrai.zengate.policy.PoolState,
        cfg: com.abrai.zengate.policy.ZenConfig,
        list: Set<String>,
        fg: String?,
    ): String {
        val remaining =
            com.abrai.zengate.policy.PoolEngine
                .sessionRemainingMs(snap, System.currentTimeMillis(), cfg)
        return when (
            val s =
                com.abrai.zengate.policy.GateStatus.describe(
                    enabled,
                    remaining,
                    snap.sessionExpiryWallMs,
                    snap.poolSec,
                    snap.usagesToday,
                    fg,
                    list,
                    BlockActivity.isShowing,
                )
        ) {
            com.abrai.zengate.policy.GateStatus.Status.Paused -> "Paused — tap Enable gate to resume"
            is com.abrai.zengate.policy.GateStatus.Status.Session -> "Session free until " + endLabel(s.expiryWallMs)
            is com.abrai.zengate.policy.GateStatus.Status.Whitelisted -> appLabel(s.pkg) + " — unlimited"
            is com.abrai.zengate.policy.GateStatus.Status.FreeTime -> "Free time — ${s.poolSec}s left"
            is com.abrai.zengate.policy.GateStatus.Status.Blocking ->
                "Blocking - ${s.usagesToday} unlocks today"
        }
    }

    private fun endLabel(wallMs: Long): String =
        try {
            java.time.LocalTime
                .ofInstant(
                    java.time.Instant.ofEpochMilli(wallMs),
                    java.time.ZoneId.systemDefault(),
                ).toString()
                .substring(0, 5)
        } catch (t: Throwable) {
            ""
        }

    private fun appLabel(pkg: String): String =
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (t: Throwable) {
            pkg
        }

    private fun buildNotification(enabled: Boolean): Notification {
        val toggle =
            PendingIntent.getService(
                this,
                0,
                Intent(this, GateService::class.java).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification
            .Builder(this, CHANNEL_ID)
            .setContentTitle(if (enabled) "Zen Gate is watching" else "Zen Gate paused")
            .setContentText(
                lastStatusLine
                    ?: statusLine(
                        enabled,
                        GateState.poolState(),
                        GateState.config,
                        GateState.userWhitelist,
                        GateState.lastEventPkg.value,
                    ),
            ).setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .addAction(
                Notification.Action
                    .Builder(
                        null,
                        if (enabled) "Disable gate" else "Enable gate",
                        toggle,
                    ).build(),
            ).build()
    }

    /**
     * Alarm-time launch path (single process): the manifest receiver persisted
     * first, so a store-direct read is fresh by construction (no ordering race).
     * The verdict runs against the live a11y root via the shared static.
     */
    private fun onDeadline(deadline: String) {
        scope.launch {
            try {
                val snap = store.snapshot.first().poolState()
                val cfg = store.config.first()
                val enabled = store.enabled.first()
                val list = store.whitelist.first()
                val wall = System.currentTimeMillis()
                val power = getSystemService(PowerManager::class.java)
                val keys = getSystemService(KeyguardManager::class.java)
                val remaining = PoolEngine.sessionRemainingMs(snap, wall, cfg)
                val usageFg =
                    com.abrai.zengate.policy.UsageOracle
                        .queryForeground(this@GateService)
                when (
                    val v =
                        ZenGateService.decideDeadline(
                            enabled,
                            remaining,
                            packageName,
                            list,
                            keys.isKeyguardLocked,
                            power.isInteractive,
                            usageFg,
                        )
                ) {
                    is com.abrai.zengate.policy.DeadlineVerdict.Outcome.Launch -> {
                        Log.d(TAG, "deadline launch pkg=${v.pkg} via $deadline")
                        startActivity(
                            Intent(this@GateService, BlockActivity::class.java)
                                .putExtra(BlockActivity.EXTRA_PACKAGE, v.pkg)
                                .putExtra(BlockActivity.EXTRA_WAIT_SEC, PoolEngine.penaltySec(snap, cfg).toInt())
                                .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                ),
                        )
                    }
                    com.abrai.zengate.policy.DeadlineVerdict.Outcome.Skip ->
                        Log.d(TAG, "deadline skipped via $deadline")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "deadline handling failed", t)
            }
        }
    }

    companion object {
        private const val TAG = "ZenGate"
        private const val CHANNEL_ID = "gate"
        private const val NOTIFICATION_ID = 1
        const val ACTION_TOGGLE = "com.abrai.zengate.TOGGLE"
        const val ACTION_DEADLINE = "com.abrai.zengate.DEADLINE"
        const val EXTRA_DEADLINE = "deadline"
    }
}
