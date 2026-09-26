package com.saab.tv.ui.player.base

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackBufferRecoveryPolicyTest {
    @Test
    fun recoversOnlyAfterSustainedBufferingWithoutProgress() {
        val policy = PlaybackBufferRecoveryPolicy(
            stallThresholdMs = 12_000L,
            recoveryCooldownMs = 30_000L,
            meaningfulMovementMs = 750L
        )

        assertFalse(policy.shouldRecover(1_000L, 60_000L, true, true))
        assertFalse(policy.shouldRecover(12_000L, 60_000L, true, true))
        assertTrue(policy.shouldRecover(13_000L, 60_000L, true, true))
    }

    @Test
    fun playbackMovementAndPausePreventFalseRecovery() {
        val policy = PlaybackBufferRecoveryPolicy(stallThresholdMs = 5_000L)

        assertFalse(policy.shouldRecover(1_000L, 10_000L, true, true))
        assertFalse(policy.shouldRecover(5_000L, 11_000L, true, true))
        assertFalse(policy.shouldRecover(11_000L, 11_000L, true, false))
    }
}
