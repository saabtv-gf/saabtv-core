package com.saab.tv.ui.player.base

internal data class AdaptiveBufferConfig(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val targetBufferBytes: Int,
    val backBufferMs: Int
)

/** Keeps a larger rebuffer cushion on low-RAM TVs without raising their allocation cap. */
internal object AdaptiveBufferPolicy {
    fun choose(
        isTorrent: Boolean,
        memoryClassMb: Int,
        isLowRamDevice: Boolean,
        qualityHeight: Int?
    ): AdaptiveBufferConfig {
        val lowMemory = isLowRamDevice || memoryClassMb <= 256
        val highMemory = memoryClassMb >= 768
        val is4k = (qualityHeight ?: 0) >= 2160

        if (lowMemory) {
            return AdaptiveBufferConfig(
                minBufferMs = if (isTorrent) 12_000 else 16_000,
                maxBufferMs = if (isTorrent) 35_000 else 45_000,
                bufferForPlaybackMs = if (isTorrent) 1_500 else 1_000,
                bufferForPlaybackAfterRebufferMs = 7_000,
                targetBufferBytes = 48 * 1024 * 1024,
                backBufferMs = 1_500
            )
        }

        val targetBytes = if (highMemory && is4k) 96 * 1024 * 1024 else 72 * 1024 * 1024
        return AdaptiveBufferConfig(
            minBufferMs = if (isTorrent) 8_000 else 10_000,
            maxBufferMs = when {
                highMemory && is4k -> 42_000
                isTorrent -> 30_000
                else -> 35_000
            },
            bufferForPlaybackMs = if (isTorrent) 500 else 250,
            bufferForPlaybackAfterRebufferMs = if (isTorrent) 3_000 else 2_500,
            targetBufferBytes = targetBytes,
            backBufferMs = if (isTorrent) 3_000 else 5_000
        )
    }
}
