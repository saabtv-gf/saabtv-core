package com.saab.tv.ui.player.base

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoJitterRecoveryPolicyTest {
    @Test
    fun recoversOnlyForSustainedDropsAfterWarmup() {
        val policy = VideoJitterRecoveryPolicy(
            warmupMs = 10_000L,
            windowMs = 4_000L,
            droppedFrameThreshold = 12,
            recoveryCooldownMs = 30_000L
        )

        assertFalse(policy.shouldRecover(5_000L, 1_000L, 20, eligible = true))
        assertFalse(policy.shouldRecover(12_000L, 1_000L, 5, eligible = true))
        assertTrue(policy.shouldRecover(13_000L, 1_000L, 7, eligible = true))
    }

    @Test
    fun cooldownPreventsRepeatedRendererFlushes() {
        val policy = VideoJitterRecoveryPolicy(
            warmupMs = 0L,
            windowMs = 4_000L,
            droppedFrameThreshold = 10,
            recoveryCooldownMs = 30_000L
        )

        assertTrue(policy.shouldRecover(10_000L, 0L, 10, eligible = true))
        assertFalse(policy.shouldRecover(20_000L, 0L, 20, eligible = true))
        assertTrue(policy.shouldRecover(41_000L, 0L, 10, eligible = true))
    }

    @Test
    fun ineligiblePlaybackResetsDropWindow() {
        val policy = VideoJitterRecoveryPolicy(
            warmupMs = 0L,
            windowMs = 4_000L,
            droppedFrameThreshold = 10,
            recoveryCooldownMs = 30_000L
        )

        assertFalse(policy.shouldRecover(1_000L, 0L, 7, eligible = true))
        assertFalse(policy.shouldRecover(2_000L, 0L, 7, eligible = false))
        assertFalse(policy.shouldRecover(3_000L, 0L, 7, eligible = true))
    }
}
