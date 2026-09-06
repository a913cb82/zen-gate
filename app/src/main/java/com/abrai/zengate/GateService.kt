package com.abrai.zengate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Persistent foreground presence for the gate (ScreenZen-shape: an idle FGS
 * keeps HyperOS delivering accessibility events). Mirrors [GateStore] into
 * [GateState] and hosts the notification kill switch. M3 grows the engine here.
 */
class GateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: GateStore

    override fun onCreate() {
        super.onCreate()
        store = GateStore(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Zen Gate", NotificationManager.IMPORTANCE_MIN),
        )
        startForeground(NOTIFICATION_ID, buildNotification(true), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        scope.launch {
            combine(store.enabled, store.sessionExpiryMs) { enabled, expiry ->
                enabled to expiry
            }.collect { (enabled, expiry) ->
                GateState.enabled = enabled
                GateState.sessionExpiryMs = expiry
                // Toggle-refresh needs the runtime grant (lint-enforced); the FGS
                // post itself is exempt. M5-ui onboarding requests the grant.
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    manager.notify(NOTIFICATION_ID, buildNotification(enabled))
                }
            }
        }
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
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
