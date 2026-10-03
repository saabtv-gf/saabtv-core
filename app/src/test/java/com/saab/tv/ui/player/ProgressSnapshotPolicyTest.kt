package com.saab.tv.ui.player

import com.saab.tv.ui.player.base.PlayerUiState
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
    @Test fun lifecycleStopPreservesPreparedEpisodeStartAndOmitsUnknownDuration() {
        val snapshot = ProgressSnapshotPolicy.onLifecycleStop(
            PlayerUiState(isReady = true, positionMs = 0L, durationMs = 0L)
        )
        assertEquals(0L, snapshot.positionMs)
        assertNull(snapshot.durationMs)
        assertTrue(snapshot.playbackEstablished)
    }
    @Test fun lifecycleStopTreatsRenderedFrameAsEstablishedWithoutReadyFlag() {
        val snapshot = ProgressSnapshotPolicy.onLifecycleStop(
            PlayerUiState(hasRenderedFirstFrame = true, positionMs = 12_000L, durationMs = 90_000L)
        )
        assertEquals(12_000L, snapshot.positionMs)
        assertEquals(90_000L, snapshot.durationMs)
        assertTrue(snapshot.playbackEstablished)
    }
    @Test fun lifecycleStopUsesTerminalPositionForEndedPlaybackAndRejectsUnstartedState() {
        val ended = ProgressSnapshotPolicy.onLifecycleStop(
            PlayerUiState(isEnded = true, isReady = false, positionMs = 1_790_000L, durationMs = 1_800_000L)
        )
        assertEquals(1_800_000L, ended.positionMs)
        assertEquals(1_800_000L, ended.durationMs)
        assertTrue(ended.playbackEstablished)

        val unstarted = ProgressSnapshotPolicy.onLifecycleStop(
            PlayerUiState(positionMs = -1L, durationMs = 90_000L)
        )
        assertEquals(0L, unstarted.positionMs)
        assertFalse(unstarted.playbackEstablished)
    }
}
