package com.saab.tv.data.repository

import com.saab.tv.data.model.stremio.MetaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CinemetaRecommendationRankerTest {
    @Test
    fun mergesGenresRemovesDuplicatesAndExcludesCurrentTitle() {
        val current = item("tt1", "Current", listOf("Drama"))
        val strongest = item("tt2", "Strongest", listOf("Drama", "Crime"), "8.4")
        val dramaOnly = item("tt3", "Drama", listOf("Drama"), "7.8")
        val crimeOnly = item("tt4", "Crime", listOf("Crime"), "7.5")

        val result = CinemetaRecommendationRanker.merge(
            catalogs = listOf(
                listOf(current, strongest, dramaOnly),
                listOf(strongest, crimeOnly)
            ),
            currentId = current.id,
            targetGenres = listOf("Drama", "Crime")
        )

        assertEquals(listOf(strongest, dramaOnly, crimeOnly), result)
        assertFalse(result.any { it.id == current.id })
    }

    @Test
    fun ignoresItemsWithoutPosters() {
        val missingPoster = item("tt2", "Missing", listOf("Drama"), poster = null)

        assertEquals(
            emptyList<MetaItem>(),
            CinemetaRecommendationRanker.merge(listOf(listOf(missingPoster)), "tt1", listOf("Drama"))
        )
    }

    private fun item(
        id: String,
        name: String,
        genres: List<String>,
        rating: String = "7.0",
        poster: String? = "https://example.com/$id.jpg"
    ) = MetaItem(
        id = id,
        type = "movie",
        name = name,
        poster = poster,
        genres = genres,
        imdbRating = rating
    )
}
