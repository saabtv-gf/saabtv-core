package com.saab.tv.data.cache

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailWorkPolicyTest {
    @Test
    fun runsWhenPlaybackIsHealthyAndBufferIsUnknown() {
        assertTrue(canRun(bufferUnknown = true))
    }

    @Test
    fun pausesForDecoderStressOrThermalPressure() {
        assertFalse(canRun(stress = true))
        assertFalse(canRun(thermal = 2))
    }

    private fun canRun(
        bufferUnknown: Boolean = false,
        stress: Boolean = false,
        thermal: Int = 0
    ) = ThumbnailWorkPolicy.canRun(
        isPlaying = true,
        isReady = true,
        hasRenderedFirstFrame = true,
        isBuffering = false,
        isSeeking = false,
        hasPlaybackError = false,
        playbackUnderStress = stress,
        bufferIsUnknown = bufferUnknown,
        bufferedAheadMs = 20_000,
        minimumBufferedAheadMs = 15_000,
        thermalStatus = thermal,
        moderateThermalStatus = 2
    )
}
