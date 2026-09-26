package com.saab.tv.ui.player.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayNextEpisodePolicyTest {
    @Test
    fun usesFiveSecondCountdown() {
        assertEquals(5, AutoplayNextEpisodePolicy.COUNTDOWN_SECONDS)
    }

    @Test
    fun detectedOutroStartsAutoplayRegardlessOfFallbackThresholdMode() {
        assertTrue(
            AutoplayNextEpisodePolicy.shouldStartCountdown(
                positionMs = 2_400_000L,
                durationMs = 2_700_000L,
                outroStartMs = 2_350_000L,
                thresholdMode = "percentage",
                thresholdPercent = 95,
                thresholdSeconds = 30,
                playbackEnded = false
            )
        )
    }

    @Test
    fun naturalEndStartsCountdownEvenWithoutOutroMarker() {
        assertTrue(
            AutoplayNextEpisodePolicy.shouldStartCountdown(
                positionMs = 0L,
                durationMs = 0L,
                outroStartMs = null,
                thresholdMode = "introdb",
                thresholdPercent = 95,
                thresholdSeconds = 30,
                playbackEnded = true
            )
        )
    }

    @Test
    fun introDbModeWaitsWhenNoOutroHasBeenDetected() {
        assertFalse(
            AutoplayNextEpisodePolicy.shouldStartCountdown(
                positionMs = 2_400_000L,
                durationMs = 2_700_000L,
                outroStartMs = null,
                thresholdMode = "introdb",
                thresholdPercent = 95,
                thresholdSeconds = 30,
                playbackEnded = false
            )
        )
    }
}
