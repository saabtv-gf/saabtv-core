package com.saab.tv.data.account

import com.saab.tv.data.model.WatchHistoryEntity
import org.junit.Assert.*
import org.junit.Test

class PlaybackHistoryFreshnessTest {
    private fun row(position: Long, time: Long, profile: Int = 1, id: String = "tt1") =
        WatchHistoryEntity(profileId = profile, id = id, title = "Title", poster = null,
            position = position, duration = 7_200_000, lastWatched = time, type = "movie")

    @Test fun newerDeviceStopWinsOverStaleLocalPosition() {
        val result = newestPlaybackHistory(listOf(row(100_000, 100)), RemotePlaybackHistory(listOf(row(900_000, 200)), true), 1)
        assertEquals(900_000L, result.single().position)
    }

    @Test fun intentionalRewindIsNotReplacedByFurthestPosition() {
        val result = newestPlaybackHistory(listOf(row(900_000, 100)), RemotePlaybackHistory(listOf(row(100_000, 200)), true), 1)
        assertEquals(100_000L, result.single().position)
    }

    @Test fun newerLocalUnsyncedStopWins() {
        val local = row(400_000, 300)
        assertEquals(local, newestPlaybackHistory(listOf(local), RemotePlaybackHistory(listOf(row(900_000, 200)), true), 1).single())
    }

    @Test fun profilesNeverShareResumePositions() {
        val result = newestPlaybackHistory(listOf(row(100_000, 100)), RemotePlaybackHistory(listOf(row(900_000, 200, profile = 2)), true), 1)
        assertEquals(100_000L, result.single().position)
    }

    @Test fun previouslyAppliedCloudRevisionDoesNotUndoClearProgress() {
        assertTrue(newestPlaybackHistory(emptyList(), RemotePlaybackHistory(listOf(row(900_000, 200)), false), 1).isEmpty())
        assertEquals(900_000L, newestPlaybackHistory(emptyList(), RemotePlaybackHistory(listOf(row(900_000, 200)), true), 1).single().position)
    }

    @Test fun unknownRemoteTimesAndTiesKeepLocalCopy() {
        val local = row(100_000, 100)
        for (time in listOf(0L, 100L)) {
            assertEquals(local, newestPlaybackHistory(listOf(local), RemotePlaybackHistory(listOf(row(900_000, time)), true), 1).single())
        }
    }
}
