package com.saab.tv.domain

import com.saab.tv.data.model.stremio.MetaVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeUtilsTest {
    @Test fun episodePanelTargetsTheCurrentSeasonAndEpisodeBeforeComposition() {
        val videos = episodes + (1..12).map { MetaVideo(id = "tt123:2:$it", title = "Episode $it", season = 2, episode = it) }
        assertEquals(EpisodePanelPosition(2, 9), resolveEpisodePanelPosition(videos, "tt123:2:10"))
        assertEquals(EpisodePanelPosition(2, 11), resolveEpisodePanelPosition(videos, "tmdb:123:2:12", 1, 0))
        assertEquals(EpisodePanelPosition(1, 1), resolveEpisodePanelPosition(videos, "native-2"))
        assertEquals(EpisodePanelPosition(2, 11), resolveEpisodePanelPosition(videos, "missing", 2, 99))
        assertEquals(EpisodePanelPosition(1, 0), resolveEpisodePanelPosition(emptyList(), null))
    }
    private val episodes = listOf(
        MetaVideo(id = "native-2", title = "Second", season = 1, episode = 2),
        MetaVideo(id = "native-1", title = "First", season = 1, episode = 1),
        MetaVideo(id = "special", title = "Special", season = 0, episode = 1),
        MetaVideo(id = "duplicate", title = "Duplicate", season = 1, episode = 2)
    )

    @Test
    fun normalizesAndFindsTheNextEpisodeForColonIds() {
        val normalized = normalizeEpisodeList(episodes)

        assertEquals(listOf(1, 2), normalized.map { it.episode })
        assertEquals(2, findNextEpisode("tmdb:123", "tmdb:123:1:1", episodes)?.episode)
        assertNull(findNextEpisode("tmdb:123", "tmdb:123:1:2", episodes))
        assertTrue(episodeMatchesPlaybackId("tmdb:123", "tmdb:123:1:2", normalized[1]))
    }
}
