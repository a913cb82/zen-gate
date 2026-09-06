package com.abrai.zengate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.abrai.zengate.policy.PoolEngine
import com.abrai.zengate.policy.ZenConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        val cfg = ZenConfig()
        when (action) {
            GateAlarms.ACTION_POOL_EXPIRED -> {
                val cur = GateState.poolState().copy(poolSec = 0)
                store.savePool(cur)
                GateState.drainEnterElapsedMs = 0L
                maybeBlock(context, cur, cfg)
            }
            GateAlarms.ACTION_SESSION_END -> {
                val cur = GateState.poolState()
                if (PoolEngine.sessionRemainingMs(cur, System.currentTimeMillis(), cfg) > 0) return
                store.savePool(cur.copy(sessionStartWallMs = 0L, sessionScreenOnMs = 0L))
                maybeBlock(context, cur, cfg)
            }
            GateAlarms.ACTION_MIDNIGHT -> {
                val today = GateAlarms.todayId()
                store.savePool(PoolEngine.midnightReset(today, System.currentTimeMillis(), cfg))
                GateAlarms.scheduleMidnight(context)
                Log.d(TAG, "midnight reset done day=$today")
            }
            else -> {
                // Boot chain (no action): re-establish foreground presence.
                context.startForegroundService(Intent(context, GateService::class.java))
                Log.d(TAG, "gate service (re)started from alarm")
            }
        }
    }

    private fun maybeBlock(
        context: Context,
        state: com.abrai.zengate.policy.PoolState,
        cfg: ZenConfig,
    ) {
        val pkg = GateState.lastGatedPkg ?: return
        if (!GateState.enabled) return
        if (PoolEngine.hasSession(state) &&
            PoolEngine.sessionRemainingMs(state, System.currentTimeMillis(), cfg) > 0
        ) {
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
