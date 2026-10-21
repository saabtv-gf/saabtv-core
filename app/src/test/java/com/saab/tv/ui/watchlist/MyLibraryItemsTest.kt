package com.saab.tv.ui.watchlist

import com.saab.tv.R
import com.saab.tv.data.model.SeriesNextUpEntity
import com.saab.tv.data.model.WatchHistoryEntity
import com.saab.tv.data.model.WatchlistEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyLibraryItemsTest {
    @Test fun stableLibrarySectionKeysResolveToLocalizedPresentationResources() {
        assertEquals(R.string.library_continue_watching, librarySectionTitleResource(LIBRARY_CONTINUE_WATCHING))
        assertEquals(R.string.library_filter_watchlist, librarySectionTitleResource(LIBRARY_WATCHLIST))
        assertEquals(R.string.library_filter_watched, librarySectionTitleResource(LIBRARY_WATCHED))
    }

    @Test fun groupsMoviesByProgressWatchlistAndWatchedState() {
        val items = buildMyLibraryItems(
            history = listOf(
                history("tt-progress", "movie", position = 600, watched = false),
                history("tt-done", "movie", position = 900, watched = true),
                history("tt-zero", "movie", position = 0, watched = false)
            ),
            watchlist = listOf(WatchlistEntity(
                1, "tt-saved", "movie", "Saved", "saved.jpg", 20,
                background = "saved-backdrop.jpg", logo = "saved-logo.png"
            )),
            nextUp = emptyList()
        )

        assertEquals(listOf("tt-progress"), items.inProgress.map { it.asActionItem().id })
        assertEquals(listOf("tt-saved"), items.watchlist.map { it.asActionItem().id })
        assertEquals("saved-backdrop.jpg", items.watchlist.single().asActionItem().background)
        assertEquals("saved-logo.png", items.watchlist.single().asActionItem().logo)
        assertEquals(listOf("tt-done"), items.watched.map { it.asActionItem().id })
        assertEquals("tt-progress", items.inProgress.single().resumePlaybackId)
        assertTrue(items.inProgress.single().progress > 0f)
    }

    @Test fun seriesEpisodesCollapseIntoOneSeriesAndUseSeriesTitle() {
        val items = buildMyLibraryItems(
            history = listOf(
                history("tt-series:1:2", "series", title = "Episode Two", position = 100, timestamp = 2),
                history("tt-series:1:3", "series", title = "Episode Three", position = 200, timestamp = 3),
                history("tt-watched:1:1", "series", title = "Pilot", position = 500, watched = true)
            ),
            watchlist = emptyList(),
            nextUp = listOf(
                SeriesNextUpEntity(1, "tt-series", "The Show", null, 1, 4, "Next", updatedAt = 3),
                SeriesNextUpEntity(1, "tt-watched", "Finished Show", null, 0, 0, null, isComplete = true, updatedAt = 1)
            )
        )

        assertEquals(1, items.inProgress.size)
        assertEquals("tt-series", items.inProgress.single().asActionItem().id)
        assertEquals("The Show", items.inProgress.single().asActionItem().name)
        assertEquals("tt-series:1:3", items.inProgress.single().resumePlaybackId)
        assertEquals(200L, items.inProgress.single().positionMs)
        assertEquals(1_000L, items.inProgress.single().durationMs)
        assertEquals(listOf("tt-watched"), items.watched.map { it.asActionItem().id })
        assertEquals("Finished Show", items.watched.single().asActionItem().name)
    }

    @Test fun sortReusesCatalogSortOptionsAndFeaturedKeepsRecentLibraryOrder() {
        val library = buildMyLibraryItems(
            history = emptyList(),
            watchlist = listOf(
                WatchlistEntity(1, "tt-zulu", "movie", "Zulu", null, addedAt = 20),
                WatchlistEntity(1, "tt-alpha", "movie", "Alpha", null, addedAt = 10)
            ),
            nextUp = emptyList()
        )
        val section = filterMyLibrary(
            library,
            MyLibraryStatusFilter.WATCHLIST,
            MyLibraryMediaFilter.ALL
        )

        assertEquals(
            listOf("tt-zulu", "tt-alpha"),
            sortMyLibrarySections(section, com.saab.tv.ui.home.CatalogGridSort.FEATURED)
                .single().items.map { it.asActionItem().id }
        )
        assertEquals(
            listOf("tt-alpha", "tt-zulu"),
            sortMyLibrarySections(section, com.saab.tv.ui.home.CatalogGridSort.TITLE)
                .single().items.map { it.asActionItem().id }
        )
    }

    @Test fun watchedTitleIsNotAlsoShownInProgressAndEmptyInputIsSafe() {
        val item = history("tt-same", "movie", position = 99, watched = true)
        val watched = buildMyLibraryItems(listOf(item), emptyList(), emptyList())
        assertTrue(watched.inProgress.isEmpty())
        assertEquals(listOf("tt-same"), watched.watched.map { it.asActionItem().id })

        val empty = buildMyLibraryItems(emptyList(), emptyList(), emptyList())
        assertFalse(empty.inProgress.isNotEmpty() || empty.watchlist.isNotEmpty() || empty.watched.isNotEmpty())
    }

    @Test fun canonicalCardsKeepExactResumeAndOpaqueAddonActionsAtBoundary() {
        val canonical = buildMyLibraryItems(
            history = listOf(history("tt1234567:2:5", "series", title = "Episode Five", position = 950, timestamp = 5)),
            watchlist = emptyList(),
            nextUp = emptyList()
        ).inProgress.single()
        assertEquals("imdb/series//tt1234567", canonical.content?.identity?.stableKey)
        assertEquals("tt1234567:2:5", canonical.asActionItem().resumePlaybackId)

        val opaque = buildMyLibraryItems(
            history = listOf(history("catalog:show:episode-5", "series", position = 500, timestamp = 6)),
            watchlist = emptyList(),
            nextUp = emptyList()
        ).inProgress.single()
        assertNull(opaque.content)
        assertEquals("catalog:show:episode-5", opaque.asActionItem().id)
    }

    @Test fun opaqueWatchlistIdentityIsTypedWhenAddonInstallationIsKnown() {
        val card = buildMyLibraryItems(
            history = emptyList(),
            watchlist = listOf(WatchlistEntity(
                profileId = 1,
                id = "catalog-show-17",
                type = "series",
                title = "Addon Show",
                poster = "poster.jpg",
                addedAt = 1,
                sourceAddonBaseUrl = "https://provider.example/stremio"
            )),
            nextUp = emptyList()
        ).watchlist.single()

        assertEquals(com.saab.tv.domain.schema.IdentityNamespace.ADDON, card.content?.identity?.namespace)
        assertEquals("catalog-show-17", card.asActionItem().id)
        assertEquals("https://provider.example/stremio", card.asActionItem().addonBaseUrl)
    }

    @Test fun libraryFiltersPreserveStatusAndMediaTypeAcrossAllSections() {
        val library = buildMyLibraryItems(
            history = listOf(
                history("tt-movie-progress", "movie", position = 300),
                history("tt-series-progress:2:5", "series", title = "Episode Five", position = 500),
                history("tt-movie-watched", "movie", position = 900, watched = true),
                history("tt-series-watched:1:8", "series", title = "Finale", position = 900, watched = true)
            ),
            watchlist = listOf(
                WatchlistEntity(1, "tt-movie-saved", "movie", "Saved Movie", null, 1),
                WatchlistEntity(1, "tt-series-saved", "series", "Saved Series", null, 2)
            ),
            nextUp = emptyList()
        )

        val allSeries = filterMyLibrary(library, MyLibraryStatusFilter.ALL, MyLibraryMediaFilter.SERIES)
        assertEquals(listOf("Continue Watching", "Watchlist", "Watched"), allSeries.map { it.title })
        assertEquals(listOf("tt-series-progress"), allSeries[0].items.map { it.asActionItem().id })
        assertEquals(listOf("tt-series-saved"), allSeries[1].items.map { it.asActionItem().id })
        assertEquals(listOf("tt-series-watched"), allSeries[2].items.map { it.asActionItem().id })

        val savedMovies = filterMyLibrary(library, MyLibraryStatusFilter.WATCHLIST, MyLibraryMediaFilter.MOVIES)
        assertEquals(1, savedMovies.size)
        assertEquals("Watchlist", savedMovies.single().title)
        assertEquals(listOf("tt-movie-saved"), savedMovies.single().items.map { it.asActionItem().id })

        assertEquals(2, filterMyLibrary(library, MyLibraryStatusFilter.WATCHED, MyLibraryMediaFilter.ALL)
            .single().items.size)
        assertTrue(filterMyLibrary(library, MyLibraryStatusFilter.IN_PROGRESS, MyLibraryMediaFilter.MOVIES)
            .single().items.all { it.asActionItem().type == "movie" })
    }

    private fun history(
        id: String,
        type: String,
        title: String = id,
        position: Long,
        watched: Boolean = false,
        timestamp: Long = 1
    ) = WatchHistoryEntity(
        profileId = 1, id = id, title = title, poster = null, position = position,
        duration = 1_000, lastWatched = timestamp, type = type, watched = watched
    )
}
