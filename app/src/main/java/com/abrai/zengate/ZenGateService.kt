package com.abrai.zengate

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.abrai.zengate.policy.GatePolicy

/** M1 skeleton: log + toast on gated apps. Real block screen lands in M2. */
class ZenGateService : AccessibilityService() {
    private var cachedImes: Set<String> = emptySet()
    private var imeCacheAt: Long = 0
    private var lastToastPkg: String? = null
    private var lastToastAt: Long = 0

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // TEMP DIAGNOSTIC (M1): log every event type to bisect HyperOS dispatch.
        Log.d(TAG, "raw type=${event?.eventType} pkg=${event?.packageName}")
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        try {
            val pkg = event.packageName?.toString().orEmpty()
            val gated = GatePolicy.isGated(pkg, packageName, enabledImes())
            Log.d(TAG, "foreground=$pkg gated=$gated")
            if (!gated) return
            // Collapse toast bursts from multi-window transitions (M2 adds a real isShowing guard).
            val now = SystemClock.elapsedRealtime()
            if (pkg == lastToastPkg && now - lastToastAt < TOAST_DEBOUNCE_MS) return
            lastToastPkg = pkg
            lastToastAt = now
            Toast.makeText(this, "ZenGate M1: $pkg gated", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            // A gate must never die: log and survive (HyperOS intercepts toasts for sideloaded apps).
            Log.e(TAG, "event handling failed", t)
        }
    }

    override fun onServiceConnected() {
        Log.d(TAG, "connected; enabledImes=${enabledImes()}")
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
        private const val TOAST_DEBOUNCE_MS = 2000L
        private const val IME_CACHE_TTL_MS = 60_000L
    }
}
