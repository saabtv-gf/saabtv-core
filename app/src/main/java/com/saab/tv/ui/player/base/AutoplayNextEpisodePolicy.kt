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
        // Never advance automatically from heuristic percentage/time thresholds,
        // including natural end when IntroDB has no valid outro marker.
        return outroStartMs != null && outroStartMs > 0 && outroStartMs < durationMs &&
            (playbackEnded || positionMs >= outroStartMs)
    }

    fun shouldOfferNextEpisode(
        positionMs: Long,
        durationMs: Long,
        outroStartMs: Long?,
        thresholdMode: String,
        thresholdPercent: Int,
        thresholdSeconds: Int,
        playbackEnded: Boolean,
        smartPromptMs: Long? = null
    ): Boolean {
        if (playbackEnded) return true
        if (durationMs <= 0L) return false
        // Percentage/time are fallbacks, never override an actual outro marker.
        if (outroStartMs != null && outroStartMs > 0L && outroStartMs < durationMs) {
            return positionMs >= outroStartMs
        }

        return when (thresholdMode) {
            "introdb" -> false
            "smart" -> smartPromptMs != null && smartPromptMs >= 0L && positionMs >= smartPromptMs
            "time" -> durationMs - positionMs <= thresholdSeconds.coerceAtLeast(0) * 1_000L
            else -> positionMs.toDouble() / durationMs.toDouble() >=
                thresholdPercent.coerceIn(0, 100) / 100.0
        }
    }
}
