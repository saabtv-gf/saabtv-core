package com.saab.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressPolicyTest {
    @Test fun ninetyFivePercentIsTheNewCompletionBoundary() {
        assertFalse(WatchProgressPolicy.evaluate(900_000L, 1_000_000L, null, 0.95).isCompleted)
        assertTrue(WatchProgressPolicy.evaluate(950_000L, 1_000_000L, null, 0.95).isCompleted)
    }
    @Test
    fun unknownDurationRemainsInProgressAndCanResume() {
        val result = WatchProgressPolicy.evaluate(
            positionMs = 120_000L,
            reportedDurationMs = null,
            existingDurationMs = null,
            watchedThreshold = 0.85
        )

        assertEquals(0L, result.storedDurationMs)
        assertFalse(result.isCompleted)
        assertTrue(WatchProgressPolicy.canResume(120_000L, 0L, false, 0.85))
    }

    @Test
    fun knownDurationCompletesOnlyNearTheConfiguredThreshold() {
        val inProgress = WatchProgressPolicy.evaluate(600_000L, 1_200_000L, null, 0.85)
        val completed = WatchProgressPolicy.evaluate(1_100_000L, 1_200_000L, null, 0.85)

        assertFalse(inProgress.isCompleted)
        assertTrue(completed.isCompleted)
    }

    @Test
    fun shorterTransientDurationDoesNotOverwriteKnownMovieDuration() {
        val result = WatchProgressPolicy.evaluate(
            positionMs = 600_000L,
            reportedDurationMs = 700_000L,
            existingDurationMs = 1_200_000L,
            watchedThreshold = 0.85
        )

        assertEquals(1_200_000L, result.storedDurationMs)
        assertFalse(result.isCompleted)
    }

    @Test
    fun neverMarksContentWatchedBeforeFiveMinutes() {
        val nearEnd = WatchProgressPolicy.evaluate(
            positionMs = 299_000L,
            reportedDurationMs = 300_000L,
            existingDurationMs = null,
            watchedThreshold = 0.85,
            forceCompleted = true
        )

        assertFalse(nearEnd.isCompleted)
        assertTrue(
            WatchProgressPolicy.evaluate(300_000L, 310_000L, null, 0.85).isCompleted
        )
    }
}
