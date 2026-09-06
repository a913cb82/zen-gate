package com.abrai.zengate

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.abrai.zengate.policy.DeadlineVerdict
import com.abrai.zengate.policy.GatePolicy
import com.abrai.zengate.policy.PoolEngine
import com.abrai.zengate.policy.ZenConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
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
    private var cachedLaunchers: Set<String> = emptySet()
    private var launcherCacheAt: Long = 0

    /** Alarm-time launches decide on the LIVE active window (never sticky state). */
    private val deadlineReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    GateAlarms.ACTION_POOL_EXPIRED, GateAlarms.ACTION_SESSION_END -> onDeadline()
                }
            }
        }

    override fun onServiceConnected() {
        store = GateStore(this)
        try {
            registerReceiver(
                deadlineReceiver,
                IntentFilter(GateAlarms.ACTION_POOL_EXPIRED).apply {
                    addAction(GateAlarms.ACTION_SESSION_END)
                },
                RECEIVER_NOT_EXPORTED,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "deadline receiver register failed", t)
        }
        // The mirror only loads while GateService runs: guarantee it here so the
        // gate can never idle on a cold mirror (M3 lesson). MainActivity + boot
        // cover the remaining paths; failures are logged, never fatal.
        try {
            startForegroundService(Intent(this, GateService::class.java))
            Log.d(TAG, "foreground presence ensured from accessibility bind")
        } catch (t: Throwable) {
            Log.e(TAG, "presence ensure failed", t)
        }
        Log.d(TAG, "connected; enabledImes=${imePackages()}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        try {
            val pkg = event.packageName?.toString().orEmpty()
            val gated = GatePolicy.isGated(pkg, packageName, imePackages(), GateState.userWhitelist)
            Log.d(TAG, "foreground=$pkg gated=$gated")
            if (!gated) {
                settleDrain(keepDraining = true)
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
        val cfg = GateState.config
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        if (!GateState.storeLoaded) {
            Log.d(TAG, "mirror cold; skipping event for $pkg")
            return
        }
        var cur = GateState.poolState()
        val today = PoolEngine.dayIdFor(wall, cfg.resetHour, cfg.resetMinute)
        if (cur.dayId.isEmpty()) {
            cur = cur.copy(dayId = today)
        } else if (PoolEngine.needsMidnightReset(cur, today)) {
            cur = PoolEngine.midnightReset(today, wall, cfg)
        }
        if (!GateState.enabled) {
            persist(cur)
            return
        }
        if (PoolEngine.hasSession(cur)) {
            // Event-driven expiry (alarm is the backstop for the no-events case).
            // Expiry tops up grace via endSession: the pool drains before any block.
            if (PoolEngine.sessionRemainingMs(cur, wall, cfg) <= 0) {
                cur = PoolEngine.endSession(cur)
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
        Log.d(
            TAG,
            "verdict pkg=$pkg pool=${cur.poolSec} usages=${cur.usagesToday} " +
                "launcher=${pkg in launcherPkgs()} whitelist=${pkg in GateState.userWhitelist}",
        )
        if (pkg in launcherPkgs()) {
            Log.d(TAG, "launcher entry pkg=$pkg pool=${cur.poolSec}")
        }
        if (PoolEngine.enterVerdict(cur.poolSec) == PoolEngine.EnterVerdict.DRAIN) {
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
        launchBlock(pkg, cur, cfg)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        try {
            unregisterReceiver(deadlineReceiver)
        } catch (t: Throwable) {
            Log.e(TAG, "deadline receiver unregister failed", t)
        }
        return super.onUnbind(intent)
    }

    private fun onDeadline() {
        scope.launch {
            try {
                // Store-direct: the mirror may trail the manifest receiver's write.
                val snap = store.snapshot.first().poolState()
                val cfg = store.config.first()
                val enabled = store.enabled.first()
                val list = store.whitelist.first()
                val wall = System.currentTimeMillis()
                val root =
                    try {
                        rootInActiveWindow?.packageName?.toString()
                    } catch (t: Throwable) {
                        null
                    }
                val power = getSystemService(Context.POWER_SERVICE) as PowerManager
                val keys = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                val remaining = PoolEngine.sessionRemainingMs(snap, wall, cfg)
                when (
                    val v =
                        DeadlineVerdict.decide(
                            enabled,
                            remaining,
                            root,
                            GateState.lastGatedPkg,
                            packageName,
                            list,
                            keys.isKeyguardLocked,
                            power.isInteractive,
                        )
                ) {
                    is DeadlineVerdict.Outcome.Launch -> {
                        Log.d(TAG, "deadline launch pkg=${v.pkg} root=$root")
                        launchBlock(v.pkg, snap, cfg)
                    }
                    DeadlineVerdict.Outcome.Skip -> Log.d(TAG, "deadline skipped root=$root")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "deadline decision failed", t)
            }
        }
    }

    private fun launchBlock(
        pkg: String,
        cur: com.abrai.zengate.policy.PoolState,
        cfg: ZenConfig,
    ) {
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
    private fun settleDrain(keepDraining: Boolean = false) {
        if (!GateState.storeLoaded) return
        if (GateState.lastGatedPkg == null || GateState.drainEnterElapsedMs == 0L) return
        val elapsed = SystemClock.elapsedRealtime()
        settleDrainLocked(GateState.poolState(), elapsed)
        // Non-gated surfaces (whitelist, system UI) must not freeze grace: the
        // wall-clock deduction above counts their time, and the remainder keeps
        // draining so the block still arrives.
        if (!keepDraining) return
        val cur = GateState.poolState()
        if (cur.poolSec > 0 && !PoolEngine.hasSession(cur)) {
            GateState.drainEnterElapsedMs = elapsed
            GateAlarms.schedulePoolExpiry(this, cur.poolSec * 1_000)
        }
    }

    private fun settleDrainLocked(
        cur: com.abrai.zengate.policy.PoolState,
        elapsed: Long,
    ) {
        // No open segment (fresh grant, post-block, launcher transit): nothing owed.
        if (GateState.drainEnterElapsedMs == 0L) {
            GateAlarms.cancelPoolExpiry(this)
            return
        }
        val spent = ((elapsed - GateState.drainEnterElapsedMs) / 1_000).coerceAtLeast(0)
        GateState.drainEnterElapsedMs = 0L
        GateAlarms.cancelPoolExpiry(this)
        if (spent > 0) {
            persist(PoolEngine.drain(cur, spent))
        }
    }

    private fun persist(state: com.abrai.zengate.policy.PoolState) {
        // Mirror converges via GateService collector; persist for durability.
        GateState.applyPool(state)
        scope.launch { store.savePool(state) }
    }

    /** Keyboards are OS surfaces like SystemUI: never gated, resolved live (no hardcoding). */
    private fun imePackages(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (cachedImes.isEmpty() || now - imeCacheAt > IME_CACHE_TTL_MS) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val enabled = imm.enabledInputMethodList.map { it.packageName }.toSet()
            val declared =
                packageManager
                    .queryIntentServices(
                        android.content.Intent(android.view.inputmethod.InputMethod.SERVICE_INTERFACE),
                        0,
                    ).map { it.serviceInfo.packageName }
                    .toSet()
            cachedImes = enabled + declared
            imeCacheAt = now
        }
        return cachedImes
    }

    /** Home-screen packages: transit surfaces, resolved live (no hardcoding). */
    private fun launcherPkgs(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (cachedLaunchers.isEmpty() || now - launcherCacheAt > IME_CACHE_TTL_MS) {
            val home =
                android.content.Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            cachedLaunchers =
                packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName }.toSet()
            launcherCacheAt = now
        }
        return cachedLaunchers
    }

    companion object {
        private const val TAG = "ZenGate"
        private const val IME_CACHE_TTL_MS = 60_000L
    }
}
