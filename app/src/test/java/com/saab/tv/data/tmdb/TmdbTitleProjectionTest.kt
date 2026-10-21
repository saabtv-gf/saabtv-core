package com.saab.tv.data.tmdb

import com.saab.tv.domain.schema.ArtworkRole
import com.saab.tv.domain.schema.ContentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbTitleProjectionTest {
    @Test
    fun mapsMovieAndSeriesToKindQualifiedIdentityAndRoleSpecificArtwork() {
        val movie = preview(type = "movie")
        val series = preview(type = "tv")

        val movieProjection = requireNotNull(movie.toCanonicalProjection())
        val seriesProjection = requireNotNull(series.toCanonicalProjection())

        assertEquals(ContentKind.MOVIE, movieProjection.kind)
        assertEquals(ContentKind.SERIES, seriesProjection.kind)
        assertTrue(movieProjection.presentationKey != seriesProjection.presentationKey)
        assertEquals("poster", movieProjection.artwork(ArtworkRole.POSTER)?.locator)
        assertEquals("backdrop", movieProjection.artwork(ArtworkRole.BACKDROP)?.locator)
        assertEquals("tmdb:42", movieProjection.asMetaItem().id)
        assertEquals("series", seriesProjection.asMetaItem().type)
    }

    @Test
    fun retainsTmdbRatingProvenanceWithoutMislabelingItAsImdb() {
        val projection = requireNotNull(preview(type = "movie").toCanonicalProjection())

        assertEquals("tmdb", projection.rating?.source)
        assertEquals("0-10", projection.rating?.scale)
        assertNull(projection.asMetaItem().imdbRating)
        assertEquals("tmdb:42", preview(type = "movie").toCanonicalMetaItem()?.id)
    }

    @Test
    fun rejectsInvalidKindIdAndRating() {
        assertNull(preview(type = "person").toCanonicalProjection())
        assertNull(preview(tmdbId = 0).toCanonicalProjection())
        assertNull(preview(rating = 12.0).toCanonicalProjection()?.rating)
        assertNull(preview(rating = null).toCanonicalProjection()?.rating)
        assertNull(preview(type = "person").toCanonicalMetaItem())
        assertTrue(requireNotNull(preview(poster = null, backdrop = null).toCanonicalProjection()).artwork.isEmpty())
    }

    private fun preview(
        type: String = "movie",
        tmdbId: Int = 42,
        rating: Double? = 8.4,
        poster: String? = "poster",
        backdrop: String? = "backdrop"
    ) = TmdbMetaPreview(
        tmdbId = tmdbId,
        type = type,
        name = "Example",
        poster = poster,
        backdrop = backdrop,
        description = "Synopsis",
        releaseInfo = "2024-02-01",
        rating = rating
    )
}
