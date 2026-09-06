package com.abrai.zengate

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * One-shot deadlines for the no-polling design. Every countdown (pool drain,
 * session allowance/hard limit, midnight) is an exact alarm recomputed on
 * transitions; nothing ever ticks. Production may relax these to inexact.
 */
object GateAlarms {
    const val ACTION_POOL_EXPIRED = "com.abrai.zengate.POOL_EXPIRED"
    const val ACTION_SESSION_END = "com.abrai.zengate.SESSION_END"
    const val ACTION_MIDNIGHT = "com.abrai.zengate.MIDNIGHT"

    private const val RC_POOL = 11
    private const val RC_SESSION = 12
    private const val RC_MIDNIGHT = 14
    private const val TAG = "ZenGate"

    fun nextResetMs(
        nowWallMs: Long,
        hour: Int,
        minute: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowWallMs), zone)
        val todayReset = now.toLocalDate().atTime(hour, minute)
        val target = if (todayReset.isAfter(now)) todayReset else todayReset.plusDays(1)
        return target.atZone(zone).toInstant().toEpochMilli()
    }

    fun schedulePoolExpiry(
        context: Context,
        delayMs: Long,
    ) {
        exactIn(context, ACTION_POOL_EXPIRED, RC_POOL, delayMs)
    }

    fun cancelPoolExpiry(context: Context) = cancel(context, ACTION_POOL_EXPIRED, RC_POOL)

    fun scheduleSessionEnd(
        context: Context,
        delayMs: Long,
    ) {
        exactIn(context, ACTION_SESSION_END, RC_SESSION, delayMs)
    }

    fun scheduleMidnight(
        context: Context,
        hour: Int,
        minute: Int,
        nowWallMs: Long = System.currentTimeMillis(),
    ) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val operation = operation(context, ACTION_MIDNIGHT, RC_MIDNIGHT)
        val atWall = nextResetMs(nowWallMs, hour, minute)
        if (alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atWall, operation)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atWall, operation)
        }
        Log.d(TAG, "midnight alarm set")
    }

    private fun exactIn(
        context: Context,
        action: String,
        rc: Int,
        delayMs: Long,
    ) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val operation = operation(context, action, rc)
        val triggerAt = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(1L)
        val exact = alarmManager.canScheduleExactAlarms()
        Log.d(TAG, "alarm schedule action=$action delayMs=$delayMs exact=$exact")
        if (exact) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                operation,
            )
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, operation)
        }
    }

    private fun cancel(
        context: Context,
        action: String,
        rc: Int,
    ) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        Log.d(TAG, "alarm cancel action=$action")
        alarmManager.cancel(operation(context, action, rc))
    }

    private fun operation(
        context: Context,
        action: String,
        rc: Int,
    ): PendingIntent {
        val intent = Intent(context, SupervisorReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            rc,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
