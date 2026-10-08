package com.saab.tv.ui.details

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.WatchHistoryEntity
import com.saab.tv.data.model.AddonEntity
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.data.model.stremio.MetaVideo
import com.saab.tv.data.model.stremio.Stream
import com.saab.tv.data.remote.TmdbApiService
import com.saab.tv.data.model.tmdb.TmdbFindResponse
import com.saab.tv.data.tmdb.TmdbMetadataService
import com.saab.tv.data.tmdb.TmdbService
import com.saab.tv.testing.FeatureFixture
import com.saab.tv.testing.awaitAppState
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import retrofit2.Response
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class DetailsScreenComposeJourneyTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var fixture: FeatureFixture
    private lateinit var detailsViewModel: DetailsViewModel
    private lateinit var homeViewModel: com.saab.tv.ui.home.HomeViewModel

    @Before fun setUp() = runBlocking {
        fixture = FeatureFixture(RuntimeEnvironment.getApplication())
        fixture.dao.insertProfile(ProfileEntity(id = 71, name = "Details", isActive = true, tmdbEnabled = false))
        fixture.dao.insertAddon(AddonEntity(
            transportUrl = "https://fixture.invalid", id = "fixture", name = "Fixture", version = "1",
            description = null, iconUrl = null, supportsMeta = true, supportsStream = true
        ))
        fixture.app.configuration.saveRuntimeState(71)
        detailsViewModel = fixture.details()
        homeViewModel = fixture.home()
    }

    @After fun tearDown() {
        detailsViewModel.viewModelScope.cancel()
        homeViewModel.viewModelScope.cancel()
        fixture.close()
    }

    @Test fun movieDetailActionsUpdateWatchlistAndWatchedState() {
        fixture.api.metadata["tt-detail-movie"] = MetaItem("tt-detail-movie", "movie", "Detail Movie")
        showDetails("movie", "tt-detail-movie")

        compose.onNodeWithText("Detail Movie").assertExists()
        compose.onNodeWithContentDescription("Add to watchlist").performClick()
        awaitAppState { runBlocking { fixture.dao.isInWatchlist(71, "tt-detail-movie") } }
        compose.waitUntil(3_000) {
            compose.onAllNodesWithContentDescription("Watchlisted", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Watchlisted", useUnmergedTree = true).assertExists()

        compose.onNodeWithContentDescription("Mark as watched").performClick()
        awaitAppState { runBlocking { fixture.dao.getHistoryItemForProfile(71, "tt-detail-movie")?.watched == true } }
        compose.waitUntil(3_000) {
            compose.onAllNodesWithContentDescription("Watched", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Watched", useUnmergedTree = true).assertExists()
    }

    @Test fun selectingEpisodeInFullDetailsRequestsThatEpisodesStreams() {
        val episodes = listOf(
            MetaVideo(id = "tt-detail-series:1:1", season = 1, episode = 1, title = "Pilot"),
            MetaVideo(id = "tt-detail-series:1:2", season = 1, episode = 2, title = "Second Episode")
        )
        fixture.api.metadata["tt-detail-series"] = MetaItem("tt-detail-series", "series", "Detail Series", videos = episodes)
        fixture.api.streams["tt-detail-series:1:2"] = listOf(
            Stream(url = "https://fixture.invalid/episode-2", title = "1080p English")
        )
        showDetails("series", "tt-detail-series")

        compose.onNodeWithContentDescription("Episodes").performClick()
        compose.onNodeWithText("S1 : E2").performClick()
        awaitAppState { fixture.api.calls.any { it.contains("/stream/series/tt-detail-series:1:2.json") } }
        awaitAppState { detailsViewModel.state.value.availableStreams.any { it.url == "https://fixture.invalid/episode-2" } }
        assertTrue(detailsViewModel.state.value.availableStreams.any { it.url == "https://fixture.invalid/episode-2" })
    }

    @Test fun detailsResumeTargetUsesTheMostRecentlyPlayedEpisodeInsteadOfSeasonOne() = runBlocking {
        val seriesId = "tt-detail-resume-series"
        val continueEpisode = "$seriesId:2:5"
        fixture.api.metadata[seriesId] = MetaItem(seriesId, "series", "Resume Series", videos = listOf(
            MetaVideo(id = "$seriesId:1:1", season = 1, episode = 1, title = "Pilot"),
            MetaVideo(id = continueEpisode, season = 2, episode = 5, title = "Current Episode")
        ))
        fixture.api.streams[continueEpisode] = listOf(
            Stream(url = "https://fixture.invalid/season-2-episode-5", title = "1080p English")
        )
        runBlocking {
            fixture.dao.updateProfile(fixture.dao.getProfileById(71)!!.copy(sourceSeasonPacksOnly = false))
            fixture.dao.insertHistory(WatchHistoryEntity(
                profileId = 71, id = "$seriesId:1:1", title = "Resume Series", poster = null,
                position = 400_000, duration = 1_800_000, lastWatched = 100, type = "series"
            ))
            fixture.dao.insertHistory(WatchHistoryEntity(
                profileId = 71, id = continueEpisode, title = "Resume Series", poster = null,
                position = 250_000, duration = 1_800_000, lastWatched = 200, type = "series"
            ))
        }

        detailsViewModel.loadDetails("series", seriesId, addonBaseUrl = "https://fixture.invalid")
        awaitAppState {
            !detailsViewModel.state.value.isLoading && detailsViewModel.state.value.contentKey == "series:$seriesId"
        }
        assertEquals(continueEpisode, detailsViewModel.state.value.resumePlaybackId)
        awaitAppState { fixture.api.calls.any { it.contains("/stream/series/$continueEpisode.json") } }
        assertTrue(fixture.api.calls.any { it.contains("/stream/series/$continueEpisode.json") })
    }

    @Test fun addonDetailsRenderWhileTmdbEnrichmentIsStillPending() {
        val profile = runBlocking { fixture.dao.getProfileById(71)!! }
        runBlocking { fixture.dao.updateProfile(profile.copy(tmdbEnabled = true)) }
        fixture.api.metadata["tt-slow-tmdb"] = MetaItem("tt-slow-tmdb", "movie", "Immediate Addon Details")
        detailsViewModel.viewModelScope.cancel()

        val delayedTmdbApi = Proxy.newProxyInstance(
            TmdbApiService::class.java.classLoader,
            arrayOf(TmdbApiService::class.java)
        ) { _, method, _ ->
            if (method.name == "findByExternalId") Thread.sleep(1_000)
            Response.success<TmdbFindResponse>(200, TmdbFindResponse())
        } as TmdbApiService
        val tmdb = TmdbService(RuntimeEnvironment.getApplication(), delayedTmdbApi)
        val metadata = TmdbMetadataService(delayedTmdbApi, tmdb)
        detailsViewModel = DetailsViewModel(
            fixture.dao, fixture.sources, fixture.tracks, fixture.app.repository, fixture.subtitles,
            fixture.app.configuration, fixture.sorting, tmdb, metadata, fixture.traktSync,
            fixture.cache, fixture.app.display, fixture.sync
        )
        detailsViewModel.loadDetails("movie", "tt-slow-tmdb", addonBaseUrl = "https://fixture.invalid")
        awaitAppState {
            !detailsViewModel.state.value.isLoading && detailsViewModel.state.value.tmdbLoading
        }

        compose.setContent {
            MaterialTheme {
                DetailsScreen(
                    type = "movie",
                    id = "tt-slow-tmdb",
                    onPlayClick = { _, _, _, _, _, _, _, _, _, _ -> },
                    viewModel = detailsViewModel,
                    trailerHostViewModel = homeViewModel
                )
            }
        }

        compose.onNodeWithText("Immediate Addon Details").assertExists()
    }

    private fun showDetails(type: String, id: String) {
        detailsViewModel.loadDetails(type, id, addonBaseUrl = "https://fixture.invalid")
        awaitAppState {
            !detailsViewModel.state.value.isLoading && detailsViewModel.state.value.contentKey == "$type:$id"
        }
        compose.setContent {
            MaterialTheme {
                DetailsScreen(
                    type = type,
                    id = id,
                    onPlayClick = { _, _, _, _, _, _, _, _, _, _ -> },
                    viewModel = detailsViewModel,
                    trailerHostViewModel = homeViewModel
                )
            }
        }
    }
}
