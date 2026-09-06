package com.abrai.zengate

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.abrai.zengate.policy.GatePolicy

/** M2: gated apps hit the opaque block; session/kill-switch state comes from [GateState]. */
class ZenGateService : AccessibilityService() {
    private var cachedImes: Set<String> = emptySet()
    private var imeCacheAt: Long = 0

    override fun onServiceConnected() {
        Log.d(TAG, "connected; enabledImes=${enabledImes()}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        try {
            val pkg = event.packageName?.toString().orEmpty()
            val gated = GatePolicy.isGated(pkg, packageName, enabledImes())
            Log.d(TAG, "foreground=$pkg gated=$gated")
            if (!gated || !GateState.enabled) return
            if (GatePolicy.isSessionActive(System.currentTimeMillis(), GateState.sessionExpiryMs)) return
            val now = SystemClock.elapsedRealtime()
            if (pkg == GateState.ignorePkg && now < GateState.ignoreUntilElapsedMs) return
            if (BlockActivity.isShowing) return
            startActivity(
                Intent(this, BlockActivity::class.java)
                    .putExtra(BlockActivity.EXTRA_PACKAGE, pkg)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
            )
        } catch (t: Throwable) {
            // A gate must never die: log and survive.
            Log.e(TAG, "event handling failed", t)
        }
    }

    override fun onInterrupt() = Unit

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
