package com.saab.tv.data.repository

import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.schema.ContentIdentity
import com.saab.tv.domain.schema.ContentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalStremioMetadataTest {
    private val requestedId = "tt1234567"
    private val cinema = "https://v3-cinemeta.strem.io"

    @Test fun encodesVerifiedCinemetaMovieFactsWithoutPlaybackState() {
        val encoded = requireNotNull(CanonicalStremioMetadata.encode(
            meta().copy(
                releaseInfo = "2024-05-06", imdbRating = "8.4", runtime = "1h 42m",
                genres = listOf(" Drama ", "", "Thriller")
            ), requestedId, "movie", 10_000
        ))

        assertEquals(requireNotNull(ContentIdentity.imdb(ContentKind.MOVIE, requestedId)).stableKey, encoded.identityKey)
        assertEquals("movie", encoded.kind)
        assertEquals("Film", encoded.title)
        assertEquals("CINEMETA", encoded.titleSource)
        assertEquals("Plot", encoded.synopsis)
        assertEquals("CINEMETA", encoded.posterSource)
        assertEquals("Drama", encoded.genresJson?.let { com.google.gson.Gson().fromJson(it, Array<String>::class.java) }?.first())
        assertEquals("2024-05-06", encoded.releaseDate)
        assertEquals("DATE", encoded.releaseDatePrecision)
        assertEquals(8.4, encoded.ratingValue!!, 0.0)
        assertEquals("IMDb", encoded.ratingSource)
        assertEquals(6_120_000L, encoded.runtimeEstimateMillis)
        assertEquals(10_000L, encoded.lastFetchedAtMillis)
        assertEquals(0f, CanonicalStremioMetadata.decode(encoded, requestedId, "movie")!!.progress)
        assertNull(CanonicalStremioMetadata.decode(encoded, requestedId, "movie")!!.resumePlaybackId)
        assertEquals("1h 42m", CanonicalStremioMetadata.decode(encoded, requestedId, "movie")!!.runtime)
    }

    @Test fun rejectsUnverifiedWrongKindAndEmptyObservations() {
        assertNull(CanonicalStremioMetadata.encode(meta(), requestedId, "movie", 0))
        assertNull(CanonicalStremioMetadata.encode(meta().copy(addonBaseUrl = "http://v3-cinemeta.strem.io"), requestedId, "movie", 1))
        assertNull(CanonicalStremioMetadata.encode(meta().copy(addonBaseUrl = "not a url"), requestedId, "movie", 1))
        assertNull(CanonicalStremioMetadata.encode(meta().copy(addonBaseUrl = "https://addon.example"), requestedId, "movie", 1))
        assertNull(CanonicalStremioMetadata.encode(meta(), "tt-invalid", "movie", 1))
        assertNull(CanonicalStremioMetadata.encode(meta().copy(id = "tt7654321"), requestedId, "movie", 1))
        assertNull(CanonicalStremioMetadata.encode(meta().copy(type = "series"), requestedId, "movie", 1))
        assertNull(CanonicalStremioMetadata.encode(meta().copy(name = "  "), requestedId, "movie", 1))
        assertNull(CanonicalStremioMetadata.encode(meta(), requestedId, "episode", 1))
    }

    @Test fun normalizesPartialFactsAndValidatesReleaseRatingAndRuntime() {
        val invalidFacts = requireNotNull(CanonicalStremioMetadata.encode(
            meta().copy(releaseInfo = "2023–2024", imdbRating = "N/A", runtime = "a long time", genres = emptyList()),
            requestedId, "film", 2_000
        ))
        assertNull(invalidFacts.releaseDate)
        assertNull(invalidFacts.ratingValue)
        assertNull(invalidFacts.runtimeEstimateMillis)
        assertNull(invalidFacts.genresJson)

        val yearFacts = requireNotNull(CanonicalStremioMetadata.encode(meta().copy(releaseInfo = "2025", imdbRating = "12", runtime = "90 min"), requestedId, "movie", 3_000))
        assertEquals("YEAR", yearFacts.releaseDatePrecision)
        assertEquals("2025", yearFacts.releaseDate)
        assertEquals(5_400_000L, yearFacts.runtimeEstimateMillis)
        assertNull(yearFacts.ratingValue)

        val invalidYear = requireNotNull(CanonicalStremioMetadata.encode(meta().copy(releaseInfo = "2500", runtime = "25h"), requestedId, "movie", 4_000))
        assertNull(invalidYear.releaseDate)
        assertNull(invalidYear.runtimeEstimateMillis)

        val numericRuntime = requireNotNull(CanonicalStremioMetadata.encode(meta().copy(releaseInfo = "not-a-date", runtime = "47"), requestedId, "movie", 5_000))
        assertNull(numericRuntime.releaseDate)
        assertEquals(2_820_000L, numericRuntime.runtimeEstimateMillis)
        assertNull(CanonicalStremioMetadata.encode(meta().copy(runtime = "1441 min"), requestedId, "movie", 6_000)?.runtimeEstimateMillis)
        val noOptionalValues = requireNotNull(CanonicalStremioMetadata.encode(meta().copy(releaseInfo = " ", runtime = null), requestedId, "movie", 7_000))
        assertNull(noOptionalValues.releaseDate)
        assertNull(noOptionalValues.runtimeEstimateMillis)
    }

    @Test fun decodesOnlyMatchingMovieCinemetaRowsAndRecoversBadOptionalLists() {
        val row = requireNotNull(CanonicalStremioMetadata.encode(meta().copy(runtime = "95m"), requestedId, "movie", 10_000))
        val decoded = requireNotNull(CanonicalStremioMetadata.decode(row, requestedId, "movie"))
        assertEquals(requestedId, decoded.id)
        assertEquals("movie", decoded.type)
        assertEquals("1h 35m", decoded.runtime)
        assertEquals(listOf("Drama"), decoded.genres)
        assertNull(CanonicalStremioMetadata.decode(row.copy(ratingSource = "TMDB"), requestedId, "movie")?.imdbRating)
        assertNull(CanonicalStremioMetadata.decode(row.copy(ratingScale = "0-100"), requestedId, "movie")?.imdbRating)

        assertNull(CanonicalStremioMetadata.decode(row, "tt0000001", "movie"))
        assertNull(CanonicalStremioMetadata.decode(row.copy(kind = "series"), requestedId, "movie"))
        assertNull(CanonicalStremioMetadata.decode(row.copy(titleSource = "TMDB"), requestedId, "movie"))
        assertNull(CanonicalStremioMetadata.decode(row.copy(title = " "), requestedId, "movie"))
        assertNull(CanonicalStremioMetadata.decode(row, requestedId, "series"))
        assertNull(CanonicalStremioMetadata.decode(row, "bad", "movie"))
        assertEquals(emptyList<String>(), CanonicalStremioMetadata.decode(row.copy(genresJson = "broken"), requestedId, "movie")?.genres)

        val noTitle = row.copy(title = null)
        assertNull(CanonicalStremioMetadata.decode(noTitle, requestedId, "movie"))
        assertTrue(CanonicalStremioMetadata.isCinemetaOrigin("https://v3-cinemeta.strem.io/path"))
        assertFalse(CanonicalStremioMetadata.isCinemetaOrigin("https://cinemeta.example"))
    }

    private fun meta() = MetaItem(
        id = requestedId, type = "movie", name = " Film ", poster = " poster ",
        background = " backdrop ", logo = " logo ", description = " Plot ",
        genres = listOf("Drama"), addonBaseUrl = cinema
    )
}
