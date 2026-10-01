package com.saab.tv.ui.player.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayNextEpisodePolicyTest {
    @Test
    fun percentageFallbackDoesNotSkipBeforeKnownOutro() {
        assertFalse(AutoplayNextEpisodePolicy.shouldOfferNextEpisode(
            970_000, 1_000_000, 990_000, "percentage", 95, 30, false))
    }

    @Test
    fun percentageFallbackRunsWithoutOutro() {
        assertTrue(AutoplayNextEpisodePolicy.shouldOfferNextEpisode(
            950_000, 1_000_000, null, "percentage", 95, 30, false))
        assertFalse(AutoplayNextEpisodePolicy.shouldOfferNextEpisode(
            940_000, 1_000_000, null, "percentage", 95, 30, false))
    }

    @Test
    fun timeFallbackRunsOnlyWithinRemainingWindow() {
        assertTrue(AutoplayNextEpisodePolicy.shouldOfferNextEpisode(
            970_000, 1_000_000, null, "time", 95, 30, false))
        assertFalse(AutoplayNextEpisodePolicy.shouldOfferNextEpisode(
            960_000, 1_000_000, null, "time", 95, 30, false))
    }

    @Test
    fun invalidOutroAllowsFallback() {
        assertTrue(AutoplayNextEpisodePolicy.shouldOfferNextEpisode(
            970_000, 1_000_000, 2_000_000, "time", 95, 30, false))
    }
    @Test
    fun usesFiveSecondCountdown() {
        assertEquals(5, AutoplayNextEpisodePolicy.COUNTDOWN_SECONDS)
    }

    @Test
    fun detectedOutroStartsAutoplayRegardlessOfFallbackThresholdMode() {
        assertTrue(
            AutoplayNextEpisodePolicy.shouldOfferNextEpisode(
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
            AutoplayNextEpisodePolicy.shouldOfferNextEpisode(
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
            AutoplayNextEpisodePolicy.shouldOfferNextEpisode(
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
    @Test fun fallbackThresholdsNeverStartAutomaticCountdown() {
        assertFalse(AutoplayNextEpisodePolicy.shouldStartCountdown(
            999_000, 1_000_000, null, "percentage", 95, 30, false))
        assertFalse(AutoplayNextEpisodePolicy.shouldStartCountdown(
            999_000, 1_000_000, null, "time", 95, 30, false))
        assertFalse(AutoplayNextEpisodePolicy.shouldStartCountdown(
            1_000_000, 1_000_000, null, "introdb", 95, 30, true))
        assertFalse(AutoplayNextEpisodePolicy.shouldStartCountdown(
            999_000, 1_000_000, 2_000_000, "time", 95, 30, false))
    }
    @Test fun onlyValidIntroDbOutroStartsAutomaticCountdown() {
        assertTrue(AutoplayNextEpisodePolicy.shouldStartCountdown(
            995_000, 1_000_000, 990_000, "percentage", 95, 30, false))
        assertFalse(AutoplayNextEpisodePolicy.shouldStartCountdown(
            980_000, 1_000_000, 990_000, "time", 95, 30, false))
        assertTrue(AutoplayNextEpisodePolicy.shouldStartCountdown(
            1_000_000, 1_000_000, 990_000, "introdb", 95, 30, true))
    }

}
