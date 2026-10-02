package com.saab.tv.data.repository

import com.saab.tv.data.model.stremio.MetaItem
import org.junit.Assert.*
import org.junit.Test

class RecommendationRegressionTest {
    private fun item(id: String, rating: String? = null, genres: List<String>? = null) =
        MetaItem(id = id, type = "movie", name = id, poster = "poster", imdbRating = rating, genres = genres)

    @Test fun ratingBreaksGenreAndCatalogHitTiesButInvalidRatingDoesNotWin() {
        val items = listOf(item("low", "5"), item("high", "9"), item("invalid", "bad"), item("absent"))
        assertEquals(listOf("high", "low", "invalid", "absent"),
            CinemetaRecommendationRanker.merge(listOf(items), "current", emptyList()).map { it.id })
    }
    @Test fun catalogHitCountOutranksRatingAndGenresAreCaseInsensitive() {
        val repeated = item("repeat", "4", listOf(" DRAMA "))
        val higherRating = item("rating", "9", listOf("drama"))
        assertEquals(listOf(repeated, higherRating), CinemetaRecommendationRanker.merge(
            listOf(listOf(higherRating, repeated), listOf(repeated)), "current", listOf(" Drama ", " ")))
    }
    @Test fun firstSeenOrderIsStableAndCapIsAppliedAfterSorting() {
        val items = (1..25).map { item("tt$it") }
        assertEquals(items.take(18), CinemetaRecommendationRanker.merge(listOf(items), "current", emptyList()))
        assertEquals(items.take(2), CinemetaRecommendationRanker.merge(listOf(items), "current", emptyList(), 2))
        assertTrue(CinemetaRecommendationRanker.merge(listOf(items), "current", emptyList(), 0).isEmpty())
    }
    @Test fun duplicateIdentityIsCaseInsensitiveAndCurrentTitleIsAlwaysExcluded() {
        val first = item("TT1")
        assertEquals(listOf(first), CinemetaRecommendationRanker.merge(
            listOf(listOf(first, item("tt1"), item("TT2"))), "tt2", emptyList()))
        assertTrue(CinemetaRecommendationRanker.merge(emptyList(), "current", emptyList()).isEmpty())
    }
    @Test fun sameIdOfDifferentMediaTypesIsNotDeduplicated() {
        val movie = item("tt1")
        val series = movie.copy(type = "series")
        assertEquals(listOf(movie, series), CinemetaRecommendationRanker.merge(listOf(listOf(movie, series)), "current", emptyList()))
    }
    @Test fun subtitleMatchingNormalizesExtensionsRejectsWrongEpisodeAndEdition() {
        assertEquals(10000, SubtitleReleaseMatcher.score("Movie.1080p.MKV", "movie 1080p.srt"))
        assertEquals(-10000, SubtitleReleaseMatcher.score("Show.S01E02", "Show.S01E03"))
        assertEquals(-1000, SubtitleReleaseMatcher.score("Movie.Extended", "Movie.Theatrical"))
        assertEquals(39, SubtitleReleaseMatcher.score("Movie.1080p", "Movie.1080p.Group"))
        listOf(null, "", " ").forEach {
            assertEquals(0, SubtitleReleaseMatcher.score(it, "release"))
            assertEquals(0, SubtitleReleaseMatcher.score("file", it))
        }
    }
}
