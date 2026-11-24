package com.abrai.zengate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.abrai.zengate.policy.PoolEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Executes one-shot engine deadlines. Recomputes from the [GateState] mirror
 * (same process) and persists via [GateStore]. Never loops.
 */
class SupervisorReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handle(context.applicationContext, intent.action)
            } catch (t: Throwable) {
                Log.e(TAG, "deadline handling failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(
        context: Context,
        action: String?,
    ) {
        val store = GateStore(context)
        // Store-direct: receivers can run in a fresh process whose mirror never loaded.
        // Reading the file (not the mirror) makes clobbering with defaults impossible.
        val snap = store.snapshot.first().poolState()
        val cfg = store.config.first()
        when (action) {
            // USER_PRESENT was removed here (P0#3): manifest delivery is
            // SmartPower-denied on HyperOS (see PORTABILITY.md), so the live
            // GateService dynamic receiver is the single grace-grant owner.
            // A branch here would only double-grant or rot.
            GateAlarms.ACTION_POOL_EXPIRED -> {
                // Persist here; the launch decision lives in ZenGateService
                // (live active window). Forward package-scoped implicit: alarm
                // PendingIntents are explicit, which dynamic receivers never get.
                val cur = snap.copy(poolSec = 0)
                GateState.applyPool(cur)
                store.savePool(cur)
                GateState.drainEnterElapsedMs = null
                Log.d(TAG, "pool expired; forwarding to gate service")
                forward(context, GateAlarms.ACTION_POOL_EXPIRED)
            }
            GateAlarms.ACTION_SESSION_END -> {
                if (PoolEngine.sessionRemainingMs(snap, System.currentTimeMillis(), cfg) > 0) return
                val cleared = PoolEngine.endSession(snap)
                GateState.applyPool(cleared)
                store.savePool(cleared)
                Log.d(TAG, "session ended; forwarding to gate service")
                forward(context, GateAlarms.ACTION_SESSION_END)
            }
            GateAlarms.ACTION_PAUSE_END -> {
                // Pause elapsed (configurable duration): clear store-direct (same
                // fresh-process discipline as the other branches).
                GateState.pauseUntilMs = 0
                store.setPauseUntil(0)
                // Re-arm the drain from the persisted remainder when an app is
                // still gated, pool remains, and no session covers it.
                synchronized(GateState.drainLock) {
                    if (GateState.lastGatedPkg != null &&
                        snap.poolSec > 0 &&
                        !PoolEngine.hasSession(snap) &&
                        GateState.drainEnterElapsedMs == null
                    ) {
                        GateState.drainEnterElapsedMs = SystemClock.elapsedRealtime()
                        GateAlarms.schedulePoolExpiry(context, snap.poolSec * 1_000)
                    }
                }
                Log.d(TAG, "pause ended; gate active")
            }
            GateAlarms.ACTION_MIDNIGHT -> {
                val now = System.currentTimeMillis()
                val today = PoolEngine.dayIdFor(now, cfg.resetHour, cfg.resetMinute)
                val reset = PoolEngine.midnightReset(today, cfg)
                GateState.applyPool(reset)
                store.savePool(reset)
                GateAlarms.scheduleMidnight(context, cfg.resetHour, cfg.resetMinute, now)
                Log.d(TAG, "midnight reset done day=$today")
            }
            else -> {
                // Boot chain (no action): re-establish foreground presence.
                context.startForegroundService(Intent(context, GateService::class.java))
                Log.d(TAG, "gate service (re)started from alarm")
            }
        }
    }

    /**
     * Intra-app forward on the proven receiver->FGS path (same mechanism as
     * the boot chain). The gate service decides on the live a11y root via the
     * shared-process static — no broadcasts for the decision itself, because
     * alarm PendingIntents are explicit (dynamic receivers never match those)
     * and HyperOS eats same-app implicit broadcasts from background contexts.
     */
    private fun forward(
        context: Context,
        deadline: String,
    ) {
        try {
            context.startForegroundService(
                Intent(context, GateService::class.java)
                    .setAction(GateService.ACTION_DEADLINE)
                    .putExtra(GateService.EXTRA_DEADLINE, deadline),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "deadline forward failed", t)
        }
    }

    companion object {
        private const val TAG = "ZenGate"
    }
}
