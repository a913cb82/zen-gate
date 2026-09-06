package com.abrai.zengate.policy

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log

/**
 * Live-foreground oracle via usage events (system truth: independent of the
 * accessibility window introspection that HyperOS blinds, and of FLAG_SECURE).
 * The fold is pure and unit-tested; only [queryForeground] touches Android,
 * and only at alarm deadlines (never polled).
 */
object UsageOracle {
    /** Minimal transition for the pure fold. */
    data class Transition(
        val foreground: Boolean,
        val pkg: String,
    )

    fun fg(pkg: String): Transition = Transition(true, pkg)

    fun bg(pkg: String): Transition = Transition(false, pkg)

    /** Last foreground package still showing, or null when unknown/empty. */
    fun pickForeground(events: List<Transition>): String? {
        var fg: String? = null
        for (e in events) {
            if (e.foreground) {
                fg = e.pkg
            } else if (fg == e.pkg) {
                fg = null
            }
        }
        return fg
    }

    /** Windowed query: who is foreground right now, null when unknowable. */
    fun queryForeground(
        context: Context,
        windowMs: Long = 15_000,
    ): String? =
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - windowMs, now)
            val list = ArrayList<Transition>()
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                when (e.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> list.add(fg(e.packageName.orEmpty()))
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> list.add(bg(e.packageName.orEmpty()))
                }
            }
            pickForeground(list).also {
                Log.d(TAG, "usage oracle foreground=$it transitions=${list.size}")
            }
        } catch (t: Throwable) {
            Log.d(TAG, "usage oracle failed; treating as unknown")
            null
        }

    private const val TAG = "ZenGate"
}
