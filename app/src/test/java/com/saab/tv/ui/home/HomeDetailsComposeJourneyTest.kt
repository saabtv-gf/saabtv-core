package com.saab.tv.ui.home

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.tv.material3.MaterialTheme
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.data.model.stremio.MetaVideo
import com.saab.tv.data.model.SeriesNextUpEntity
import com.saab.tv.data.model.WatchHistoryEntity
import com.saab.tv.domain.CategoryRow
import com.saab.tv.domain.HomeRow
import com.saab.tv.domain.episodePlaybackId
import com.saab.tv.ui.details.GlassSidebar
import com.saab.tv.ui.details.SidebarState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class HomeDetailsComposeJourneyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun homeCatalogCardOpensTheSelectedTitle() {
        val movie = MetaItem(
            id = "tt-home-journey", type = "movie", name = "Journey Movie",
            poster = "", background = "", logo = "", description = "A fixture movie",
            releaseInfo = "2024", imdbRating = "7.8", runtime = "100 min", genres = listOf("Drama")
        )
        val row = HomeRow("fixture:movies", "Popular Movies", listOf(movie), order = 0)
        val state = HomeViewModel.HomeState(
            mixedRows = listOf(CategoryRow.fromHomeRow(row)), rows = listOf(row), isLoading = false,
            loadedScreen = "home", loadedProfileId = 31
        )
        var openedMovieId: String? = null
        val entry = FocusRequester()
        val drawer = FocusRequester()

        compose.setContent {
            MaterialTheme {
                SimpleLayout(
                    startPadding = androidx.compose.ui.unit.Dp(120f),
                    isTopNav = false,
                    state = state,
                    onMovieClick = { openedMovieId = it.id },
                    onContinueClick = { openedMovieId = it.id },
                    onMovieLongClick = { _, _, _ -> },
                    onViewMore = { _, _, _ -> },
                    onHubClick = {},
                    onLoadMore = {},
                    entryRequester = entry,
                    drawerRequester = drawer,
                    lastFocusedKey = null,
                    rowScrollPositions = emptyMap(),
                    verticalScrollPosition = 0 to 0,
                    historyScrollAdjustment = 0,
                    onFocusChange = {},
                    onScrollPositionChange = { _, _ -> },
                    onVerticalScrollChange = {},
                    onHeroItemVisible = {}
                )
            }
        }

        compose.onNodeWithContentDescription("Journey Movie").assertExists()
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("tt-home-journey", openedMovieId) }
    }

    @Test fun detailsEpisodePanelSelectsTheExactEpisodeForPlayback() {
        val episodes = listOf(
            MetaVideo(id = "tt-details-journey:1:1", season = 1, episode = 1, title = "Pilot"),
            MetaVideo(id = "tt-details-journey:2:7", season = 2, episode = 7, title = "Chosen Episode")
        )

        var selectedPlaybackId: String? = null
        var selectedEpisode: MetaVideo? = null
        compose.setContent {
            MaterialTheme {
                GlassSidebar(
                    state = SidebarState.Episodes(episodes),
                    currentEpisodeId = episodePlaybackId("tt-details-journey", episodes.first()),
                    onEpisodeSelected = { episode ->
                        selectedEpisode = episode
                        selectedPlaybackId = episodePlaybackId("tt-details-journey", episode)
                    },
                    onSourceSelected = {},
                    onBack = {},
                    onDismiss = {}
                )
            }
        }

        compose.onNodeWithText("Season 2").assertExists().performClick()
        compose.onNodeWithText("S2 : E7").assertExists().performClick()
        compose.runOnIdle {
            assertEquals("tt-details-journey:2:7", selectedPlaybackId)
            assertEquals(2, selectedEpisode?.season)
            assertEquals(7, selectedEpisode?.episode)
        }
    }

    @Test fun continueWatchingUsesLatestEpisodeAndOnlyShowsAiredNextUp() {
        val history = listOf(
            WatchHistoryEntity(31, "tt-movie", "Paused Movie", "/movie.jpg", position = 3_000, duration = 10_000, lastWatched = 10, type = "movie"),
            WatchHistoryEntity(31, "tt-show:1:2", "Paused Show", "/show.jpg", position = 4_000, duration = 10_000, lastWatched = 20, type = "series"),
            WatchHistoryEntity(31, "tt-show:1:1", "Paused Show", "/show.jpg", position = 8_000, duration = 10_000, lastWatched = 15, type = "series"),
            WatchHistoryEntity(31, "tt-watched", "Finished", "/watched.jpg", position = 10_000, duration = 10_000, lastWatched = 30, type = "movie", watched = true)
        )
        val nextUp = listOf(
            SeriesNextUpEntity(31, "tt-returning", "Returning Show", "/returning.jpg", 2, 1, "New Episode", isComplete = true, updatedAt = 40),
            SeriesNextUpEntity(31, "tt-future", "Future Show", "/future.jpg", 2, 1, "Future", nextReleased = "2999-01-01", updatedAt = 50),
            SeriesNextUpEntity(31, "tt-finished", "Finished Show", null, 1, 1, null, isComplete = true, updatedAt = 60)
        )
        val state = HomeViewModel.HomeState(
            history = history,
            seriesNextUp = nextUp,
            isLoading = false,
            loadedScreen = "home",
            loadedProfileId = 31
        )
        val visible = mutableStateListOf<MetaItem>()
        var resumedId: String? = null
        val entry = FocusRequester()
        val drawer = FocusRequester()

        compose.setContent {
            MaterialTheme {
                SimpleLayout(
                    startPadding = androidx.compose.ui.unit.Dp(80f), isTopNav = false, state = state,
                    onMovieClick = {}, onContinueClick = { resumedId = it.resumePlaybackId ?: it.id },
                    onMovieLongClick = { _, _, _ -> }, onViewMore = { _, _, _ -> }, onHubClick = {},
                    onLoadMore = {}, entryRequester = entry, drawerRequester = drawer,
                    lastFocusedKey = null, rowScrollPositions = emptyMap(), verticalScrollPosition = 0 to 0,
                    historyScrollAdjustment = 0, onFocusChange = {}, onScrollPositionChange = { _, _ -> },
                    onVerticalScrollChange = {}, onHeroItemVisible = { visible += it }
                )
            }
        }

        compose.waitForIdle()
        compose.onNodeWithText("Continue Watching").assertExists()
        compose.onNodeWithContentDescription("Paused Show").assertExists()
        compose.onNodeWithContentDescription("Paused Show").performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle {
            assertEquals("tt-show:1:2", resumedId)
            assertEquals("tt-show:1:2", visible.first { it.id == "tt-show" }.resumePlaybackId)
            assertFalse(visible.any { it.id == "tt-future" || it.id == "tt-finished" })
        }
    }
}
