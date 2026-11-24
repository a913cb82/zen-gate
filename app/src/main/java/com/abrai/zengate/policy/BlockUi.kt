package com.abrai.zengate.policy

/**
 * Block-screen render state. The big countdown is the long (primary) wait;
 * the quick pool button can become available earlier without moving it.
 */
data class BlockUi(
    val fullWaitSec: Long,
    val quickWaitSec: Long,
    val fullRemainingSec: Long,
    val quickRemainingSec: Long,
    val fullReady: Boolean,
    val quickReady: Boolean,
    val progress: Float,
    val usagesToday: Int,
    val fullLabel: String,
    val quickLabel: String,
) {
    val showQuick: Boolean get() = quickWaitSec > 0
}

object BlockUiFactory {
    fun from(
        waitSec: Long,
        quickWaitSec: Long,
        elapsedSec: Long,
        usagesToday: Int,
        sessionAllowSec: Long,
        unlockPoolSec: Long,
    ): BlockUi {
        val wait = waitSec.coerceAtLeast(1)
        val quick = quickWaitSec.coerceAtLeast(0)
        val elapsed = elapsedSec.coerceIn(0, wait)
        val fullRemaining = (wait - elapsed).coerceAtLeast(0)
        val quickRemaining = (quick - elapsed).coerceAtLeast(0)
        return BlockUi(
            fullWaitSec = wait,
            quickWaitSec = quick,
            fullRemainingSec = fullRemaining,
            quickRemainingSec = quickRemaining,
            fullReady = fullRemaining == 0L,
            quickReady = quickRemaining == 0L,
            progress = elapsed.toFloat() / wait.toFloat(),
            usagesToday = usagesToday,
            fullLabel = DurationFmt.openLabel(sessionAllowSec),
            quickLabel = DurationFmt.openLabel(unlockPoolSec),
        )
    }
}
