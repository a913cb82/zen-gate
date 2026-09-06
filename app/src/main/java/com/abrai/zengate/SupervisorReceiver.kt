package com.abrai.zengate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * M5-core: alarm-fired gate (re)start. Runs after boot (via [BootReceiver]) and
 * doubles as the future supervisor tick (M3+ engine checks piggyback here).
 * Starting GateService from an exact-alarm receiver is an allowed
 * foreground-service trigger on Android 15.
 */
class SupervisorReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        try {
            context.startForegroundService(Intent(context, GateService::class.java))
            Log.d(TAG, "gate service (re)started from alarm")
        } catch (t: Throwable) {
            Log.e(TAG, "gate restart failed", t)
        }
    }

    companion object {
        private const val TAG = "ZenGate"
    }
}
