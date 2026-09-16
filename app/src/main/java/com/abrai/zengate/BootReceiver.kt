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
        private const val REQUEST_CODE_BACKSTOP = 2

        /** Boot restart backoff: one fast shot, one bounded backstop, then silent. */
        fun restartDelaysMs(): List<Long> = listOf(60_000L, 180_000L)

        fun gateRestartIntent(
            context: Context,
            requestCode: Int = REQUEST_CODE,
        ): PendingIntent {
            val intent = Intent(context, SupervisorReceiver::class.java)
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        fun scheduleGateRestart(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val now = SystemClock.elapsedRealtime()
            val exact = alarmManager.canScheduleExactAlarms()
            // The SupervisorReceiver no-action branch restarts the FGS idempotently,
            // so a second alarm is a pure backstop, never a duplicate gate.
            val codes = listOf(REQUEST_CODE, REQUEST_CODE_BACKSTOP)
            for ((i, delay) in restartDelaysMs().withIndex()) {
                val operation = gateRestartIntent(context, codes[i])
                val triggerAt = now + delay
                if (exact) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        operation,
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        operation,
                    )
                }
            }
            Log.d(TAG, "gate-restart alarms set exact=$exact")
        }
    }
}
