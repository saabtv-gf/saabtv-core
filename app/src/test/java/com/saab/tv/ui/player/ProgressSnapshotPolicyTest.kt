package com.saab.tv.ui.player

import org.junit.Assert.*
import org.junit.Test

class ProgressSnapshotPolicyTest {
    @Test fun boundariesSaveEvenTheFirstFrame() {
        for (position in listOf(0L, 1L, 999L, 4_999L, 5_000L, 90_000L)) {
            assertTrue(ProgressSnapshotPolicy.shouldSave(position, true, true))
        }
    }
    @Test fun unstartedBackendNeverOverwritesResumePosition() {
        for (boundary in listOf(false, true)) {
            for (position in listOf(0L, 4_999L, 5_000L, 90_000L)) {
                assertFalse(ProgressSnapshotPolicy.shouldSave(position, boundary, false))
            }
        }
    }
    @Test fun periodicWritesRetainTheFiveSecondNoiseFilter() {
        assertFalse(ProgressSnapshotPolicy.shouldSave(0L, false, true))
        assertFalse(ProgressSnapshotPolicy.shouldSave(4_999L, false, true))
        assertTrue(ProgressSnapshotPolicy.shouldSave(5_000L, false, true))
    }
    @Test fun naturalEndStoresTheEndEvenWhenFinalPositionTickIsStale() {
        assertEquals(1_800_000L, ProgressSnapshotPolicy.terminalPosition(1_790_000L, 1_800_000L))
        assertEquals(1_800_010L, ProgressSnapshotPolicy.terminalPosition(1_800_010L, 1_800_000L))
        assertEquals(0L, ProgressSnapshotPolicy.terminalPosition(-1L, 0L))
    }
    @Test fun savingAnEpisodeStartDoesNotMarkItWatched() {
        assertTrue(ProgressSnapshotPolicy.shouldSave(0L, true, true))
        assertFalse(WatchProgressPolicy.evaluate(0L, 1_800_000L, null, .95).isCompleted)
    }
    @Test fun lastEpisodeEndIsWatchedAndNoLongerResumable() {
        val position = ProgressSnapshotPolicy.terminalPosition(1_790_000L, 1_800_000L)
        val progress = WatchProgressPolicy.evaluate(position, 1_800_000L, null, .95, true)
        assertTrue(progress.isCompleted)
        assertFalse(WatchProgressPolicy.canResume(position, progress.storedDurationMs, progress.isCompleted, .95))
    }
}
