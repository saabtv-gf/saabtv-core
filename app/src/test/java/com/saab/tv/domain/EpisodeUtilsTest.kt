package com.saab.tv.domain

import com.saab.tv.data.model.stremio.MetaVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeUtilsTest {
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
