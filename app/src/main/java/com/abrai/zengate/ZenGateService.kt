package com.abrai.zengate

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.abrai.zengate.policy.GatePolicy

/** M1 skeleton: log + toast on gated apps. Real block screen lands in M2. */
class ZenGateService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString().orEmpty()
        Log.d(TAG, "foreground=$pkg gated=${GatePolicy.isGated(pkg, packageName)}")
        if (GatePolicy.isGated(pkg, packageName)) {
            Toast.makeText(this, "ZenGate M1: $pkg gated", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInterrupt() = Unit

    companion object {
        private const val TAG = "ZenGate"
    }
}
