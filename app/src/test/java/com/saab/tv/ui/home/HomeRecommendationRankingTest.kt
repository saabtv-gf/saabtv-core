package com.saab.tv.ui.home

import com.saab.tv.data.tmdb.TmdbMetaPreview
import com.saab.tv.data.tmdb.mixTmdbMediaTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRecommendationRankingTest {
    @Test fun suggestionsCombineHistoryMatchesRankByMatchAndKeepBothMediaTypes() {
        fun item(id: Int, type: String, popularity: Double = 0.0) = TmdbMetaPreview(
            tmdbId = id,
            type = type,
            name = "Title $id",
            poster = null,
            backdrop = null,
            description = null,
            releaseInfo = null,
            rating = null,
            popularity = popularity
        )

        val ranked = rankWatchHistorySuggestions(
            recommendationsByHistory = listOf(
                listOf(item(1, "movie"), item(2, "tv")),
                listOf(item(1, "movie"), item(3, "movie"))
            )
        )

        assertEquals(listOf(1, 2, 3), ranked.map { it.tmdbId })
        assertEquals(listOf("movie", "series", "movie"), ranked.map { it.type })
    }

    @Test fun suggestionsUsePopularityOnlyToBreakEqualMatchScores() {
        fun item(id: Int, popularity: Double) = TmdbMetaPreview(
            tmdbId = id,
            type = "movie",
            name = "Title $id",
            poster = null,
            backdrop = null,
            description = null,
            releaseInfo = null,
            rating = null,
            popularity = popularity
        )

        val ranked = rankWatchHistorySuggestions(
            recommendationsByHistory = listOf(
                listOf(item(1, 1.0), item(2, 100.0)),
                listOf(item(2, 100.0))
            )
        )

        assertEquals(listOf(2, 1), ranked.map { it.tmdbId })
    }

    @Test fun topSuggestionsAlternateHistoryMatchesAndCrossTypeFillers() {
        fun item(id: Int, type: String) = TmdbMetaPreview(
            tmdbId = id,
            type = type,
            name = "Title $id",
            poster = null,
            backdrop = null,
            description = null,
            releaseInfo = null,
            rating = null
        )

        val historyMatches = rankWatchHistorySuggestions(listOf(listOf(item(1, "movie"), item(2, "movie"))))
        val mixed = mixTmdbMediaTypes(historyMatches + listOf(item(3, "tv"), item(4, "tv")), limit = 4)

        assertEquals(listOf("movie", "series", "movie", "series"), mixed.map { it.type })
    }

    @Test fun asynchronouslyGeneratedTmdbRowsStayAfterCatalogRowsToPreserveFocusKeys() {
        val catalogOrders = listOf(-10, 0, 4, 12)
        val personalizedOrders = listOf(10, 13, 16, 19).map(::personalizedRailOrder)

        assertTrue(personalizedOrders.all { it > catalogOrders.maxOrNull()!! })
        assertEquals(personalizedOrders.sorted(), personalizedOrders)
    }
}
