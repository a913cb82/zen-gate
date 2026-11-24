package com.abrai.zengate.policy

/** Compact duration text shared by the block screen and the app UI. */
object DurationFmt {
    /** mm:ss, rolling into h:mm:ss only when a duration really is that long. */
    fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val hours = s / 3_600
        val minutes = (s % 3_600) / 60
        val rest = s % 60
        return if (hours > 0) {
            "$hours:${minutes.toString().padStart(2, '0')}:${rest.toString().padStart(2, '0')}"
        } else {
            "${minutes.toString().padStart(2, '0')}:${rest.toString().padStart(2, '0')}"
        }
    }

    /** Right-hand value on an Open card: seconds until it would be noise. */
    fun waitValue(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return if (s >= 60) clock(s) else "${s}s"
    }

    /** Card title, e.g. "Open for 5 min" or "Open for 15 s". */
    fun openLabel(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val minutes = s / 60
        val rest = s % 60
        return when {
            minutes > 0 && rest > 0 -> "Open for $minutes min $rest s"
            minutes > 0 -> "Open for $minutes min"
            else -> "Open for $s s"
        }
    }
}
