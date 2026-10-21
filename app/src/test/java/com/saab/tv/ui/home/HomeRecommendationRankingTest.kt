package com.saab.tv.ui.home

import com.saab.tv.data.tmdb.mixTmdbMediaTypes
import com.saab.tv.domain.schema.ContentIdentity
import com.saab.tv.domain.schema.ContentKind
import com.saab.tv.domain.schema.RatingProjection
import com.saab.tv.domain.schema.TitleCardProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRecommendationRankingTest {
    @Test fun suggestionsCombineHistoryMatchesRankByMatchAndKeepBothMediaTypes() {
        fun item(id: Int, type: String) = projection(id, type)

        val ranked = rankWatchHistorySuggestions(
            recommendationsByHistory = listOf(
                listOf(item(1, "movie"), item(2, "tv")),
                listOf(item(1, "movie"), item(3, "movie"))
            )
        )

        assertEquals(listOf("1", "2", "3"), ranked.map { it.identity.externalId })
        assertEquals(listOf("movie", "series", "movie"), ranked.map { it.kind.keyPart })
    }

    @Test fun suggestionsUseCanonicalRatingThenStableIdentityForEqualMatchScores() {
        fun item(id: Int, rating: Double) = projection(id, "movie", rating)

        val ranked = rankWatchHistorySuggestions(
            recommendationsByHistory = listOf(
                listOf(item(2, 9.0), item(1, 5.0)),
                listOf(item(1, 5.0))
            )
        )

        assertEquals(listOf("2", "1"), ranked.map { it.identity.externalId })

        val stableTie = rankWatchHistorySuggestions(
            recommendationsByHistory = listOf(
                listOf(item(2, 5.0), item(1, 5.0)),
                listOf(item(1, 5.0))
            )
        )
        assertEquals(listOf("1", "2"), stableTie.map { it.identity.externalId })
    }

    @Test fun topSuggestionsPreserveHistoryMatchOrderBeforeCrossTypeFillers() {
        val directMovieMatches = listOf(
            listOf(projection(1, "movie", rating = 5.0), projection(2, "movie", rating = 9.9)),
            listOf(projection(2, "movie", rating = 9.9), projection(1, "movie", rating = 5.0))
        )

        val suggestions = composeTopSuggestions(
            recommendationsByHistory = directMovieMatches,
            crossTypeCandidates = listOf(
                projection(3, "tv", rating = 10.0),
                projection(4, "movie", rating = 10.0),
                projection(3, "tv", rating = 10.0)
            ),
            limit = 3
        )

        assertEquals(listOf("1", "2", "3"), suggestions.map { it.identity.externalId })
        assertEquals(listOf("movie", "movie", "series"), suggestions.map { it.kind.keyPart })
    }

    @Test fun crossTypeFillersNeverDisplaceDirectHistoryMatchesAtTheLimit() {
        val suggestions = composeTopSuggestions(
            recommendationsByHistory = listOf(listOf(
                projection(1, "movie"),
                projection(2, "movie"),
                projection(3, "movie"),
                projection(4, "movie")
            )),
            crossTypeCandidates = listOf(projection(5, "tv", rating = 10.0)),
            limit = 3
        )

        assertEquals(listOf("1", "2", "3"), suggestions.map { it.identity.externalId })
    }

    @Test fun movieOnlyAndSeriesOnlyHistoryCanFillOnlyTheMissingMediaKind() {
        listOf("movie" to "tv", "tv" to "movie").forEach { (historyType, fillerType) ->
            val suggestions = composeTopSuggestions(
                recommendationsByHistory = listOf(listOf(
                    projection(1, historyType),
                    projection(2, historyType)
                )),
                crossTypeCandidates = listOf(
                    projection(3, fillerType),
                    projection(4, historyType)
                ),
                limit = 3
            )

            assertEquals(listOf("1", "2", "3"), suggestions.map { it.identity.externalId })
            assertEquals(setOf("movie", "series"), suggestions.map { it.kind.keyPart }.toSet())
        }
    }

    @Test fun mixedHistoryMatchesRemainInRankOrderWithoutUnrequestedFillers() {
        val matches = listOf(
            listOf(projection(1, "movie"), projection(2, "tv"), projection(3, "movie"))
        )

        val suggestions = composeTopSuggestions(
            recommendationsByHistory = matches,
            crossTypeCandidates = listOf(projection(4, "tv"), projection(5, "movie")),
            limit = 4
        )

        assertEquals(listOf("1", "2", "3"), suggestions.map { it.identity.externalId })
    }

    @Test fun coldHistoryDoesNotRelabelGenericDiscoveryAsTopSuggestions() {
        val suggestions = composeTopSuggestions(
            recommendationsByHistory = emptyList(),
            crossTypeCandidates = listOf(projection(1, "movie"), projection(2, "tv"))
        )

        assertTrue(suggestions.isEmpty())
    }

    @Test fun topSuggestionsExcludeAlreadyVisibleAndDuplicateCanonicalItems() {
        val suggestions = composeTopSuggestions(
            recommendationsByHistory = listOf(listOf(
                projection(1, "movie"),
                projection(2, "movie")
            )),
            crossTypeCandidates = listOf(
                projection(1, "tv"),
                projection(3, "tv"),
                projection(3, "tv")
            ),
            excludedPresentationKeys = setOf(
                projection(1, "movie").presentationKey,
                projection(1, "tv").presentationKey
            ),
            limit = 3
        )

        assertEquals(listOf("2", "3"), suggestions.map { it.identity.externalId })
        assertEquals(2, suggestions.map { it.presentationKey }.distinct().size)
    }

    @Test fun topSuggestionsHonorZeroAndSmallLimits() {
        val matches = listOf(listOf(projection(1, "movie"), projection(2, "movie")))
        val crossType = listOf(projection(3, "tv"), projection(4, "tv"))

        assertTrue(composeTopSuggestions(matches, crossType, limit = 0).isEmpty())
        assertEquals(
            listOf("1"),
            composeTopSuggestions(matches, crossType, limit = 1).map { it.identity.externalId }
        )
    }

    @Test fun discoveryRailsInterleaveMediaTypesWhenBothAreAvailable() {
        fun item(id: Int, type: String) = projection(id, type)

        val historyMatches = rankWatchHistorySuggestions(listOf(listOf(item(1, "movie"), item(2, "movie"))))
        val mixed = mixTmdbMediaTypes(historyMatches + listOf(item(3, "tv"), item(4, "tv")), limit = 4)

        assertEquals(listOf("movie", "series", "movie", "series"), mixed.map { it.kind.keyPart })
    }

    @Test fun asynchronouslyGeneratedTmdbRowsStayAfterCatalogRowsToPreserveFocusKeys() {
        val catalogOrders = listOf(-10, 0, 4, 12)
        val personalizedOrders = listOf(10, 13, 16, 19).map(::personalizedRailOrder)

        assertTrue(personalizedOrders.all { it > catalogOrders.maxOrNull()!! })
        assertEquals(personalizedOrders.sorted(), personalizedOrders)
    }

    private fun projection(id: Int, providerType: String, rating: Double? = null): TitleCardProjection {
        val kind = if (providerType == "tv") ContentKind.SERIES else ContentKind.MOVIE
        val identity = requireNotNull(ContentIdentity.tmdb(kind, id))
        return TitleCardProjection(
            presentationKey = identity.stableKey,
            identity = identity,
            kind = kind,
            title = "Title $id",
            synopsis = null,
            artwork = emptyList(),
            rating = rating?.let { RatingProjection(it, "tmdb", "0-10") }
        )
    }
}
