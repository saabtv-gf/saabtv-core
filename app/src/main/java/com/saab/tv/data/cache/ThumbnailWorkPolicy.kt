package com.saab.tv.data.cache

object ThumbnailWorkPolicy {
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
        moderateThermalStatus: Int
    ): Boolean = isPlaying && isReady && hasRenderedFirstFrame &&
        !isBuffering && !isSeeking && !hasPlaybackError && !playbackUnderStress &&
        thermalStatus < moderateThermalStatus &&
        (bufferIsUnknown || bufferedAheadMs >= minimumBufferedAheadMs)
}
