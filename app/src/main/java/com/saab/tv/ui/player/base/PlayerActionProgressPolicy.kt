package com.saab.tv.ui.player.base

internal object PlayerActionProgressPolicy {
    fun linearFraction(elapsedMs: Long, totalMs: Long): Float {
        if (totalMs <= 0L) return 0f
        return (elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f)
    }

    fun remainingDurationMillis(progress: Float, totalSeconds: Int): Int {
        val totalMs = totalSeconds.coerceAtLeast(0) * 1_000L
        return ((1f - progress.coerceIn(0f, 1f)) * totalMs).toInt()
    }
}
