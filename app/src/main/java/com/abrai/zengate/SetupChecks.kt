package com.abrai.zengate

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

enum class CheckStatus { OK, MISSING, MANUAL }

/** One setup row. The gate only needs a few things; everything else is noise. */
data class SetupCheck(
    val id: String,
    val label: String,
    val status: CheckStatus,
    val detail: String,
)

object SetupChecks {
    const val ID_NOTIFICATIONS = "notifications"
    const val ID_BATTERY = "battery"
    const val ID_A11Y = "a11y"
    const val ID_USAGE = "usage"

    fun all(context: Context): List<SetupCheck> =
        listOf(
            SetupCheck(
                ID_NOTIFICATIONS,
                "Notifications",
                auto(hasNotifications(context)),
                "Shows the gate status and the pause button.",
            ),
            SetupCheck(
                ID_BATTERY,
                "Battery unrestricted",
                auto(isBatteryUnrestricted(context)),
                "Set Zen Gate to No restrictions so HyperOS keeps it alive.",
            ),
            SetupCheck(
                ID_A11Y,
                "Accessibility service",
                auto(isA11yEnabled(context)),
                "Enable Zen Gate in Accessibility. Greyed out? Allow restricted settings in App info first.",
            ),
            SetupCheck(
                ID_USAGE,
                "Usage access",
                auto(hasUsageAccess(context)),
                "Lets timers see the foreground app on HyperOS.",
            ),
        )

    fun missingAutoCount(context: Context): Int = all(context).count { it.status == CheckStatus.MISSING }

    private fun auto(ok: Boolean): CheckStatus = if (ok) CheckStatus.OK else CheckStatus.MISSING

    fun isA11yEnabled(context: Context): Boolean {
        val enabled =
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
        return enabled.contains(context.packageName)
    }

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    fun isBatteryUnrestricted(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun hasNotifications(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
