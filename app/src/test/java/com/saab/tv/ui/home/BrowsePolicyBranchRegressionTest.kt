package com.saab.tv.ui.home

import com.saab.tv.data.model.WatchHistoryEntity
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.ui.details.seriesResumeActionLabel
import com.saab.tv.ui.trailer.TrailerPreviewPolicy
import com.saab.tv.ui.watchlist.WatchlistFocusPolicy
import com.saab.tv.ui.watchlist.WatchlistFocusTarget
import com.saab.tv.ui.common.ListStateSafety
import org.junit.Assert.*
import org.junit.Test

class BrowsePolicyBranchRegressionTest {
    private fun entry(id: String, time: Long) = WatchHistoryEntity(
        profileId = 1, id = id, title = "Series", poster = null,
        position = 0, duration = 1_800_000, lastWatched = time, type = "series")

    @Test fun resumeIgnoresAggregateAndMalformedIdsWhenAnEpisodeExists() {
        val current = entry("tt123:2:4", 100)
        val aggregate = entry("tt123", 999)
        val malformedSeason = entry("tt123:no:4", 1000)
        val malformedEpisode = entry("tt123:2:no", 1001)
        assertEquals(current, ContinueResumePolicy.latestEpisode(listOf(aggregate, malformedSeason, malformedEpisode, current)))
        assertEquals(malformedEpisode, ContinueResumePolicy.latestEpisode(listOf(aggregate, malformedSeason, malformedEpisode)))
        assertEquals(current, ContinueResumePolicy.latestEpisode(listOf(current)))
        assertTrue(ContinueResumePolicy.mayReuseOpenStream(""))
    }
    @Test fun previewMetadataPriorityWorksAtEveryFallbackLevel() {
        val current = MetaItem(id = "current")
        val enriched = MetaItem(id = "enriched")
        val hero = MetaItem(id = "hero")
        val row = MetaItem(id = "row")
        val history = MetaItem(id = "history")
        assertSame(enriched, HomePreviewMetadataPolicy.select(current, enriched, hero, row, history))
        assertSame(hero, HomePreviewMetadataPolicy.select(current, null, hero, row, history))
        assertSame(row, HomePreviewMetadataPolicy.select(current, null, null, row, history))
        assertSame(history, HomePreviewMetadataPolicy.select(current, null, null, null, history))
        assertSame(current, HomePreviewMetadataPolicy.select(current, null, null, null, null))
    }
    @Test fun labelsNeverInventMissingSeasonOrEpisode() {
        assertEquals("Resume S2 E4", seriesResumeActionLabel(2, 4, false))
        assertEquals("Play Next S2 E4", seriesResumeActionLabel(2, 4, true))
        for ((season, episode) in listOf(null to null, 2 to null, null to 4)) {
            assertEquals("Resume", seriesResumeActionLabel(season, episode, false))
            assertEquals("Play Next Episode", seriesResumeActionLabel(season, episode, true))
        }
    }
    @Test fun hoverPreviewRespectsSearchContinueWatchingAndDismissal() {
        assertTrue(TrailerPreviewPolicy.allowsHover(false, false))
        assertFalse(TrailerPreviewPolicy.allowsHover(true, false))
        assertFalse(TrailerPreviewPolicy.allowsHover(false, true))
        assertFalse(TrailerPreviewPolicy.allowsHover(true, true))
        assertTrue(TrailerPreviewPolicy.canStart(true, true, "tt1", "home", null, null))
        assertFalse(TrailerPreviewPolicy.canStart(false, true, "tt1", "home", null, null))
        assertFalse(TrailerPreviewPolicy.canStart(true, false, "tt1", "home", null, null))
        assertFalse(TrailerPreviewPolicy.canStart(true, true, null, "home", null, null))
        assertFalse(TrailerPreviewPolicy.canStart(true, true, "tt1", null, null, null))
        assertFalse(TrailerPreviewPolicy.canStart(true, true, "tt1", "home", "tt1", "movies"))
        assertFalse(TrailerPreviewPolicy.canStart(true, true, "tt1", "home", "tt2", "home"))
        assertTrue(TrailerPreviewPolicy.canStart(true, true, "tt1", "home", "tt2", "movies"))
    }
    @Test fun watchlistRejectsMalformedAndMissingFocusTargets() {
        for (key in listOf(null, "", " ", "_tt_0", "0_tt", "bad_tt_0", "0__0")) {
            assertNull("key=$key", WatchlistFocusPolicy.parse(key))
            assertFalse(WatchlistFocusPolicy.isValid(key, emptySet(), emptySet()))
        }
        assertEquals(WatchlistFocusTarget(0, "tt_with_underscore"), WatchlistFocusPolicy.parse("0_tt_with_underscore_3"))
        assertTrue(WatchlistFocusPolicy.isValid("0_movie_1", setOf("movie"), emptySet()))
        assertFalse(WatchlistFocusPolicy.isValid("0_missing_1", setOf("movie"), emptySet()))
        assertTrue(WatchlistFocusPolicy.isValid("1_series_1", emptySet(), setOf("series")))
        assertFalse(WatchlistFocusPolicy.isValid("1_missing_1", emptySet(), setOf("series")))
        assertFalse(WatchlistFocusPolicy.isValid("2_series_1", setOf("series"), setOf("series")))
    }
    @Test fun staleListIndicesAreClampedBeforeFocusRestoration() {
        assertEquals(0, ListStateSafety.boundedIndex(10, 0))
        assertEquals(0, ListStateSafety.boundedIndex(10, -1))
        assertEquals(0, ListStateSafety.boundedIndex(-1, 3))
        assertEquals(2, ListStateSafety.boundedIndex(3, 3))
        assertEquals(1, ListStateSafety.boundedIndex(1, 3))
    }
}
