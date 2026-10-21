package com.saab.tv.ui.player.base

/** Keeps the seek fill moving between the backend's one-second progress snapshots. */
internal object PlaybackProgressAnimationPolicy {
    const val BACKEND_SNAPSHOT_INTERVAL_MS = 1_000

    fun fraction(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return (positionMs.coerceIn(0L, durationMs).toDouble() / durationMs).toFloat()
    }

    fun interpolationDurationMs(isPlaybackAdvancing: Boolean): Int =
        if (isPlaybackAdvancing) BACKEND_SNAPSHOT_INTERVAL_MS else 0
}
