package com.saab.tv.ui.player

import org.junit.Assert.*
import org.junit.Test

class WatchProgressBoundaryRegressionTest {
    @Test fun absentAndInvalidDurationsNeverInventCompletion() {
        for (reported in listOf<Long?>(null, -1, 0)) {
            for (stored in listOf<Long?>(null, -1, 0)) {
                val progress = WatchProgressPolicy.evaluate(300_000, reported, stored, .95)
                assertEquals(0L, progress.storedDurationMs)
                assertFalse(progress.isCompleted)
            }
        }
    }
    @Test fun eitherValidDurationIsRetainedAndTheLongerOneWins() {
        for (invalid in listOf<Long?>(null, -1, 0)) {
            assertEquals(1_200_000L, WatchProgressPolicy.evaluate(400_000, 1_200_000, invalid, .95).storedDurationMs)
            assertEquals(1_200_000L, WatchProgressPolicy.evaluate(400_000, invalid, 1_200_000, .95).storedDurationMs)
        }
        assertEquals(1_200_000L, WatchProgressPolicy.evaluate(400_000, 1_200_000, 1_000_000, .95).storedDurationMs)
        assertEquals(1_200_000L, WatchProgressPolicy.evaluate(400_000, 1_000_000, 1_200_000, .95).storedDurationMs)
        assertEquals(1_300_000L, WatchProgressPolicy.evaluate(1_300_000, 1_200_000, null, .95).storedDurationMs)
    }
    @Test fun completionCannotBypassMinimumWatchTime() {
        for (position in listOf(-1L, 0L, 299_999L)) {
            for (force in listOf(false, true)) {
                assertFalse(WatchProgressPolicy.evaluate(position, 300_000, null, .95, force).isCompleted)
            }
        }
        assertTrue(WatchProgressPolicy.evaluate(300_000, null, null, .95, true).isCompleted)
        assertFalse(WatchProgressPolicy.evaluate(300_000, 1_200_000, null, .95).isCompleted)
    }
    @Test fun exactThresholdAndEndGraceAreIndependentBoundaries() {
        assertFalse(WatchProgressPolicy.evaluate(949_999, 1_000_000, null, .95).isCompleted)
        assertTrue(WatchProgressPolicy.evaluate(950_000, 1_000_000, null, .95).isCompleted)
        // At a 99% threshold, the established 30-second end grace still applies.
        assertFalse(WatchProgressPolicy.evaluate(969_999, 1_000_000, null, .99).isCompleted)
        assertTrue(WatchProgressPolicy.evaluate(970_000, 1_000_000, null, .99).isCompleted)
    }
    @Test fun allWatchedStateCombinationsAreSticky() {
        assertFalse(WatchProgressPolicy.preserveWatched(false, false))
        assertTrue(WatchProgressPolicy.preserveWatched(false, true))
        assertTrue(WatchProgressPolicy.preserveWatched(true, false))
        assertTrue(WatchProgressPolicy.preserveWatched(true, true))
    }
    @Test fun resumeRejectsWatchedZeroNegativeAndCompletedPositions() {
        assertFalse(WatchProgressPolicy.canResume(100, 0, true, .95))
        assertFalse(WatchProgressPolicy.canResume(0, 0, false, .95))
        assertFalse(WatchProgressPolicy.canResume(-1, 0, false, .95))
        assertTrue(WatchProgressPolicy.canResume(1, 0, false, .95))
        assertTrue(WatchProgressPolicy.canResume(1, -1, false, .95))
        assertTrue(WatchProgressPolicy.canResume(949_999, 1_000_000, false, .95))
        assertFalse(WatchProgressPolicy.canResume(950_000, 1_000_000, false, .95))
        assertTrue(WatchProgressPolicy.canResume(969_999, 1_000_000, false, .99))
        assertFalse(WatchProgressPolicy.canResume(970_000, 1_000_000, false, .99))
    }
}
