package com.abrai.zengate

import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

enum class CheckStatus { OK, MISSING, MANUAL }

/** One onboarding checklist row. MANUAL rows can't be verified; they show how-to text. */
data class SetupCheck(
    val id: String,
    val label: String,
    val status: CheckStatus,
    val detail: String,
)

/** Synchronous status reads (binder calls; cheap enough for composition-time use). */
object SetupChecks {
    const val ID_A11Y = "a11y"
    const val ID_USAGE = "usage"
    const val ID_BATTERY = "battery"
    const val ID_AUTOSTART = "autostart"
    const val ID_RESTRICTED = "restricted"
    const val ID_NOTIFICATIONS = "notifications"
    const val ID_EXACT = "exact"

    fun all(context: Context): List<SetupCheck> =
        listOf(
            SetupCheck(
                ID_RESTRICTED,
                "Allow restricted settings",
                CheckStatus.MANUAL,
                "Sideloaded apps only: open Zen Gate's App info, tap ⋮, Allow restricted settings.",
            ),
            SetupCheck(
                ID_A11Y,
                "Accessibility service",
                auto(isA11yEnabled(context)),
                "Lets the gate see which app is open. No screen content is ever read.",
            ),
            SetupCheck(
                ID_USAGE,
                "Usage access",
                auto(hasUsageAccess(context)),
                "Backup detector if the accessibility feed ever stalls.",
            ),
            SetupCheck(
                ID_BATTERY,
                "Battery unrestricted",
                auto(isBatteryUnrestricted(context)),
                "Without this HyperOS starves the gate (proven day-one lesson).",
            ),
            SetupCheck(
                ID_AUTOSTART,
                "Autostart allowed",
                CheckStatus.MANUAL,
                "Settings → Apps → Zen Gate → Autostart → allow. Needed for reboot survival.",
            ),
            SetupCheck(
                ID_NOTIFICATIONS,
                "Notifications",
                auto(hasNotifications(context)),
                "Shows the persistent gate notification and kill switch.",
            ),
            SetupCheck(
                ID_EXACT,
                "Exact alarms",
                auto(canExactAlarms(context)),
                "Fires session/midnight timers on time.",
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

    fun canExactAlarms(context: Context): Boolean {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarms.canScheduleExactAlarms()
    }
}
