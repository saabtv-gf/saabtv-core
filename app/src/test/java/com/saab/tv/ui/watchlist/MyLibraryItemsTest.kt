package com.saab.tv.ui.watchlist

import com.saab.tv.data.model.SeriesNextUpEntity
import com.saab.tv.data.model.WatchHistoryEntity
import com.saab.tv.data.model.WatchlistEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyLibraryItemsTest {
    @Test fun groupsMoviesByProgressWatchlistAndWatchedState() {
        val items = buildMyLibraryItems(
            history = listOf(
                history("tt-progress", "movie", position = 600, watched = false),
                history("tt-done", "movie", position = 900, watched = true),
                history("tt-zero", "movie", position = 0, watched = false)
            ),
            watchlist = listOf(WatchlistEntity(1, "tt-saved", "movie", "Saved", "saved.jpg", 20)),
            nextUp = emptyList()
        )

        assertEquals(listOf("tt-progress"), items.inProgress.map { it.id })
        assertEquals(listOf("tt-saved"), items.watchlist.map { it.id })
        assertEquals(listOf("tt-done"), items.watched.map { it.id })
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
        assertEquals("tt-series", items.inProgress.single().id)
        assertEquals("The Show", items.inProgress.single().name)
        assertEquals("tt-series:1:3", items.inProgress.single().resumePlaybackId)
        assertEquals(listOf("tt-watched"), items.watched.map { it.id })
        assertEquals("Finished Show", items.watched.single().name)
    }

    @Test fun watchedTitleIsNotAlsoShownInProgressAndEmptyInputIsSafe() {
        val item = history("tt-same", "movie", position = 99, watched = true)
        val watched = buildMyLibraryItems(listOf(item), emptyList(), emptyList())
        assertTrue(watched.inProgress.isEmpty())
        assertEquals(listOf("tt-same"), watched.watched.map { it.id })

        val empty = buildMyLibraryItems(emptyList(), emptyList(), emptyList())
        assertFalse(empty.inProgress.isNotEmpty() || empty.watchlist.isNotEmpty() || empty.watched.isNotEmpty())
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
