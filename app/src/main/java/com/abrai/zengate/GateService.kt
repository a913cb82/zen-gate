package com.abrai.zengate

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
import android.os.SystemClock
import android.util.Log
import com.abrai.zengate.policy.PoolEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
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
            ) { enabled, snap, cfg, list ->
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
                    manager.notify(NOTIFICATION_ID, buildNotification(enabled))
                }
            }.collect { }
        }
        registerReceiver(
            screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_ON).apply {
                addAction(Intent.ACTION_SCREEN_OFF)
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

    private fun onScreenOn() {
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
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
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

    companion object {
        private const val TAG = "ZenGate"
        private const val CHANNEL_ID = "gate"
        private const val NOTIFICATION_ID = 1
        const val ACTION_TOGGLE = "com.abrai.zengate.TOGGLE"
    }
}
