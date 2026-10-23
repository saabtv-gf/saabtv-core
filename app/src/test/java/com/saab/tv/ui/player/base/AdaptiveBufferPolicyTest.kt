package com.saab.tv.ui.player.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBufferPolicyTest {
    @Test
    fun lowRamPlaybackWaitsForAUsefulInitialCushionAndRebufferRecovery() {
        val direct = AdaptiveBufferPolicy.choose(false, 128, true, 1080)
        val torrent = AdaptiveBufferPolicy.choose(true, 128, true, 1080)
        assertEquals(1_000, direct.bufferForPlaybackMs)
        assertEquals(1_500, torrent.bufferForPlaybackMs)
        assertEquals(16_000, direct.minBufferMs)
        assertEquals(12_000, torrent.minBufferMs)
        assertEquals(7_000, direct.bufferForPlaybackAfterRebufferMs)
        assertEquals(7_000, torrent.bufferForPlaybackAfterRebufferMs)
    }

    @Test
    fun higherMemoryPlaybackKeepsFastStartup() {
        for (memory in listOf(512, 1024)) {
            val direct = AdaptiveBufferPolicy.choose(false, memory, memory <= 256, 2160)
            val torrent = AdaptiveBufferPolicy.choose(true, memory, memory <= 256, 2160)
            assertEquals(250, direct.bufferForPlaybackMs)
            assertEquals(500, torrent.bufferForPlaybackMs)
            assertTrue(direct.bufferForPlaybackAfterRebufferMs >= 2_000)
            assertTrue(torrent.bufferForPlaybackAfterRebufferMs >= 2_000)
        }
    }

    @Test
    fun lowRamTvUsesStrictMemoryCap() {
        val config = AdaptiveBufferPolicy.choose(true, 256, true, 2160)
        assertEquals(48 * 1024 * 1024, config.targetBufferBytes)
        assertEquals(35_000, config.maxBufferMs)
        assertEquals(12_000, config.minBufferMs)
        assertEquals(7_000, config.bufferForPlaybackAfterRebufferMs)
        assertTrue(config.backBufferMs <= 1_500)
    }

    @Test
    fun highMemory4kTvGetsLargerForwardBuffer() {
        val config = AdaptiveBufferPolicy.choose(false, 1024, false, 2160)
        assertEquals(96 * 1024 * 1024, config.targetBufferBytes)
        assertEquals(42_000, config.maxBufferMs)
    }
}
