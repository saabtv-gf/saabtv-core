package com.saab.tv.ui.details

import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.data.tmdb.TmdbEnrichment
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailsBackdropResolutionTest {
    @Test fun canonicalAndProviderArtworkStayAheadOfTmdbBackdropFallback() {
        assertEquals(
            "canonical-backdrop",
            resolveDetailsBackdropUrl(
                tmdbBackdrop = "tmdb-backdrop",
                canonicalBackdrop = "canonical-backdrop",
                providerBackdrop = "provider-backdrop",
                canonicalPoster = "canonical-poster",
                providerPoster = "provider-poster"
            )
        )
    }

    @Test fun detailsUsesCanonicalAndProviderArtworkWhenTmdbBackdropIsMissing() {
        assertEquals(
            "canonical-backdrop",
            resolveDetailsBackdropUrl(null, "canonical-backdrop", "provider-backdrop", null, null)
        )
        assertEquals(
            "provider-backdrop",
            resolveDetailsBackdropUrl(null, " ", "provider-backdrop", null, null)
        )
    }

    @Test fun detailsFallsBackToPosterRatherThanRenderingAnEmptyBackdrop() {
        assertEquals(
            "canonical-poster",
            resolveDetailsBackdropUrl(null, null, null, "canonical-poster", "provider-poster")
        )
        assertEquals(
            "provider-poster",
            resolveDetailsBackdropUrl(null, null, null, "", "provider-poster")
        )
        assertEquals(null, resolveDetailsBackdropUrl(" ", null, "", null, null))
    }

    @Test fun backdropCandidatesKeepCinemetaFirstThenTmdbAndPosterFallbacks() {
        assertEquals(
            listOf("canonical-backdrop", "provider-backdrop", "tmdb-backdrop", "tmdb-poster", "provider-poster"),
            detailsBackdropCandidates(
                "tmdb-backdrop", "canonical-backdrop", "provider-backdrop", "tmdb-poster", "provider-poster"
            )
        )
    }

    @Test fun tmdbDetailsFillOnlyMissingCinemetaFields() {
        val current = MetaItem(
            id = "tt1234567", type = "movie", name = "Cinemeta title",
            description = "Cinemeta synopsis", poster = "cinemeta-poster",
            background = "cinemeta-backdrop", logo = "cinemeta-logo",
            releaseInfo = "2024", runtime = "100m", genres = listOf("Drama")
        )
        val enrichment = TmdbEnrichment(
            localizedTitle = "TMDB title", description = "TMDB synopsis", genres = listOf("Thriller"),
            backdrop = "tmdb-backdrop", logo = "tmdb-logo", poster = "tmdb-poster",
            directorMembers = emptyList(), writerMembers = emptyList(), castMembers = emptyList(),
            releaseInfo = "2025", rating = 8.0, runtimeMinutes = 120,
            productionCompanies = emptyList(), networks = emptyList(), ageRating = null,
            status = null, countries = null, language = null, collectionId = null, collectionName = null
        )

        val merged = mergeTmdbDetailsFallback(current, enrichment)

        assertEquals("Cinemeta title", merged.name)
        assertEquals("Cinemeta synopsis", merged.description)
        assertEquals("cinemeta-poster", merged.poster)
        assertEquals("cinemeta-backdrop", merged.background)
        assertEquals("cinemeta-logo", merged.logo)
        assertEquals("2024", merged.releaseInfo)
        assertEquals("100m", merged.runtime)
        assertEquals(listOf("Drama"), merged.genres)

        val sparse = mergeTmdbDetailsFallback(current.copy(description = null, background = " ", genres = emptyList()), enrichment)
        assertEquals("TMDB synopsis", sparse.description)
        assertEquals("tmdb-backdrop", sparse.background)
        assertEquals(listOf("Thriller"), sparse.genres)
    }

    @Test fun failedBackdropAdvancesThroughCandidatesButStopsAtTheLastOne() {
        assertEquals(1, nextDetailsBackdropIndex(0, 3))
        assertEquals(2, nextDetailsBackdropIndex(1, 3))
        assertEquals(null, nextDetailsBackdropIndex(2, 3))
        assertEquals(null, nextDetailsBackdropIndex(0, 0))
    }
}
