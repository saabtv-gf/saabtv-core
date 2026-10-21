package com.saab.tv.data.cache

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailWorkPolicyTest {
    @Test fun firstTenFramesStartImmediatelyOnHealthyPlaybackWithoutBufferHeadroom() {
        for (count in 0..10) {
            val minimum = ThumbnailWorkPolicy.bufferedAheadRequiredMs(count)
            val permitted = ThumbnailWorkPolicy.canRun(true, true, true, false, false,
                false, false, false, 500, minimum, 0, 3)
            assertEquals(count < 10, permitted)
            assertEquals(if (count < 10) 0L else 500L, ThumbnailWorkPolicy.recoveryDelayMs(count))
            assertFalse(ThumbnailWorkPolicy.canRun(true, false, true, true, false,
                false, false, false, 0, minimum, 0, 3))
        }
    }
    @Test
    fun runsWhenPlaybackIsHealthyAndBufferIsUnknown() {
        assertTrue(canRun(bufferUnknown = true))
    }

    @Test
    fun pausesForDecoderStressOrThermalPressure() {
        assertFalse(canRun(stress = true))
        assertFalse(canRun(thermal = 2))
    }

    @Test fun pausedReadyPlaybackCanCacheButBufferingAndLowHeadroomYieldToPlayback() {
        fun allowed(playing: Boolean = true, paused: Boolean = false, buffering: Boolean = false, ahead: Long = 6_000) =
            ThumbnailWorkPolicy.canRun(playing, true, true, buffering, false, false, false,
                paused, ahead, 5_000, 0, 2, isPaused = paused)
        assertTrue(allowed(playing = false, paused = true, ahead = 0))
        assertFalse(allowed(playing = false))
        assertFalse(allowed(buffering = true))
        assertFalse(allowed(ahead = 4_999))
        assertTrue(allowed(ahead = 5_000))
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
