package com.saab.tv.data.cache

object ThumbnailWorkPolicy {
    /** Opening seek previews are useful immediately; do not wait for a large video buffer. */
    fun bufferedAheadRequiredMs(cachedFrames: Int): Long = if (cachedFrames < 10) 0L else 1_000L
    fun recoveryDelayMs(cachedFrames: Int): Long = if (cachedFrames < 10) 0L else 500L

    fun canRun(
        isPlaying: Boolean,
        isReady: Boolean,
        hasRenderedFirstFrame: Boolean,
        isBuffering: Boolean,
        isSeeking: Boolean,
        hasPlaybackError: Boolean,
        playbackUnderStress: Boolean,
        bufferIsUnknown: Boolean,
        bufferedAheadMs: Long,
        minimumBufferedAheadMs: Long,
        thermalStatus: Int,
        moderateThermalStatus: Int,
        isPaused: Boolean = false
    ): Boolean = (isPlaying || isPaused) && isReady && hasRenderedFirstFrame &&
        !isBuffering && !isSeeking && !hasPlaybackError && !playbackUnderStress &&
        thermalStatus < moderateThermalStatus &&
        (bufferIsUnknown || bufferedAheadMs >= minimumBufferedAheadMs)
}
