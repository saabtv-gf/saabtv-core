package com.saab.tv.ui.player.base

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerActionProgressPolicyTest {
    @Test fun countdownFillIsLinearForEveryFrameOfTheCountdown() {
        assertEquals(0f, PlayerActionProgressPolicy.linearFraction(0, 5_000), 0.001f)
        assertEquals(0.1f, PlayerActionProgressPolicy.linearFraction(500, 5_000), 0.001f)
        assertEquals(0.5f, PlayerActionProgressPolicy.linearFraction(2_500, 5_000), 0.001f)
        assertEquals(1f, PlayerActionProgressPolicy.linearFraction(5_000, 5_000), 0.001f)
        assertEquals(0f, PlayerActionProgressPolicy.linearFraction(10, 0), 0.001f)
    }

    @Test fun pauseAndResumeContinueFromTheCurrentFraction() {
        assertEquals(3_750, PlayerActionProgressPolicy.remainingDurationMillis(0.25f, 5))
        assertEquals(0, PlayerActionProgressPolicy.remainingDurationMillis(1f, 5))
        assertEquals(10_000, PlayerActionProgressPolicy.remainingDurationMillis(0f, 10))
    }
}
