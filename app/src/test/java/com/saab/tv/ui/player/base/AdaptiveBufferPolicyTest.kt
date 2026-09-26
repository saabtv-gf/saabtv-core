package com.saab.tv.ui.player.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBufferPolicyTest {
    @Test
    fun lowRamTvUsesStrictMemoryCap() {
        val config = AdaptiveBufferPolicy.choose(true, 256, true, 2160)
        assertEquals(48 * 1024 * 1024, config.targetBufferBytes)
        assertEquals(20_000, config.maxBufferMs)
        assertTrue(config.backBufferMs <= 1_500)
    }

    @Test
    fun highMemory4kTvGetsLargerForwardBuffer() {
        val config = AdaptiveBufferPolicy.choose(false, 1024, false, 2160)
        assertEquals(96 * 1024 * 1024, config.targetBufferBytes)
        assertEquals(42_000, config.maxBufferMs)
    }
}
