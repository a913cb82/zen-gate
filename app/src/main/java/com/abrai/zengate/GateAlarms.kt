package com.abrai.zengate

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import java.time.Instant
import java.time.ZoneId

/**
 * One-shot deadlines for the no-polling design. Every countdown (pool drain,
 * session allowance/hard limit, midnight) is an exact alarm recomputed on
 * transitions; nothing ever ticks. Production may relax these to inexact.
 */
object GateAlarms {
    const val ACTION_POOL_EXPIRED = "com.abrai.zengate.POOL_EXPIRED"
    const val ACTION_SESSION_END = "com.abrai.zengate.SESSION_END"
    const val ACTION_PAUSE_END = "com.abrai.zengate.PAUSE_END"
    const val ACTION_MIDNIGHT = "com.abrai.zengate.MIDNIGHT"

    private const val RC_POOL = 11
    private const val RC_SESSION = 12
    private const val RC_PAUSE = 13
    private const val RC_MIDNIGHT = 14
    private const val TAG = "ZenGate"

    fun nextResetMs(
        nowWallMs: Long,
        hour: Int,
        minute: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        // Same clamped instant logic as PoolEngine.dayIdFor: the two agree by
        // construction, including across DST gaps (gap resolves forward).
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        val now = Instant.ofEpochMilli(nowWallMs).atZone(zone)
        val todayReset = now.toLocalDate().atTime(h, m).atZone(zone)
        val target =
            if (todayReset.toInstant().isAfter(now.toInstant())) todayReset else todayReset.plusDays(1)
        return target.toInstant().toEpochMilli()
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

    /** Kill-switch pause: configurable duration, wall-clock, auto-resumed by this alarm. */
    fun schedulePauseEnd(
        context: Context,
        delayMs: Long,
    ) {
        exactIn(context, ACTION_PAUSE_END, RC_PAUSE, delayMs)
    }

    fun cancelPauseEnd(context: Context) = cancel(context, ACTION_PAUSE_END, RC_PAUSE)

    fun scheduleMidnight(
        context: Context,
        hour: Int,
        minute: Int,
        nowWallMs: Long = System.currentTimeMillis(),
    ) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val operation = operation(context, ACTION_MIDNIGHT, RC_MIDNIGHT)
        val atWall = nextResetMs(nowWallMs, hour, minute)
        try {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atWall, operation)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atWall, operation)
            }
        } catch (se: SecurityException) {
            // Exact grant revoked between check and set: inexact fallback.
            Log.w(TAG, "exact midnight denied; inexact fallback", se)
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
        try {
            if (exact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    operation,
                )
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, operation)
            }
        } catch (se: SecurityException) {
            // Exact grant revoked between check and set: inexact fallback.
            Log.w(TAG, "exact alarm denied; inexact fallback action=$action", se)
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
