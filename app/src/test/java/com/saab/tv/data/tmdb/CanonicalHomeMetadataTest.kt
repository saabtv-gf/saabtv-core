package com.saab.tv.data.tmdb

import com.saab.tv.domain.schema.ContentIdentity
import com.saab.tv.domain.schema.ContentKind
import org.junit.Assert.*
import org.junit.Test

class CanonicalHomeMetadataTest {
    private val identity = requireNotNull(ContentIdentity.tmdb(ContentKind.SERIES, 42))

    @Test fun populatedHomeProjectionSurvivesPersistentRoundTrip() {
        val value = projection().copy(
            localizedTitle = "తెలుగు Title", description = "Localized synopsis",
            genres = listOf("Drama", "Crime"), genreIds = listOf(18, 80),
            backdrop = "https://art/wide", poster = "https://art/poster", logo = "https://art/logo",
            releaseInfo = "2025-01-02", rating = 8.3, runtimeMinutes = 47,
            status = "Returning Series", countries = listOf("IN", "US"), language = "te",
            collectionId = 72, collectionName = "Collection"
        )
        val row = CanonicalHomeMetadata.encode(identity, "TE_in", value, 1_000, true)
        assertEquals(value, CanonicalHomeMetadata.decode(row))
        assertEquals(identity.stableKey, row.identityKey)
        assertEquals("series", row.kind)
        assertEquals("te-in", row.locale)
        assertEquals("TMDB", row.titleSource)
        assertEquals("TE_in", row.titleLanguage)
        assertEquals(1_000L, row.titleObservedAtMillis)
        assertEquals("TMDB", row.synopsisSource)
        assertEquals("TMDB", row.posterSource)
        assertEquals("TMDB", row.backdropSource)
        assertEquals("TMDB", row.logoSource)
        assertEquals(1_000L, row.logoObservedAtMillis)
        assertEquals("DATE", row.releaseDatePrecision)
        assertEquals("TMDB", row.ratingSource)
        assertEquals("0-10", row.ratingScale)
        assertEquals(2_820_000L, row.runtimeEstimateMillis)
    }

    @Test fun absentOptionalMetadataSurvivesAndLogoRequestIsNotInvented() {
        val value = projection()
        val row = CanonicalHomeMetadata.encode(identity, "en", value, 1_000, false)
        assertEquals(value, CanonicalHomeMetadata.decode(row))
        assertEquals(0L, row.logoObservedAtMillis)
        assertNull(row.releaseDatePrecision)
        assertNull(row.runtimeEstimateMillis)
        assertNull(row.countriesJson)
        assertEquals(value, CanonicalHomeMetadata.decode(row.copy(genresJson = null, genreIdsJson = null)))
    }

    @Test fun fullDetailsPersistAgeRatingRegionAndBoundedCredits() {
        val details = projection().copy(
            castMembers = listOf(TmdbCastInfo("Actor", "Lead", "https://art/actor", 9)),
            directorMembers = listOf(TmdbCastInfo("Director", "Director", null, 10)),
            writerMembers = listOf(TmdbCastInfo("Writer", "Writer", null, 11)),
            ageRating = "U/A 16+", ageRatingRegion = "IN",
            digitalReleaseDate = "2025-03-01", digitalReleaseRegion = "IN",
            physicalReleaseDate = "2025-04-01", physicalReleaseRegion = "GB"
        )

        val row = CanonicalHomeMetadata.encode(identity, "en-IN", details, 5_000, true, includeDetails = true)
        val cached = CanonicalHomeMetadata.decode(row)

        assertEquals("IN", row.ageRatingRegion)
        assertEquals("TMDB", row.ageRatingSource)
        assertEquals(5_000L, row.creditsObservedAtMillis)
        assertEquals("U/A 16+", cached.ageRating)
        assertEquals("IN", cached.ageRatingRegion)
        assertEquals("2025-03-01", cached.digitalReleaseDate)
        assertEquals("IN", cached.digitalReleaseRegion)
        assertEquals("2025-04-01", cached.physicalReleaseDate)
        assertEquals("GB", cached.physicalReleaseRegion)
        assertEquals(details.castMembers, cached.castMembers)
        assertEquals(details.directorMembers, cached.directorMembers)
        assertEquals(details.writerMembers, cached.writerMembers)
    }

    @Test fun lightweightHomeWriteDoesNotInventTheFullDetailsMarker() {
        val row = CanonicalHomeMetadata.encode(identity, "en", projection(), 5_000, false)

        assertNull(row.creditsJson)
        assertNull(row.creditsSource)
        assertEquals(0L, row.creditsObservedAtMillis)
        assertNull(CanonicalHomeMetadata.decode(row).ageRating)
        assertNull(CanonicalHomeMetadata.decode(row).digitalReleaseDate)
        assertNull(CanonicalHomeMetadata.decode(row).physicalReleaseDate)
    }

    @Test fun fullDetailsRejectCreditsThatExceedTheDurableCacheBound() {
        val oversized = projection().copy(
            castMembers = listOf(TmdbCastInfo("x".repeat(270_000), null, null, 9))
        )

        assertThrows(IllegalArgumentException::class.java) {
            CanonicalHomeMetadata.encode(identity, "en", oversized, 5_000, false, includeDetails = true)
        }
    }

    @Test fun yearPrecisionAndForeignOrStaleFactsAreNotExposedAsTmdb() {
        val row = CanonicalHomeMetadata.encode(
            identity,
            "en",
            projection().copy(
                releaseInfo = "2024",
                ageRating = "U/A 13+",
                ageRatingRegion = "IN",
                digitalReleaseDate = "2024-02-01",
                digitalReleaseRegion = "IN",
                physicalReleaseDate = "2024-03-01",
                physicalReleaseRegion = "IN"
            ),
            5_000,
            false,
            includeDetails = true
        )
        assertEquals("YEAR", row.releaseDatePrecision)

        val foreignOrStale = CanonicalHomeMetadata.decode(row.copy(
            genresSource = "CINEMETA",
            genreIdsJson = "[18]",
            releaseDate = "2024-01-01",
            releaseDateSource = "CINEMETA",
            runtimeEstimateMillis = 3_600_000,
            runtimeSource = "CINEMETA",
            countriesJson = "[\"IN\"]",
            countriesSource = "CINEMETA",
            ageRating = "U/A 13+",
            ageRatingRegion = "IN",
            ageRatingSource = "TMDB",
            ageRatingObservedAtMillis = 0,
            digitalReleaseDate = "2024-02-01",
            digitalReleaseRegion = "IN",
            physicalReleaseDate = "2024-03-01",
            physicalReleaseRegion = "IN",
            releaseAvailabilitySource = "CINEMETA"
        ))
        assertTrue(foreignOrStale.genres.isEmpty())
        assertTrue(foreignOrStale.genreIds.isEmpty())
        assertNull(foreignOrStale.releaseInfo)
        assertNull(foreignOrStale.runtimeMinutes)
        assertNull(foreignOrStale.countries)
        assertNull(foreignOrStale.ageRating)
        assertNull(foreignOrStale.ageRatingRegion)
        assertNull(foreignOrStale.digitalReleaseDate)
        assertNull(foreignOrStale.digitalReleaseRegion)
        assertNull(foreignOrStale.physicalReleaseDate)
        assertNull(foreignOrStale.physicalReleaseRegion)
    }

    @Test fun detailsWithoutRatingDoesNotClaimAnAgeRatingAndMissingCreditPayloadIsRejected() {
        val row = CanonicalHomeMetadata.encode(
            identity,
            "en",
            projection(),
            5_000,
            false,
            includeDetails = true
        )
        assertEquals("TMDB", row.creditsSource)
        assertNull(row.ageRatingSource)
        assertEquals(0L, row.ageRatingObservedAtMillis)
        assertNull(CanonicalHomeMetadata.decode(row).ageRating)

        assertThrows(IllegalArgumentException::class.java) {
            CanonicalHomeMetadata.decode(row.copy(creditsJson = null))
        }
        assertTrue(CanonicalHomeMetadata.decode(row.copy(creditsObservedAtMillis = 0)).castMembers.isEmpty())
    }

    @Test fun creditIdentityIsOptionalAndMalformedStoredIdentityDegradesToUnknown() {
        val details = projection().copy(castMembers = listOf(
            TmdbCastInfo("No ID", null, null, null),
            TmdbCastInfo("Invalid ID", null, null, 0)
        ))
        val row = CanonicalHomeMetadata.encode(identity, "en", details, 5_000, false, includeDetails = true)
        assertTrue(CanonicalHomeMetadata.decode(row).castMembers.all { it.tmdbId == null })

        val malformedIdentities = listOf("tmdb/person//not-a-number", "person-without-delimiters")
            .map { id -> row.copy(creditsJson = """[{"role":"cast","personId":"$id","name":"Malformed","character":null,"photo":null}]""") }
        malformedIdentities.forEach { malformed ->
            assertNull(CanonicalHomeMetadata.decode(malformed).castMembers.single().tmdbId)
        }
    }

    @Test fun homeProjectionNeverRelabelsMergedProviderFactsAsTmdb() {
        val row = CanonicalHomeMetadata.encode(identity, "en", projection().copy(
            localizedTitle = "Cinemeta title", description = "Cinemeta synopsis",
            poster = "https://art/cinemeta-poster", backdrop = "https://art/cinemeta-backdrop",
            logo = "https://art/cinemeta-logo", rating = 8.1
        ), 1_000, true).copy(
            titleSource = "CINEMETA",
            synopsisSource = "CINEMETA",
            posterSource = "CINEMETA",
            backdropSource = "CINEMETA",
            logoSource = "CINEMETA",
            ratingSource = "IMDb",
            ratingScale = "0-10"
        )

        val projected = CanonicalHomeMetadata.decode(row)
        assertNull(projected.localizedTitle)
        assertNull(projected.description)
        assertNull(projected.poster)
        assertNull(projected.backdrop)
        assertNull(projected.logo)
        assertNull(projected.rating)
    }

    @Test fun tmdbRatingWithUnexpectedScaleIsNotExposedAsTmdbRating() {
        val row = CanonicalHomeMetadata.encode(identity, "en", projection().copy(rating = 8.1), 1_000, false)
        assertNull(CanonicalHomeMetadata.decode(row.copy(ratingScale = "0-100")).rating)
    }

    @Test fun corruptPersistedListsAreRejectedSoCallerCanRefetch() {
        val row = CanonicalHomeMetadata.encode(identity, "en", projection(), 1_000, true)
        listOf(row.copy(genresJson = "broken"), row.copy(genresJson = "[null]"), row.copy(genresJson = "[2]"), row.copy(genreIdsJson = "[\"bad\"]"),
            row.copy(countriesJson = "{", countriesSource = "TMDB")).forEach {
            assertThrows(RuntimeException::class.java) { CanonicalHomeMetadata.decode(it) }
        }
    }

    private fun projection() = TmdbEnrichment(
        localizedTitle = null, description = null, genres = emptyList(),
        backdrop = null, logo = null, poster = null,
        directorMembers = emptyList(), writerMembers = emptyList(), castMembers = emptyList(),
        releaseInfo = null, rating = null, runtimeMinutes = null,
        productionCompanies = emptyList(), networks = emptyList(), ageRating = null,
        status = null, countries = null, language = null, collectionId = null, collectionName = null
    )
}
