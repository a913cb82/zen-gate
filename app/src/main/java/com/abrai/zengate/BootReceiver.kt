package com.abrai.zengate

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * M5-core: re-establish the gate after reboot. Android 15 forbids starting a
 * foreground service directly from BOOT_COMPLETED, so we chain through an
 * exact alarm (an allowed FGS trigger). Needs SCHEDULE_EXACT_ALARM; falls back
 * to inexact (slower re-establishment) if not granted.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.d(TAG, "boot completed; scheduling gate restart")
        scheduleGateRestart(context)
    }

    companion object {
        private const val TAG = "ZenGate"
        private const val REQUEST_CODE = 1
        private const val DELAY_MS = 60_000L

        fun gateRestartIntent(context: Context): PendingIntent {
            val intent = Intent(context, SupervisorReceiver::class.java)
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        fun scheduleGateRestart(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val triggerAt = SystemClock.elapsedRealtime() + DELAY_MS
            val operation = gateRestartIntent(context)
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    operation,
                )
                Log.d(TAG, "exact gate-restart alarm set")
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    operation,
                )
                Log.d(TAG, "inexact gate-restart alarm set (exact alarms not granted)")
            }
        }
    }
}
