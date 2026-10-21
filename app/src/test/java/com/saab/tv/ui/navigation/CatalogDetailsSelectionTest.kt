package com.saab.tv.ui.navigation

import com.saab.tv.data.model.stremio.MetaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogDetailsSelectionTest {
    @Test fun catalogSelectionCarriesTheChosenTitleAndItsPlaybackDefaults() {
        val selected = MetaItem(
            id = "tmdb:42",
            type = "movie",
            name = "Selected Film",
            poster = "poster",
            background = "backdrop",
            logo = "logo",
            addonBaseUrl = "https://addon.invalid"
        ).toCatalogDetailsSelection(returnDestination = ShellView.Grid)

        assertEquals("tmdb:42", selected.contentId)
        assertEquals("movie", selected.contentType)
        assertEquals("Selected Film", selected.title)
        assertEquals("tmdb:42", selected.playbackId)
        assertEquals("Selected Film", selected.playbackTitle)
        assertEquals("poster", selected.playbackPoster)
        assertEquals(ShellView.Grid, selected.returnDestination)
    }

    @Test fun catalogSelectionForSeriesKeepsTheSelectedIdentityAndArtworkForPlayback() {
        val selected = MetaItem(
            id = "tmdb:77",
            type = "series",
            name = "Related Series",
            poster = "series-poster",
            background = "series-backdrop",
            logo = "series-logo",
            addonBaseUrl = "https://series-addon.invalid"
        ).toCatalogDetailsSelection()

        assertEquals("tmdb:77", selected.contentId)
        assertEquals("series", selected.contentType)
        assertEquals("Related Series", selected.title)
        assertEquals("https://series-addon.invalid", selected.addonBaseUrl)
        assertEquals("tmdb:77", selected.playbackId)
        assertEquals("series", selected.playbackType)
        assertEquals("Related Series", selected.playbackTitle)
        assertEquals("series-poster", selected.playbackPoster)
        assertEquals(ShellView.Menu, selected.returnDestination)
    }

    @Test fun trailerPlaybackSelectionKeepsTheRelatedTitleAndRestoresItsBrowseParent() {
        val selected = MetaItem(
            id = "tmdb:related-series",
            type = "series",
            name = "Related Series",
            poster = "related-poster",
            background = "related-backdrop",
            logo = "related-logo",
            addonBaseUrl = "https://related-addon.invalid"
        ).toCatalogPlaybackSelection(currentView = ShellView.Details, previousView = ShellView.Grid)

        assertEquals("tmdb:related-series", selected.contentId)
        assertEquals("Related Series", selected.playbackTitle)
        assertEquals("related-poster", selected.playbackPoster)
        assertEquals(ShellView.Grid, selected.returnDestination)
    }

    @Test fun trailerHandoffRouteTargetsTheSelectedRelatedTitleAndPreservesItsPlaybackContext() {
        val related = MetaItem(
            id = "tmdb:related/series",
            type = "series",
            addonBaseUrl = "https://addon.invalid/path",
            resumePlaybackId = "tmdb:related:2:5"
        )

        assertEquals(
            "detail/series/tmdb%3Arelated%2Fseries?addon=https%3A%2F%2Faddon.invalid%2Fpath&resume=tmdb%3Arelated%3A2%3A5",
            related.trailerHandoffRoute(currentType = "movie", currentId = "tmdb:details")
        )
    }

    @Test fun trailerHandoffDoesNotPushDuplicateRouteForCurrentTitle() {
        val current = MetaItem(id = "tmdb:current", type = "series")

        assertEquals(
            null,
            current.trailerHandoffRoute(
                currentType = "SERIES",
                currentId = "tmdb:current",
                currentAddonBaseUrl = null,
                currentResumePlaybackHint = null
            )
        )
    }

    @Test fun trailerHandoffTreatsBlankRouteContextAsMissing() {
        val current = MetaItem(
            id = "tmdb:current",
            type = "series",
            addonBaseUrl = "",
            resumePlaybackId = ""
        )

        assertEquals(
            null,
            current.trailerHandoffRoute(
                currentType = "series",
                currentId = "tmdb:current",
                currentAddonBaseUrl = null,
                currentResumePlaybackHint = null
            )
        )
    }

    @Test fun trailerHandoffNavigatesWhenTheSameTitleUsesADifferentAddon() {
        val selected = MetaItem(
            id = "tt-same-title",
            type = "movie",
            addonBaseUrl = "https://new-addon.invalid"
        )

        assertEquals(
            "detail/movie/tt-same-title?addon=https%3A%2F%2Fnew-addon.invalid&resume=",
            selected.trailerHandoffRoute(
                currentType = "movie",
                currentId = "tt-same-title",
                currentAddonBaseUrl = "https://old-addon.invalid",
                currentResumePlaybackHint = null
            )
        )
    }

    @Test fun trailerHandoffReplacesCurrentRouteWhenTheRequestedEpisodeDiffers() {
        val currentTitle = MetaItem(id = "tmdb:current", type = "series")
        val requestedEpisode = currentTitle.copy(resumePlaybackId = "tmdb:current:2:5")

        assertEquals(
            "detail/series/tmdb%3Acurrent?addon=&resume=tmdb%3Acurrent%3A2%3A5",
            requestedEpisode.trailerHandoffRoute(
                currentType = "series",
                currentId = "tmdb:current",
                currentAddonBaseUrl = null,
                currentResumePlaybackHint = null
            )
        )
    }

    @Test fun nestedSelectionReplacesPriorMetadataRatherThanLeakingItToTheNextRoute() {
        val selected = nestedCatalogDetailsSelection("series", "tt-series:2:5")

        assertEquals("tt-series:2:5", selected.contentId)
        assertEquals("series", selected.contentType)
        assertEquals("", selected.title)
        assertEquals("tt-series:2:5", selected.playbackId)
        assertEquals("", selected.playbackTitle)
        assertEquals("", selected.poster)
        assertEquals(null, selected.addonBaseUrl)
    }
}
