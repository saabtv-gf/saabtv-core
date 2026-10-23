package com.saab.tv.data.repository

import com.saab.tv.data.model.CanonicalTitleEntity
import com.saab.tv.domain.schema.CanonicalTextField
import com.saab.tv.domain.schema.CanonicalFieldResolver
import com.saab.tv.domain.schema.MetadataSource
import com.saab.tv.domain.schema.TextFieldCandidate
import org.junit.Assert.*
import org.junit.Test

class CanonicalTitleMergePolicyTest {
    @Test fun diagnosticSummaryReportsOnlyPolicyAndProvenanceNotMetadataValues() {
        val title = CanonicalFieldResolver.select(
            CanonicalTextField.TITLE,
            listOf(TextFieldCandidate(
                field = CanonicalTextField.TITLE,
                value = "Private title text",
                source = MetadataSource.TMDB,
                languageTag = "en",
                observedAtMillis = 10,
                identityVerified = true
            )),
            requestedLanguage = "en",
            nowMillis = 20
        )
        val summary = CanonicalTitleMergePolicy.diagnosticSummary(
            "a".repeat(64),
            mapOf(CanonicalTextField.TITLE to title)
        )

        assertTrue(summary.contains("policyVersion=2"))
        assertTrue(summary.contains("identity=${"a".repeat(12)}"))
        assertTrue(summary.contains("title=TMDB:language_match".lowercase()))
        assertFalse(summary.contains("Private title text"))
    }

    @Test fun diagnosticSummaryOmitsUnvalidatedIdentityAndUnselectedFields() {
        val summary = CanonicalTitleMergePolicy.diagnosticSummary("provider:raw-id", emptyMap())

        assertTrue(summary.contains("identity=unavailable"))
        assertTrue(summary.endsWith("fields="))
        assertFalse(summary.contains("provider:raw-id"))
    }

    @Test fun partialNewObservationPreservesExistingArtworkAndUsesFieldAuthority() {
        val existing = row("old", 10).copy(
            title = "My title", titleSource = "USER_OVERRIDE", titleObservedAtMillis = 10,
            synopsis = "Synopsis", synopsisSource = "TMDB", synopsisLanguage = "en", synopsisObservedAtMillis = 10,
            poster = "poster", posterSource = "TMDB", posterObservedAtMillis = 10,
            backdrop = "backdrop", backdropSource = "CINEMETA", backdropObservedAtMillis = 10,
            logo = "logo", logoSource = "TMDB", logoLanguage = "en", logoObservedAtMillis = 10,
            genresJson = "[\"Drama\"]", genreIdsJson = "[18]", genresSource = "TMDB", genresObservedAtMillis = 10,
            releaseDate = "2025-01-02", releaseDatePrecision = "DATE", releaseDateSource = "TMDB", releaseDateObservedAtMillis = 10,
            ratingValue = 8.0, ratingSource = "TMDB", ratingScale = "0-10", ratingObservedAtMillis = 10,
            runtimeEstimateMillis = 120_000, runtimeSource = "TMDB", runtimeObservedAtMillis = 10,
            status = "Released", statusSource = "TMDB", statusObservedAtMillis = 10,
            countriesJson = "[\"IN\"]", countriesSource = "TMDB", countriesObservedAtMillis = 10,
            originalLanguage = "en", originalLanguageSource = "TMDB", originalLanguageObservedAtMillis = 10,
            collectionId = 9, collectionName = "Collection", collectionSource = "TMDB", collectionObservedAtMillis = 10
        )
        val partial = row("new", 20).copy(title = "Provider title", titleSource = "ADDON", titleObservedAtMillis = 20,
            ratingSource = "Different", ratingScale = "0-100", releaseDatePrecision = "year", collectionName = "Wrong name")
        val forward = CanonicalTitleMergePolicy.merge(listOf(existing, partial), "root", 30)
        val reversed = CanonicalTitleMergePolicy.merge(listOf(partial, existing), "root", 30)
        assertEquals(forward, reversed)
        assertEquals(existing.copy(identityKey = "root", lastFetchedAtMillis = 20), forward)
    }

    @Test fun localizedTextAndFreshArtworkAreSelectedIndependently() {
        val older = row("a", 10).copy(title = "Wrong language", titleSource = "TMDB", titleLanguage = "fr", titleObservedAtMillis = 10,
            synopsis = "Old", synopsisSource = "TMDB", synopsisLanguage = "en", synopsisObservedAtMillis = 10,
            poster = "old", posterSource = "CINEMETA", posterObservedAtMillis = 10,
            backdrop = "old", backdropSource = "TMDB", backdropObservedAtMillis = 10,
            logo = "Old logo", logoSource = "TMDB", logoLanguage = "fr", logoObservedAtMillis = 10)
        val newer = row("b", 20).copy(title = "English", titleSource = "CINEMETA", titleLanguage = "en", titleObservedAtMillis = 20,
            synopsis = "New", synopsisSource = "TMDB", synopsisLanguage = "en", synopsisObservedAtMillis = 20,
            poster = "new", posterSource = "TMDB", posterObservedAtMillis = 20,
            backdrop = "new", backdropSource = "TMDB", backdropObservedAtMillis = 20,
            logo = "English logo", logoSource = "CINEMETA", logoLanguage = "en", logoObservedAtMillis = 20)
        val merged = CanonicalTitleMergePolicy.merge(listOf(older, newer), "root", 30)
        assertEquals("English", merged.title)
        assertEquals("New", merged.synopsis)
        assertEquals("old", merged.poster)
        assertEquals("new", merged.backdrop)
        assertEquals("English logo", merged.logo)
        assertEquals(20L, merged.logoObservedAtMillis)
    }

    @Test fun emptyInvalidAndFutureObservationsNeverBecomeCanonicalValues() {
        val invalid = row("a", 20).copy(title = "Future", titleSource = "TMDB", titleObservedAtMillis = 31,
            synopsis = "Unknown", synopsisSource = "UNKNOWN", poster = " ", posterSource = "TMDB",
            backdrop = "Unattributed", logoObservedAtMillis = 20)
        val empty = row("b", 20)
        val merged = CanonicalTitleMergePolicy.merge(listOf(empty, invalid), "root", 30)
        assertNull(merged.title)
        assertNull(merged.synopsis)
        assertNull(merged.poster)
        assertNull(merged.backdrop)
        assertNull(merged.logo)
        assertEquals(20L, merged.logoObservedAtMillis)
        assertNull(merged.genresJson)
        assertNull(merged.ratingValue)
        assertNull(merged.releaseDate)
        assertNull(merged.collectionId)
    }

    @Test fun mergesRejectEmptyInputOrDifferentKindsOrLocales() {
        assertThrows(IllegalArgumentException::class.java) { CanonicalTitleMergePolicy.merge(emptyList(), "root", 30) }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalTitleMergePolicy.merge(listOf(row("a", 10), row("b", 20).copy(kind = "series")), "root", 30)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalTitleMergePolicy.merge(listOf(row("a", 10), row("b", 20).copy(locale = "te")), "root", 30)
        }
    }

    @Test fun scalarFactsPreferProviderAuthorityBeforeFreshnessAndKeepFieldProvenance() {
        val trusted = row("imdb", 10).copy(
            genresJson = "[\"Drama\"]", genreIdsJson = "[18]", genresSource = "TMDB", genresObservedAtMillis = 10,
            releaseDate = "2024-05-01", releaseDatePrecision = "DATE", releaseDateSource = "TMDB", releaseDateObservedAtMillis = 10,
            runtimeEstimateMillis = 90_000, runtimeSource = "CINEMETA", runtimeObservedAtMillis = 10
        )
        val freshButLowerPriority = row("addon", 20).copy(
            genresJson = "[\"Other\"]", genresSource = "ADDON", genresObservedAtMillis = 20,
            releaseDate = "2024", releaseDatePrecision = "YEAR", releaseDateSource = "CINEMETA", releaseDateObservedAtMillis = 20,
            runtimeEstimateMillis = 100_000, runtimeSource = "ADDON", runtimeObservedAtMillis = 20
        )

        val merged = CanonicalTitleMergePolicy.merge(listOf(trusted, freshButLowerPriority), "root", 30)

        assertEquals("[\"Drama\"]", merged.genresJson)
        assertEquals("TMDB", merged.genresSource)
        assertEquals(10L, merged.genresObservedAtMillis)
        assertEquals("2024", merged.releaseDate)
        assertEquals("CINEMETA", merged.releaseDateSource)
        assertEquals(90_000L, merged.runtimeEstimateMillis)
        assertEquals("CINEMETA", merged.runtimeSource)
    }

    @Test fun newerLightweightObservationPreservesPreviouslyFetchedFullDetails() {
        val full = row("full", 10).copy(
            ageRating = "U/A 16+", ageRatingRegion = "IN", ageRatingSource = "TMDB",
            ageRatingObservedAtMillis = 10,
            creditsJson = "[{\"role\":\"cast\",\"name\":\"Actor\"}]",
            creditsSource = "TMDB", creditsObservedAtMillis = 10,
            digitalReleaseDate = "2025-03-01", digitalReleaseRegion = "IN",
            physicalReleaseDate = "2025-04-01", physicalReleaseRegion = "IN",
            releaseAvailabilitySource = "TMDB", releaseAvailabilityObservedAtMillis = 10
        )
        val lightweight = row("home", 20).copy(title = "Fresh title", titleSource = "TMDB", titleObservedAtMillis = 20)

        val merged = CanonicalTitleMergePolicy.merge(listOf(full, lightweight), "root", 30)

        assertEquals("U/A 16+", merged.ageRating)
        assertEquals("IN", merged.ageRatingRegion)
        assertEquals("TMDB", merged.ageRatingSource)
        assertEquals(10L, merged.ageRatingObservedAtMillis)
        assertEquals(full.creditsJson, merged.creditsJson)
        assertEquals(10L, merged.creditsObservedAtMillis)
        assertEquals("2025-03-01", merged.digitalReleaseDate)
        assertEquals("IN", merged.digitalReleaseRegion)
        assertEquals("2025-04-01", merged.physicalReleaseDate)
        assertEquals("IN", merged.physicalReleaseRegion)
        assertEquals("TMDB", merged.releaseAvailabilitySource)
        assertEquals(10L, merged.releaseAvailabilityObservedAtMillis)
        assertEquals("Fresh title", merged.title)
    }

    @Test fun ratingSourceAliasesAreNormalizedAndUnknownSourcesAreRejected() {
        val imdb = CanonicalTitleMergePolicy.merge(
            listOf(row("imdb", 10).copy(ratingValue = 7.1, ratingSource = " imdb ", ratingScale = "0-10")),
            "root", 20
        )
        val cinemeta = CanonicalTitleMergePolicy.merge(
            listOf(row("cinemeta", 10).copy(ratingValue = 7.2, ratingSource = "CINEMETA", ratingScale = "0-10")),
            "root", 20
        )
        val addon = CanonicalTitleMergePolicy.merge(
            listOf(row("addon", 10).copy(ratingValue = 7.3, ratingSource = "ADDON", ratingScale = "provider")),
            "root", 20
        )
        val unknown = CanonicalTitleMergePolicy.merge(
            listOf(row("unknown", 10).copy(ratingValue = 9.9, ratingSource = "OTHER", ratingScale = "0-10")),
            "root", 20
        )

        assertEquals(" imdb ", imdb.ratingSource)
        assertEquals("CINEMETA", cinemeta.ratingSource)
        assertEquals("ADDON", addon.ratingSource)
        assertNull(unknown.ratingValue)
    }

    private fun row(id: String, at: Long) = CanonicalTitleEntity(id, "en", "movie", lastFetchedAtMillis = at)
}
