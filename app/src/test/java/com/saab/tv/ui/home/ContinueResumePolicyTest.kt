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
        val current = episode("tt123:2:3", 200, 650_000)
        val selected = ContinueResumePolicy.latestEpisode(listOf(old, current))!!
        assertEquals("tt123:2:3", selected.id)
        assertEquals(650_000L, selected.position)
        assertEquals(current, ContinueResumePolicy.latestEpisode(listOf(current, old)))
    }
    @Test fun seriesMustResolveFreshEpisodeEvenWhenAnOldStreamExists() {
        assertFalse(ContinueResumePolicy.mayReuseOpenStream("series"))
        assertTrue(ContinueResumePolicy.mayReuseOpenStream("movie"))
    }
    @Test fun prefixedIdsAndEmptyHistoryRemainSafe() {
        assertNull(ContinueResumePolicy.latestEpisode(emptyList()))
        val current = episode("tmdb:123:3:4", 200, 650_000)
        assertEquals(current, ContinueResumePolicy.latestEpisode(listOf(current)))
    }
}
