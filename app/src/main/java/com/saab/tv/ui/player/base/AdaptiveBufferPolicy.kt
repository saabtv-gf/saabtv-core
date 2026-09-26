package com.saab.tv.ui.player.base

internal data class AdaptiveBufferConfig(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val targetBufferBytes: Int,
    val backBufferMs: Int
)

/** Keeps startup quick while bounding memory on low-RAM TVs and large 4K streams. */
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
                minBufferMs = if (isTorrent) 6_000 else 8_000,
                maxBufferMs = if (isTorrent) 20_000 else 24_000,
                bufferForPlaybackMs = 750,
                bufferForPlaybackAfterRebufferMs = 2_000,
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
            bufferForPlaybackMs = if (isTorrent) 750 else 1_000,
            bufferForPlaybackAfterRebufferMs = if (isTorrent) 3_000 else 2_500,
            targetBufferBytes = targetBytes,
            backBufferMs = if (isTorrent) 3_000 else 5_000
        )
    }
}
