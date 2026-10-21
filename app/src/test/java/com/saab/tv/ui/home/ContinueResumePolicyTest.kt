package com.saab.tv.ui.home

import com.saab.tv.data.model.WatchHistoryEntity
import org.junit.Assert.*
import org.junit.Test

class ContinueResumePolicyTest {
    private fun episode(id: String, time: Long, position: Long) = WatchHistoryEntity(
        profileId = 1, id = id, title = "Series", poster = null,
        position = position, duration = 2_700_000, lastWatched = time, type = "series")
    @Test fun selectsMostRecentEpisodeNotFirstOrFurthest() {
        val old = episode("tt123:1:8", 100, 2_000_000)
        val current = episode("tt123:2:5", 200, 650_000)
        val selected = ContinueResumePolicy.latestEpisode(listOf(old, current))!!
        assertEquals("tt123:2:5", selected.id)
        assertEquals(650_000L, selected.position)
        assertEquals(current, ContinueResumePolicy.latestEpisode(listOf(current, old)))
    }
    @Test fun seriesMustResolveFreshEpisodeEvenWhenAnOldStreamExists() {
        assertFalse(ContinueResumePolicy.mayReuseOpenStream("series"))
        assertTrue(ContinueResumePolicy.mayReuseOpenStream("movie"))
    }

    @Test fun currentMovieStreamIsReusedOnlyForTheExactUnwatchedSelection() {
        val entry = episode("movie-1", 200, 650_000).copy(type = "movie")
        fun canReuse(
            contentId: String = "movie-1",
            mediaType: String = "movie",
            selectedContentId: String = "movie-1",
            selectedContentType: String = "movie",
            selectedPlaybackId: String = "movie-1",
            videoUrl: String = "https://stream.test/video.mkv",
            hasCurrentStream: Boolean = true,
            history: List<WatchHistoryEntity> = listOf(entry)
        ) = ContinueResumePolicy.canReuseCurrentStream(
            contentId, mediaType, selectedContentId, selectedContentType,
            selectedPlaybackId, videoUrl, hasCurrentStream, history
        )

        assertTrue(canReuse())
        assertFalse(canReuse(mediaType = "series"))
        assertFalse(canReuse(contentId = "another-movie"))
        assertFalse(canReuse(selectedContentType = "series"))
        assertFalse(canReuse(selectedPlaybackId = "another-movie"))
        assertFalse(canReuse(videoUrl = "file:///video.mkv"))
        assertFalse(canReuse(hasCurrentStream = false))
        assertFalse(canReuse(history = listOf(entry.copy(watched = true))))
    }

    @Test fun continueCardEpisodeWinsOverStaleDetailsResume() {
        assertEquals("tt123:2:5", ContinueResumePolicy.playbackId(
            seriesId = "tt123", continueHint = "tt123:2:5", hintIsWatched = false,
            calculatedResumeId = "tt123:1:1"
        ))
    }

    @Test fun invalidOrWatchedContinueHintFallsBackToCalculatedResume() {
        val fallback = "tt123:1:1"
        assertEquals(fallback, ContinueResumePolicy.playbackId("tt123", null, false, fallback))
        assertEquals(fallback, ContinueResumePolicy.playbackId("tt123", "tt1234:2:5", false, fallback))
        assertEquals(fallback, ContinueResumePolicy.playbackId("tt123", "tt123:2:5", true, fallback))
        assertEquals(fallback, ContinueResumePolicy.playbackId("tt123", "tt123:2", false, fallback))
        assertEquals(fallback, ContinueResumePolicy.playbackId("tt123", "tt123:x:5", false, fallback))
        assertNull(ContinueResumePolicy.playbackId("tt123", "tt123:x:5", false, null))
    }

    @Test fun prefixedIdsAndEmptyHistoryRemainSafe() {
        assertNull(ContinueResumePolicy.latestEpisode(emptyList()))
        val current = episode("tmdb:123:3:4", 200, 650_000)
        assertEquals(current, ContinueResumePolicy.latestEpisode(listOf(current)))
    }
}
