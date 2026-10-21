package com.saab.tv.ui.home

import android.app.Application
import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.SeriesNextUpEntity
import com.saab.tv.data.model.WatchHistoryEntity
import com.saab.tv.data.model.WatchlistEntity
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.episodePlaybackId
import com.saab.tv.data.model.stremio.MetaVideo
import com.saab.tv.testing.FeatureFixture
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class CatalogQuickActionsInteractionP1Test {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: FeatureFixture
    private lateinit var viewModel: HomeViewModel
    private val item = MetaItem("tt-quick-action", "movie", "Quick Action Film")
    private var dismissed = 0
    private fun action(label: String) = compose.onNode(hasContentDescription(label, ignoreCase = true))

    @Before fun setUp() = runBlocking {
        fixture = FeatureFixture(RuntimeEnvironment.getApplication())
        fixture.dao.insertProfile(ProfileEntity(id = 21, name = "Quick Action Profile"))
        viewModel = fixture.home()
    }

    @After fun tearDown() {
        viewModel.viewModelScope.cancel()
        fixture.close()
    }

    private fun show() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(1280.dp, 720.dp)) {
                    CatalogQuickActionsPopup(
                        item = item, bounds = Rect(200f, 160f, 500f, 460f), profileId = 21,
                        onDismiss = { dismissed++ }, onTrailerClick = { _, _ -> }, viewModel = viewModel
                    )
                }
            }
        }
        compose.waitUntil(3_000) {
            runCatching {
                compose.onNodeWithContentDescription("Watch Trailer").assertIsEnabled()
            }.isSuccess
        }
        awaitLibraryActionsReady()
    }

    private fun awaitLibraryActionsReady() {
        compose.waitUntil(3_000) {
            val watchedReady = listOf("Mark As Watched", "Mark As Unwatched").any { label ->
                runCatching { action(label).assertIsEnabled() }.isSuccess
            }
            val watchlistReady = listOf("Add To Watchlist", "Remove From Watchlist").any { label ->
                runCatching { action(label).assertIsEnabled() }.isSuccess
            }
            watchedReady && watchlistReady
        }
    }

    @Test fun popupFocusesFirstActionWithoutActivatingIt() {
        show()

        action("Watch Trailer").assertIsFocused()
        compose.runOnIdle { assertEquals(0, dismissed) }
    }

    @Test fun watchlistedTitleOffersRemoveOnlyAndRemoteActionRemovesTheCorrectProfileRow() = runBlocking {
        fixture.dao.addToWatchlist(WatchlistEntity(21, item.id, item.type, item.name, null, 1L))
        fixture.dao.addToWatchlist(WatchlistEntity(22, item.id, item.type, item.name, null, 1L))
        show()

        action("Add To Watchlist").assertDoesNotExist()
        action("Remove From Watchlist").assertExists()
        val trailerBounds = compose.onNodeWithContentDescription("Watch Trailer")
            .fetchSemanticsNode().boundsInRoot
        val removeBounds = action("Remove From Watchlist")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(trailerBounds.center.y, removeBounds.center.y, 1f)
        assertTrue(removeBounds.left >= trailerBounds.right - 2f)
        action("Remove From Watchlist").performClick()
        compose.waitUntil(3_000) { !runBlocking { fixture.dao.isInWatchlist(21, item.id) } }
        assertTrue(fixture.dao.isInWatchlist(22, item.id))
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test fun unwatchlistedTitleOffersAddAndDoesNotStartTrailerWithoutSelection() = runBlocking {
        var trailerCalls = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(1280.dp, 720.dp)) {
                    CatalogQuickActionsPopup(
                        item = item, bounds = Rect(200f, 160f, 500f, 460f), profileId = 21,
                        onDismiss = { dismissed++ }, onTrailerClick = { _, _ -> trailerCalls++ }, viewModel = viewModel
                    )
                }
            }
        }
        compose.waitUntil(3_000) {
            compose.onAllNodes(hasContentDescription("Add To Watchlist", ignoreCase = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(3_000) {
            runCatching { compose.onNodeWithContentDescription("Watch Trailer").assertIsEnabled() }.isSuccess
        }
        awaitLibraryActionsReady()
        assertFalse(fixture.dao.isInWatchlist(21, item.id))
        action("Add To Watchlist").assertExists()
        compose.runOnIdle { assertEquals(0, trailerCalls); assertEquals(0, dismissed) }
    }

    @Test fun statefulActionCannotBeActivatedUntilItsInitialStateIsKnown() {
        val stateLoaded = androidx.compose.runtime.mutableStateOf(false)
        var actionCalls = 0
        compose.setContent {
            MaterialTheme {
                com.saab.tv.ui.components.DetailActionButton(
                    label = "Add To Watchlist",
                    icon = Icons.Default.BookmarkBorder,
                    onClick = { actionCalls++ },
                    enabled = stateLoaded.value,
                    compact = true
                )
            }
        }

        action("Add To Watchlist").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, actionCalls) }
        compose.runOnIdle { stateLoaded.value = true }
        compose.waitUntil(3_000) {
            runCatching {
                action("Add To Watchlist").assertIsEnabled()
            }.isSuccess
        }
        action("Add To Watchlist").performClick()
        compose.runOnIdle { assertEquals(1, actionCalls) }
    }

    @Test fun watchedBadgeRefreshDoesNotStealFocusFromQuickAction() {
        val watchedIds = androidx.compose.runtime.mutableStateOf(emptySet<String>())
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                com.saab.tv.ui.components.LocalWatchedIds provides watchedIds.value
            ) {
                MaterialTheme {
                    Box(Modifier.size(1280.dp, 720.dp)) {
                        CatalogQuickActionsPopup(
                            item = item, bounds = Rect(200f, 160f, 500f, 460f), profileId = 21,
                            onDismiss = { dismissed++ }, onTrailerClick = { _, _ -> }, viewModel = viewModel
                        )
                    }
                }
            }
        }
        compose.waitUntil(3_000) {
            runCatching { action("Mark As Watched").assertIsEnabled() }.isSuccess
        }
        val markWatched = action("Mark As Watched")
        markWatched.performSemanticsAction(SemanticsActions.RequestFocus)
        markWatched.assertIsFocused()

        compose.runOnIdle { watchedIds.value = setOf(item.id) }
        compose.waitUntil(3_000) {
            runCatching {
                action("Mark As Unwatched").assertIsFocused()
                true
            }.getOrDefault(false)
        }
    }

    @Test fun markWatchedRemovesTheSameTitleFromWatchlist() = runBlocking {
        fixture.dao.addToWatchlist(WatchlistEntity(21, item.id, item.type, item.name, null, 1L))
        show()

        action("Mark As Watched").performClick()
        compose.waitUntil(3_000) {
            runBlocking {
                fixture.dao.getHistoryItemForProfile(21, item.id)?.watched == true &&
                    !fixture.dao.isInWatchlist(21, item.id)
            }
        }
    }

    @Test fun watchedTitleCanBeUnmarkedFromQuickActions() = runBlocking {
        fixture.dao.insertHistory(WatchHistoryEntity(
            profileId = 21, id = item.id, title = item.name, poster = null,
            position = 420_000L, duration = 1_200_000L, lastWatched = 10L, type = "movie", watched = true
        ))
        show()

        action("Mark As Unwatched").assertExists().performClick()
        compose.waitUntil(3_000) {
            runBlocking { fixture.dao.getHistoryItemForProfile(21, item.id)?.watched == false }
        }
        assertEquals(420_000L, fixture.dao.getHistoryItemForProfile(21, item.id)?.position)
    }

    @Test fun heldCenterOpensPosterAndLandscapeActionsOnlyAfterRelease() {
        var cardClicks = 0
        var trailerCalls = 0
        var rootView: View? = null
        val landscapeState = androidx.compose.runtime.mutableStateOf(false)
        val popupBounds = androidx.compose.runtime.mutableStateOf<Rect?>(null)
        compose.setContent {
            rootView = LocalView.current
            MaterialTheme {
                Box(Modifier.size(1280.dp, 720.dp)) {
                    com.saab.tv.ui.components.SaabTvCard(
                        title = item.name,
                        posterUrl = null,
                        previewItem = item,
                        forceLandscape = landscapeState.value,
                        onClick = { cardClicks++ },
                        onLongClick = { popupBounds.value = it }
                    )
                    popupBounds.value?.let { bounds ->
                        CatalogQuickActionsPopup(
                            item = item,
                            bounds = bounds,
                            profileId = 21,
                            onDismiss = { dismissed++ },
                            onTrailerClick = { _, _ -> trailerCalls++ },
                            viewModel = viewModel
                        )
                    }
                }
            }
        }
        for (landscape in listOf(false, true)) {
            compose.runOnIdle { landscapeState.value = landscape }
            compose.waitForIdle()
            compose.onNodeWithContentDescription(item.name)
                .performSemanticsAction(SemanticsActions.RequestFocus)
            val view = requireNotNull(rootView)
            val downTime = SystemClock.uptimeMillis()
            compose.runOnUiThread {
                view.dispatchKeyEvent(android.view.KeyEvent(
                    downTime, downTime, android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER, 0
                ))
                view.dispatchKeyEvent(android.view.KeyEvent(
                    downTime, downTime + 500, android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER, 1
                ))
            }

            compose.onNodeWithContentDescription("Watch Trailer").assertDoesNotExist()
            compose.runOnUiThread {
                view.dispatchKeyEvent(android.view.KeyEvent(
                    downTime, downTime + 520, android.view.KeyEvent.ACTION_UP,
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER, 0
                ))
            }
            compose.waitUntil(3_000) {
                compose.onAllNodesWithContentDescription("Watch Trailer").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitUntil(3_000) {
                runCatching { compose.onNodeWithContentDescription("Watch Trailer").assertIsEnabled() }.isSuccess
            }
            compose.runOnIdle {
                assertEquals(0, cardClicks)
                assertEquals(0, trailerCalls)
                assertEquals(0, dismissed)
            }
            compose.runOnIdle { popupBounds.value = null }
            compose.waitForIdle()
        }
    }

    @Test fun unmarkingWatchedSeriesPreservesEpisodeProgressAndReopensContinueWatching() = runBlocking {
        val series = MetaItem("tt-quick-series", "series", "Quick Action Series")
        val episodeId = episodePlaybackId(series.id, MetaVideo(
            id = "addon-episode-1", season = 1, episode = 1, title = "Pilot"
        ))
        fixture.dao.insertHistory(WatchHistoryEntity(
            profileId = 21, id = episodeId, title = "Pilot", poster = null,
            position = 120_000L, duration = 1_800_000L, lastWatched = 10L,
            type = "series", watched = true
        ))
        fixture.dao.insertHistory(WatchHistoryEntity(
            profileId = 21, id = series.id, title = series.name, poster = null,
            position = 0L, duration = 0L, lastWatched = 10L,
            type = "series", watched = true
        ))
        fixture.dao.insertSeriesNextUp(SeriesNextUpEntity(
            profileId = 21, seriesId = series.id, title = series.name, poster = null,
            nextSeason = 0, nextEpisode = 0, nextEpisodeTitle = null,
            isComplete = true, updatedAt = 10L
        ))

        viewModel.unmarkTitleWatched(21, series)

        compose.waitUntil(3_000) {
            runBlocking {
                fixture.dao.getHistoryItemForProfile(21, episodeId)?.watched == false &&
                    fixture.dao.getSeriesNextUpForProfile(21).any {
                        it.seriesId == series.id && !it.isComplete
                    }
            }
        }
        assertEquals(120_000L, fixture.dao.getHistoryItemForProfile(21, episodeId)?.position)
    }

    @Test fun watchedTmdbRecommendationGetsAnAliasForItsImdbHistoryId() = runBlocking {
        fixture.tmdb.preCacheMapping("tt1234567", 987, "movie")
        fixture.dao.insertHistory(WatchHistoryEntity(
            profileId = 21, id = "tt1234567", title = "Alias Target", poster = null,
            position = 0L, duration = 0L, lastWatched = 10L, type = "movie", watched = true
        ))
        val recommendation = MetaItem("tmdb:987", "movie", "Alias Target")

        viewModel.ensureWatchedAlias(21, recommendation)
        val watchedIds = withTimeout(3_000) {
            viewModel.watchedIdsForProfile(21).first { recommendation.id in it }
        }

        assertTrue(recommendation.id in watchedIds)
    }

    @Test fun completedSeriesRecommendationGetsBadgeEvenWithoutEpisodeHistoryRows() = runBlocking {
        fixture.tmdb.preCacheMapping("tt7654321", 988, "series")
        fixture.dao.insertSeriesNextUp(SeriesNextUpEntity(
            profileId = 21, seriesId = "tt7654321", title = "Completed Series", poster = null,
            nextSeason = 0, nextEpisode = 0, nextEpisodeTitle = null,
            isComplete = true, updatedAt = 10L
        ))
        val recommendation = MetaItem("tmdb:988", "series", "Completed Series")

        viewModel.ensureWatchedAlias(21, recommendation)
        val watchedIds = withTimeout(3_000) {
            viewModel.watchedIdsForProfile(21).first { recommendation.id in it }
        }

        assertTrue(recommendation.id in watchedIds)
    }
}
