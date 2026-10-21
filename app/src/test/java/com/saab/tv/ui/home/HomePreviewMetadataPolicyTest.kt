package com.saab.tv.ui.home

import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.CategoryRow
import com.saab.tv.domain.schema.CatalogCardProjection
import com.saab.tv.domain.schema.canonicalPresentationKey
import org.junit.Assert.*
import org.junit.Test

class HomePreviewMetadataPolicyTest {
    @Test
    fun moviesAndSeriesUseSmallerFirstPaintBatchWhileHomeKeepsItsDefault() {
        assertEquals(3, initialDashboardBatchSizeFor("movies", 6))
        assertEquals(3, initialDashboardBatchSizeFor("series", 6))
        assertEquals(6, initialDashboardBatchSizeFor("home", 6))
        assertEquals(2, initialDashboardBatchSizeFor("movies", 2))
    }

    @Test
    fun previewRowIndexKeepsTheFirstCanonicalMatch() {
        val firstRowItem = CatalogCardProjection.from(MetaItem("tt-index", "movie", "First rail"))
        val secondRowItem = CatalogCardProjection.from(MetaItem("tt-index", "movie", "Second rail"))
        val index = homePreviewMetadataIndex(
            rows = listOf(
                CategoryRow("first", "First", 1, listOf(firstRowItem)),
                CategoryRow("second", "Second", 2, listOf(secondRowItem))
            )
        )

        assertEquals("First rail", index.rowsByKey[firstRowItem.asActionItem().canonicalPresentationKey()]?.name)
    }

    @Test
    fun initialArtworkPrefetchMatchesTheConfiguredCardShape() {
        val item = MetaItem(
            id = "tt-artwork", type = "series", poster = "poster-url",
            background = "backdrop-url", logo = "logo-url"
        )

        val poster = HomePreviewMetadataPolicy.initialArtwork(item, isLandscape = false)
        assertEquals("poster-url", poster.poster)
        assertNull(poster.backdrop)
        assertNull(poster.logo)

        val landscape = HomePreviewMetadataPolicy.initialArtwork(item, isLandscape = true)
        assertNull(landscape.poster)
        assertEquals("backdrop-url", landscape.backdrop)
        assertEquals("logo-url", landscape.logo)
    }

    @Test
    fun tmdbIsRequestedOnlyForCinemetaGapsNeededByTheCurrentCardShape() {
        val complete = MetaItem(
            id = "tt-artwork", type = "series", name = "Cinemeta title", poster = "poster",
            background = "backdrop", logo = "logo", description = "Synopsis", releaseInfo = "2025",
            imdbRating = "8.0", runtime = "45m", genres = listOf("Drama"),
            addonBaseUrl = "https://v3-cinemeta.strem.io"
        )

        assertFalse(needsTmdbEnrichment(complete, includeLogo = true))
        assertFalse(needsTmdbEnrichment(complete.copy(logo = null), includeLogo = false))
        assertTrue(needsTmdbEnrichment(complete.copy(logo = null), includeLogo = true))
        assertTrue(needsTmdbEnrichment(complete.copy(background = null), includeLogo = true))
        assertFalse(needsTmdbEnrichment(
            complete.copy(background = null, logo = null, imdbRating = null, runtime = null, genres = null),
            includeLogo = false
        ))
        assertTrue(needsTmdbEnrichment(complete.copy(poster = null), includeLogo = false))
    }

    @Test
    fun rapidFocusOnlyWarmsMetadataAfterThrottleWhileSettledFocusWarmsImmediately() {
        assertFalse(HomePreviewMetadataPolicy.shouldWarmFocusedMetadata(true, 119, 120))
        assertTrue(HomePreviewMetadataPolicy.shouldWarmFocusedMetadata(true, 120, 120))
        assertTrue(HomePreviewMetadataPolicy.shouldWarmFocusedMetadata(false, 0, 120))
    }

    @Test
    fun enrichedRatingWinsEvenWhenEnrichmentHasNoLogo() {
        val stale = MetaItem(id = "tt123", type = "movie", imdbRating = "6.1", logo = "old")
        val enriched = stale.copy(imdbRating = "8.4", logo = null)

        val selected = HomePreviewMetadataPolicy.select(
            current = stale,
            enriched = enriched,
            hero = stale,
            row = stale,
            history = null
        )

        assertEquals("8.4", selected.imdbRating)
    }

    @Test
    fun updatedRowWinsOverSparseHistoryMetadata() {
        val current = MetaItem(id = "tt456", type = "series")
        val row = current.copy(imdbRating = "9.0")
        val history = current.copy(name = "Saved History Title")

        val selected = HomePreviewMetadataPolicy.select(
            current = current,
            enriched = null,
            hero = null,
            row = row,
            history = history
        )

        assertEquals("9.0", selected.imdbRating)
    }

    @Test
    fun cinemetaFieldsRemainCanonicalWhenTmdbReturnsConflictingMetadata() {
        val current = MetaItem(
            id = "tt7654321", type = "movie", name = "Provider title",
            poster = "provider-poster", background = "provider-backdrop", logo = "provider-logo",
            description = "Provider synopsis", imdbRating = "8.0",
            addonBaseUrl = "https://v3-cinemeta.strem.io"
        )
        val enriched = current.copy(
            name = "Localized title", poster = "tmdb-poster", background = "tmdb-backdrop",
            logo = "tmdb-logo", description = "TMDB synopsis", imdbRating = "6.0"
        )

        val selected = HomePreviewMetadataPolicy.select(current, enriched, null, null, null)

        assertEquals("Provider title", selected.name)
        assertEquals("Provider synopsis", selected.description)
        assertEquals("provider-poster", selected.poster)
        assertEquals("provider-backdrop", selected.background)
        assertEquals("provider-logo", selected.logo)
        assertEquals("8.0", selected.imdbRating)
    }

    @Test
    fun tmdbFillsOnlyMetadataMissingFromCinemeta() {
        val current = MetaItem(
            id = "tt7654322", type = "movie", name = "Cinemeta title", poster = "cinemeta-poster",
            addonBaseUrl = "https://v3-cinemeta.strem.io"
        )
        val enriched = current.copy(
            description = "TMDB synopsis", poster = "tmdb-poster", background = "tmdb-backdrop",
            logo = "tmdb-logo", releaseInfo = "2025", runtime = "105m", genres = listOf("Drama")
        )

        val selected = HomePreviewMetadataPolicy.select(current, enriched, null, null, null)

        assertEquals("Cinemeta title", selected.name)
        assertEquals("cinemeta-poster", selected.poster)
        assertEquals("TMDB synopsis", selected.description)
        assertEquals("tmdb-backdrop", selected.background)
        assertEquals("tmdb-logo", selected.logo)
        assertEquals("2025", selected.releaseInfo)
        assertEquals("105m", selected.runtime)
        assertEquals(listOf("Drama"), selected.genres)
    }

    @Test
    fun lateCinemetaResolutionReplacesEarlierAddonOrTmdbCardMetadata() {
        val original = MetaItem(
            id = "tt7654323", type = "movie", name = "Addon title", poster = "addon-poster",
            background = "tmdb-backdrop", description = "TMDB synopsis",
            addonBaseUrl = "https://addon.example"
        )
        val cinemeta = original.copy(
            name = "Cinemeta title", poster = "cinemeta-poster", background = "cinemeta-backdrop",
            description = "Cinemeta synopsis", addonBaseUrl = "https://v3-cinemeta.strem.io"
        )

        val selected = HomePreviewMetadataPolicy.select(original, cinemeta, null, null, null)

        assertEquals("Cinemeta title", selected.name)
        assertEquals("Cinemeta synopsis", selected.description)
        assertEquals("cinemeta-poster", selected.poster)
        assertEquals("cinemeta-backdrop", selected.background)
    }

    @Test
    fun metadataFromAnotherIdentityCannotLeakIntoCurrentPreview() {
        val current = MetaItem(id = "tt1234567", type = "series", name = "Correct", poster = "correct")
        val foreign = MetaItem(id = "tt9999999", type = "series", name = "Wrong", poster = "wrong", imdbRating = "9.9", runtime = "99 min", genres = listOf("Foreign genre"))

        val selected = HomePreviewMetadataPolicy.select(current, foreign, foreign, foreign, foreign)

        assertEquals("Correct", selected.name)
        assertEquals("correct", selected.poster)
        assertNull(selected.imdbRating)
        assertNull(selected.runtime)
        assertNull(selected.genres)
    }

    @Test
    fun emptyTextCandidatesReturnTheCurrentItemWithoutErasingItsFallbackValues() {
        val current = MetaItem(
            id = "ttempty", type = "movie", name = "  ", description = " ",
            poster = "", background = "  ", logo = ""
        )

        val selected = HomePreviewMetadataPolicy.select(current, null, null, null, null)

        assertSame(current, selected)
    }

    @Test
    fun missingTitleCandidateKeepsRequiredFallbackWhenOtherCanonicalFieldsExist() {
        val current = MetaItem(id = "ttposteronly", type = "movie", name = "", poster = "poster")

        val selected = HomePreviewMetadataPolicy.select(current, null, null, null, null)

        assertEquals("", selected.name)
        assertEquals("poster", selected.poster)
    }

    @Test
    fun opaqueIdsFromDifferentAddonInstallationsCannotSharePreviewMetadata() {
        val current = MetaItem(
            id = "catalog:42", type = "movie", name = "Right title", poster = "right-poster",
            addonBaseUrl = "https://one.example/manifest.json"
        )
        val otherInstall = current.copy(
            name = "Wrong title", poster = "wrong-poster", imdbRating = "9.9",
            addonBaseUrl = "https://two.example/manifest.json"
        )

        val selected = HomePreviewMetadataPolicy.select(
            current = current,
            enriched = otherInstall,
            hero = otherInstall,
            row = otherInstall,
            history = null
        )

        assertEquals("Right title", selected.name)
        assertEquals("right-poster", selected.poster)
        assertNull(selected.imdbRating)
    }

    @Test
    fun unusableScalarMetadataIsIgnoredAndExistingFallbacksArePreserved() {
        val current = MetaItem(
            id = "tt7654999", type = "movie", releaseInfo = " ", imdbRating = "",
            runtime = "  ", genres = emptyList()
        )
        val selected = HomePreviewMetadataPolicy.select(
            current = current,
            enriched = current.copy(),
            hero = null,
            row = null,
            history = null
        )

        assertEquals(" ", selected.releaseInfo)
        assertEquals("", selected.imdbRating)
        assertEquals("  ", selected.runtime)
        assertEquals(emptyList<String>(), selected.genres)
    }

    @Test
    fun unchangedUsableEnrichmentDoesNotReplaceProviderScalarValues() {
        val current = MetaItem(
            id = "tt7654998", type = "movie", releaseInfo = "2024", imdbRating = "7.1",
            runtime = "98 min", genres = listOf("Drama")
        )

        assertEquals(
            current,
            HomePreviewMetadataPolicy.select(current, current.copy(), null, null, null)
        )
    }
}
