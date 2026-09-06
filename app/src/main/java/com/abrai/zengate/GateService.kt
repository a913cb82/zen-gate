package com.abrai.zengate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * M5-core experiment: persistent foreground presence for the gate.
 * ScreenZen-class blockers all hold FGS + exemptions; our cached process may be
 * exactly what HyperOS stops feeding. No work loop here (M3 adds the engine) —
 * existence at foreground importance is the whole point.
 */
class GateService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Zen Gate", NotificationManager.IMPORTANCE_MIN),
        )
        val notification =
            Notification
                .Builder(this, CHANNEL_ID)
                .setContentTitle("Zen Gate is watching")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setOngoing(true)
                .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        Log.d(TAG, "foreground presence active")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ZenGate"
        private const val CHANNEL_ID = "gate"
        private const val NOTIFICATION_ID = 1
    }
}
