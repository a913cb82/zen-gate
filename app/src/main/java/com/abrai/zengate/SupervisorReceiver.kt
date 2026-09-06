package com.abrai.zengate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.abrai.zengate.policy.PoolEngine
import com.abrai.zengate.policy.ZenConfig
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
        val enabled = store.enabled.first()
        val cfg = store.config.first()
        when (action) {
            Intent.ACTION_USER_PRESENT -> {
                // Phone unlock: outside a session this grants the grace pool
                // (10s free use before the block); inside one it changes nothing.
                val cur = PoolEngine.phoneUnlock(snap, System.currentTimeMillis(), cfg)
                if (cur != snap) {
                    GateState.applyPool(cur)
                    store.savePool(cur)
                    Log.d(TAG, "phone unlock; grace pool=${cur.poolSec}")
                }
            }
            GateAlarms.ACTION_POOL_EXPIRED -> {
                val cur = snap.copy(poolSec = 0)
                GateState.applyPool(cur)
                store.savePool(cur)
                GateState.drainEnterElapsedMs = 0L
                // Pool-empty is its own pending state: if the screen is off, the next
                // gated entry blocks naturally. Only launch over a lit screen.
                if (isInteractive(context)) {
                    maybeBlock(context, cur, cfg, enabled)
                }
            }
            GateAlarms.ACTION_SESSION_END -> {
                if (PoolEngine.sessionRemainingMs(snap, System.currentTimeMillis(), cfg) > 0) return
                val cleared = PoolEngine.endSession(snap)
                GateState.applyPool(cleared)
                store.savePool(cleared)
                Log.d(TAG, "session ended; block follows")
                if (isInteractive(context)) {
                    maybeBlock(context, cleared, cfg, enabled)
                }
            }
            GateAlarms.ACTION_MIDNIGHT -> {
                val now = System.currentTimeMillis()
                val today = PoolEngine.dayIdFor(now, cfg.resetHour, cfg.resetMinute)
                val reset = PoolEngine.midnightReset(today, now, cfg)
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

    private fun isInteractive(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return power.isInteractive
    }

    private fun maybeBlock(
        context: Context,
        state: com.abrai.zengate.policy.PoolState,
        cfg: ZenConfig,
        enabled: Boolean,
    ) {
        // Launch only when the sticky last foreground is itself a gated surface:
        // firing over a whitelisted app would break the whitelist promise, and its
        // exit always emits window events, so the entry path blocks then (pool=0).
        // (Process-importance checks were tried and lie on HyperOS; the sticky
        // value is kept clean of transient system surfaces instead. IMEs never
        // emit window-state events, so this passes an empty IME set.)
        val pkg = GateState.lastForegroundPkg ?: return
        if (!enabled) return
        if (PoolEngine.hasSession(state) &&
            PoolEngine.sessionRemainingMs(state, System.currentTimeMillis(), cfg) > 0
        ) {
            return
        }
        if (!com.abrai.zengate.policy.GatePolicy.isGated(
                pkg,
                context.packageName,
                emptySet(),
                GateState.userWhitelist,
            )
        ) {
            Log.d(TAG, "deadline inside whitelisted $pkg; entry path will block")
            return
        }
        context.startActivity(
            Intent(context, BlockActivity::class.java)
                .putExtra(BlockActivity.EXTRA_PACKAGE, pkg)
                .putExtra(BlockActivity.EXTRA_WAIT_SEC, PoolEngine.penaltySec(state, cfg).toInt())
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
        )
    }

    companion object {
        private const val TAG = "ZenGate"
    }
}
