package com.abrai.zengate

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.abrai.zengate.policy.GatePolicy
import com.abrai.zengate.policy.PoolEngine
import com.abrai.zengate.policy.ZenConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * M3: event-driven pool orchestration. Every transition either starts, pauses,
 * or settles pool drain via one-shot alarms — no polling. Session-aware:
 * active sessions and the kill switch suppress everything.
 */
class ZenGateService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: GateStore
    private var cachedImes: Set<String> = emptySet()
    private var imeCacheAt: Long = 0

    override fun onServiceConnected() {
        store = GateStore(this)
        Log.d(TAG, "connected; enabledImes=${enabledImes()}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        try {
            val pkg = event.packageName?.toString().orEmpty()
            val gated = GatePolicy.isGated(pkg, packageName, enabledImes())
            Log.d(TAG, "foreground=$pkg gated=$gated")
            if (!gated) {
                settleDrain()
                GateState.lastGatedPkg = null
                return
            }
            onGated(pkg)
        } catch (t: Throwable) {
            // A gate must never die: log and survive.
            Log.e(TAG, "event handling failed", t)
        }
    }

    override fun onInterrupt() = Unit

    private fun onGated(pkg: String) {
        val cfg = ZenConfig()
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        var cur = GateState.poolState()
        val today = GateAlarms.todayId()
        if (cur.dayId.isEmpty()) {
            cur = cur.copy(dayId = today)
        } else if (PoolEngine.needsMidnightReset(cur, today)) {
            cur = PoolEngine.midnightReset(today, wall, cfg)
        } else {
            val refilled = PoolEngine.refill(cur, wall, cfg)
            if (refilled != cur) cur = refilled
        }
        if (!GateState.enabled) {
            persist(cur)
            return
        }
        Log.d(
            TAG,
            "mirror pool=${cur.poolSec} usages=${cur.usagesToday} day=${cur.dayId} " +
                "sessStart=${cur.sessionStartWallMs} accum=${cur.sessionScreenOnMs}",
        )
        if (PoolEngine.hasSession(cur)) {
            // Event-driven expiry (alarm is the backstop for the no-events case).
            if (PoolEngine.sessionRemainingMs(cur, wall, cfg) <= 0) {
                cur = cur.copy(sessionStartWallMs = 0L, sessionScreenOnMs = 0L)
            } else {
                GateState.lastGatedPkg = pkg
                persist(cur)
                return
            }
        }
        if (pkg != GateState.lastGatedPkg) {
            settleDrainLocked(cur, elapsed)
            cur = GateState.poolState()
            GateState.lastGatedPkg = pkg
            GateState.drainEnterElapsedMs = 0L
        }
        if (cur.poolSec > 0) {
            if (GateState.drainEnterElapsedMs == 0L) {
                GateState.drainEnterElapsedMs = elapsed
                GateAlarms.schedulePoolExpiry(this, cur.poolSec * 1_000)
            }
            persist(cur)
            return
        }
        GateState.drainEnterElapsedMs = 0L
        GateAlarms.cancelPoolExpiry(this)
        persist(cur)
        startActivity(
            Intent(this, BlockActivity::class.java)
                .putExtra(BlockActivity.EXTRA_PACKAGE, pkg)
                .putExtra(BlockActivity.EXTRA_WAIT_SEC, PoolEngine.penaltySec(cur, cfg).toInt())
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
        )
    }

    /** Deduct the open drain segment for the previous app, if any. */
    private fun settleDrain() {
        val elapsed = SystemClock.elapsedRealtime()
        if (GateState.lastGatedPkg == null || GateState.drainEnterElapsedMs == 0L) return
        settleDrainLocked(GateState.poolState(), elapsed)
    }

    private fun settleDrainLocked(
        cur: com.abrai.zengate.policy.PoolState,
        elapsed: Long,
    ) {
        val spent = ((elapsed - GateState.drainEnterElapsedMs) / 1_000).coerceAtLeast(0)
        GateState.drainEnterElapsedMs = 0L
        GateAlarms.cancelPoolExpiry(this)
        if (spent > 0) {
            persist(PoolEngine.drain(cur, spent))
        }
    }

    private fun persist(state: com.abrai.zengate.policy.PoolState) {
        // Mirror converges via GateService collector; persist for durability.
        GateState.poolSec = state.poolSec
        GateState.usagesToday = state.usagesToday
        GateState.dayId = state.dayId
        GateState.lastRefillMs = state.lastRefillWallMs
        GateState.sessionStartMs = state.sessionStartWallMs
        GateState.sessionScreenOnMs = state.sessionScreenOnMs
        scope.launch { store.savePool(state) }
    }

    /** Keyboards are OS surfaces like SystemUI: never gated, resolved live (no hardcoding). */
    private fun enabledImes(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (cachedImes.isEmpty() || now - imeCacheAt > IME_CACHE_TTL_MS) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            cachedImes = imm.enabledInputMethodList.map { it.packageName }.toSet()
            imeCacheAt = now
        }
        return cachedImes
    }

    companion object {
        private const val TAG = "ZenGate"
        private const val IME_CACHE_TTL_MS = 60_000L
    }
}
