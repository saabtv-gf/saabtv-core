package com.saab.tv.ui.home

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
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
            compose.onAllNodesWithContentDescription("Watch Trailer").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun watchlistedTitleOffersRemoveOnlyAndRemoteActionRemovesTheCorrectProfileRow() = runBlocking {
        fixture.dao.addToWatchlist(WatchlistEntity(21, item.id, item.type, item.name, null, 1L))
        fixture.dao.addToWatchlist(WatchlistEntity(22, item.id, item.type, item.name, null, 1L))
        show()

        compose.onNodeWithContentDescription("Add To Watchlist").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithContentDescription("Remove From Watchlist").assertExists()
        val trailerBounds = compose.onNodeWithContentDescription("Watch Trailer")
            .fetchSemanticsNode().boundsInRoot
        val removeBounds = compose.onNodeWithContentDescription("Remove From Watchlist")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(trailerBounds.center.y, removeBounds.center.y, 1f)
        assertTrue(removeBounds.left >= trailerBounds.right - 2f)
        compose.onNodeWithContentDescription("Remove From Watchlist").performClick()
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
            compose.onAllNodesWithContentDescription("Add To Watchlist").fetchSemanticsNodes().isNotEmpty()
        }
        compose.mainClock.advanceTimeBy(600)
        assertFalse(fixture.dao.isInWatchlist(21, item.id))
        compose.onNodeWithContentDescription("Add To Watchlist").assertExists()
        compose.runOnIdle { assertEquals(0, trailerCalls); assertEquals(0, dismissed) }
    }

    @Test fun markWatchedRemovesTheSameTitleFromWatchlist() = runBlocking {
        fixture.dao.addToWatchlist(WatchlistEntity(21, item.id, item.type, item.name, null, 1L))
        show()
        compose.mainClock.advanceTimeBy(600)

        compose.onNodeWithContentDescription("Mark As Watched").performClick()
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
        compose.mainClock.advanceTimeBy(600)

        compose.onNodeWithContentDescription("Mark As Unwatched").assertExists().performClick()
        compose.waitUntil(3_000) {
            runBlocking { fixture.dao.getHistoryItemForProfile(21, item.id)?.watched == false }
        }
        assertEquals(420_000L, fixture.dao.getHistoryItemForProfile(21, item.id)?.position)
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
        fixture.tmdb.preCacheMapping("tt-alias-target", 987)
        fixture.dao.insertHistory(WatchHistoryEntity(
            profileId = 21, id = "tt-alias-target", title = "Alias Target", poster = null,
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
        fixture.tmdb.preCacheMapping("tt-complete-series", 988)
        fixture.dao.insertSeriesNextUp(SeriesNextUpEntity(
            profileId = 21, seriesId = "tt-complete-series", title = "Completed Series", poster = null,
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
