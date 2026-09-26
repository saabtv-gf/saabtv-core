package com.saab.tv.ui.player.base

internal object AutoplayNextEpisodePolicy {
    const val COUNTDOWN_SECONDS = 5

    fun shouldStartCountdown(
        positionMs: Long,
        durationMs: Long,
        outroStartMs: Long?,
        thresholdMode: String,
        thresholdPercent: Int,
        thresholdSeconds: Int,
        playbackEnded: Boolean
    ): Boolean {
        if (playbackEnded) return true
        if (durationMs <= 0L) return false
        if (outroStartMs != null && outroStartMs > 0L && positionMs >= outroStartMs) return true

        return when (thresholdMode) {
            "introdb" -> false
            "time" -> durationMs - positionMs <= thresholdSeconds.coerceAtLeast(0) * 1_000L
            else -> positionMs.toDouble() / durationMs.toDouble() >=
                thresholdPercent.coerceIn(0, 100) / 100.0
        }
    }
}
