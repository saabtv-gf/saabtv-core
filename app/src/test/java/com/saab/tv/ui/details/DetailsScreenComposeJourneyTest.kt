package com.saab.tv.ui.details

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private typealias DetailsPlayHandler = (
    String,
    String,
    String,
    String,
    String,
    String,
    Stream,
    List<com.saab.tv.domain.AddonSubtitle>,
    List<Stream>,
    List<MetaVideo>,
    String,
    String,
    String
) -> Unit

private data class DetailsPlayRequest(
    val url: String,
    val playbackId: String,
    val playbackType: String,
    val playbackTitle: String,
    val detailContentId: String,
    val detailContentType: String,
    val detailPoster: String
)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class DetailsScreenComposeJourneyTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var fixture: FeatureFixture
    private lateinit var detailsViewModel: DetailsViewModel
    private lateinit var homeViewModel: com.saab.tv.ui.home.HomeViewModel
    @Volatile private var lastPlayRequest: DetailsPlayRequest? = null
    private var tmdbLookupStarted: CountDownLatch? = null
    private var releaseTmdbLookup: CountDownLatch? = null

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
        releaseTmdbLookup?.countDown()
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

    @Test fun failedDetailsLookupOffersRetryAndRendersWhenMetadataReturns() {
        val id = "tt-detail-retry-1001"
        compose.setContent {
            MaterialTheme {
                DetailsScreen(
                    addonBaseUrl = "https://fixture.invalid",
                    type = "movie",
                    id = id,
                    onPlayClick = { _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
                    viewModel = detailsViewModel,
                    trailerHostViewModel = homeViewModel
                )
            }
        }

        awaitAppState { detailsViewModel.state.value.detailsLoadFailed }
        compose.onNodeWithText("Title details are unavailable").assertExists()
        compose.onNodeWithText("Try again").assertExists()
        fixture.api.metadata[id] = MetaItem(id, "movie", "Recovered Detail")
        compose.onNodeWithText("Try again").performClick()
        awaitAppState {
            !detailsViewModel.state.value.isLoading && detailsViewModel.state.value.meta?.name == "Recovered Detail"
        }
        compose.onNodeWithText("Recovered Detail").assertExists()
    }

    @Test fun selectingEpisodeInFullDetailsRequestsThatEpisodesStreams() {
        val seriesId = "addon:2:5"
        val episodeId = "$seriesId:1:2"
        val poster = "https://fixture.invalid/series-poster.jpg"
        val episodes = listOf(
            MetaVideo(id = "$seriesId:1:1", season = 1, episode = 1, title = "Pilot"),
            MetaVideo(id = episodeId, season = 1, episode = 2, title = "Second Episode")
        )
        fixture.api.metadata[seriesId] = MetaItem(
            seriesId, "series", "Detail Series", poster = poster, videos = episodes
        )
        fixture.api.streams[episodeId] = listOf(
            Stream(url = "https://fixture.invalid/episode-2", title = "1080p English")
        )
        showDetails("series", seriesId)

        compose.onNodeWithContentDescription("Episodes").performClick()
        compose.onNodeWithText("S1 : E2").performClick()
        awaitAppState { fixture.api.calls.any { it.contains("/stream/series/$episodeId.json") } }
        awaitAppState {
            detailsViewModel.state.value.availableStreams.any { it.url == "https://fixture.invalid/episode-2" } &&
                detailsViewModel.state.value.sidebarState is SidebarState.Sources
        }
        assertTrue(detailsViewModel.state.value.availableStreams.any { it.url == "https://fixture.invalid/episode-2" })
        // Explicitly choose the returned episode source so this journey exercises the complete
        // episode-ID -> stream-picker -> player callback path without depending on a Compose
        // lifecycle effect being scheduled by Robolectric after unrelated test classes.
        compose.onNode(hasText("1080P", substring = true) and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals("https://fixture.invalid/episode-2", lastPlayRequest?.url) }
        val request = requireNotNull(lastPlayRequest)
        assertEquals(episodeId, request.playbackId)
        assertEquals("series", request.playbackType)
        assertEquals("S1:E2 - Second Episode", request.playbackTitle)
        assertEquals(seriesId, request.detailContentId)
        assertEquals("series", request.detailContentType)
        assertEquals(poster, request.detailPoster)
    }

    @Test fun movieDetailsCanOpenSourcePickerAndPlayChosenSource() {
        val movieId = "tt-detail-source-movie"
        fixture.api.metadata[movieId] = MetaItem(movieId, "movie", "Source Movie")
        fixture.api.streams[movieId] = listOf(Stream(url = "https://fixture.invalid/movie-source", title = "1080p English"))
        showDetails("movie", movieId)

        compose.onNodeWithContentDescription("Sources").performClick()
        awaitAppState {
            detailsViewModel.state.value.availableStreams.any { it.url == "https://fixture.invalid/movie-source" } &&
                detailsViewModel.state.value.sidebarState is SidebarState.Sources
        }
        compose.onNode(hasText("1080P", substring = true) and hasClickAction()).performClick()

        compose.runOnIdle {
            assertEquals("https://fixture.invalid/movie-source", lastPlayRequest?.url)
            assertEquals(movieId, lastPlayRequest?.playbackId)
            assertEquals("movie", lastPlayRequest?.playbackType)
        }
    }

    @Test fun seriesDetailsSourcePickerUsesTheSelectedEpisodeStreams() {
        val seriesId = "tt-detail-source-series"
        val episodeId = "$seriesId:1:2"
        fixture.api.metadata[seriesId] = MetaItem(
            seriesId, "series", "Source Series",
            videos = listOf(MetaVideo(id = episodeId, season = 1, episode = 2, title = "Second Episode"))
        )
        fixture.api.streams[episodeId] = listOf(Stream(url = "https://fixture.invalid/episode-source", title = "720p English"))
        showDetails("series", seriesId)

        compose.onNodeWithContentDescription("Sources").performClick()
        awaitAppState {
            detailsViewModel.state.value.availableStreams.any { it.url == "https://fixture.invalid/episode-source" } &&
                detailsViewModel.state.value.sidebarState is SidebarState.Sources
        }
        compose.onNode(hasText("720P", substring = true) and hasClickAction()).performClick()

        compose.runOnIdle {
            assertEquals("https://fixture.invalid/episode-source", lastPlayRequest?.url)
            assertEquals(episodeId, lastPlayRequest?.playbackId)
            assertEquals("series", lastPlayRequest?.playbackType)
            assertEquals("S1:E2 - Second Episode", lastPlayRequest?.playbackTitle)
        }
    }

    @Test fun closingEpisodeSidebarRestoresFocusToEpisodesAction() {
        val seriesId = "tt-details-focus-series"
        fixture.api.metadata[seriesId] = MetaItem(
            seriesId,
            "series",
            "Focus Series",
            videos = listOf(MetaVideo(id = "$seriesId:1:1", season = 1, episode = 1, title = "Pilot"))
        )
        showDetails("series", seriesId)

        compose.onNodeWithContentDescription("Episodes")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        detailsViewModel.openEpisodes()
        compose.waitUntil(3_000) {
            compose.onAllNodesWithText("More Episodes").fetchSemanticsNodes().isNotEmpty()
        }
        detailsViewModel.closeSidebar()
        compose.waitUntil(3_000) {
            runCatching {
                compose.onNodeWithContentDescription("Episodes").assertIsFocused()
            }.isSuccess
        }

        compose.onNodeWithContentDescription("Episodes").assertIsFocused()
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
            !detailsViewModel.state.value.isLoading &&
                detailsViewModel.state.value.resumeStateReady &&
                detailsViewModel.state.value.contentKey == "series:$seriesId"
        }
        assertEquals(continueEpisode, detailsViewModel.state.value.resumePlaybackId)
        awaitAppState { fixture.api.calls.any { it.contains("/stream/series/$continueEpisode.json") } }
        assertTrue(fixture.api.calls.any { it.contains("/stream/series/$continueEpisode.json") })
    }

    @Test fun addonDetailsRenderWhileTmdbEnrichmentIsStillPending() {
        val profile = runBlocking { fixture.dao.getProfileById(71)!! }
        runBlocking { fixture.dao.updateProfile(profile.copy(tmdbEnabled = true)) }
        fixture.api.metadata["tt1234567"] = MetaItem("tt1234567", "movie", "Immediate Addon Details")
        detailsViewModel.viewModelScope.cancel()
        tmdbLookupStarted = CountDownLatch(1)
        releaseTmdbLookup = CountDownLatch(1)

        val delayedTmdbApi = Proxy.newProxyInstance(
            TmdbApiService::class.java.classLoader,
            arrayOf(TmdbApiService::class.java)
        ) { _, method, _ ->
            if (method.name == "findByExternalId") {
                tmdbLookupStarted?.countDown()
                check(releaseTmdbLookup?.await(5, TimeUnit.SECONDS) == true) {
                    "Test did not release the pending TMDB lookup"
                }
            }
            Response.success<TmdbFindResponse>(200, TmdbFindResponse())
        } as TmdbApiService
        val tmdb = TmdbService(RuntimeEnvironment.getApplication(), delayedTmdbApi)
        val metadata = TmdbMetadataService(delayedTmdbApi, tmdb)
        detailsViewModel = DetailsViewModel(
            fixture.dao, fixture.sources, fixture.tracks, fixture.app.repository, fixture.subtitles,
            fixture.app.configuration, fixture.sorting, tmdb, metadata, fixture.traktSync,
            fixture.cache, fixture.app.display, fixture.sync, fixture.userStateCommands
        )
        detailsViewModel.loadDetails("movie", "tt1234567", addonBaseUrl = "https://fixture.invalid")
        awaitAppState {
            !detailsViewModel.state.value.isLoading && detailsViewModel.state.value.tmdbLoading &&
                tmdbLookupStarted?.count == 0L
        }

        compose.setContent {
            MaterialTheme {
                DetailsScreen(
                    type = "movie",
                    id = "tt1234567",
                    onPlayClick = { _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
                    viewModel = detailsViewModel,
                    trailerHostViewModel = homeViewModel
                )
            }
        }

        compose.onNodeWithText("Immediate Addon Details").assertExists()
        releaseTmdbLookup?.countDown()
        awaitAppState { !detailsViewModel.state.value.tmdbLoading }
    }

    @Test fun navigatingAwayDoesNotWaitForThePreviousDetailsTmdbLookup() {
        val imdbId = "tt98765432"
        val nextImdbId = "tt98765433"
        val profile = runBlocking { fixture.dao.getProfileById(71)!! }
        runBlocking { fixture.dao.updateProfile(profile.copy(tmdbEnabled = true)) }
        fixture.api.metadata[imdbId] = MetaItem(imdbId, "movie", "Cancelable Details")
        fixture.api.metadata[nextImdbId] = MetaItem(nextImdbId, "movie", "Next Details")
        detailsViewModel.viewModelScope.cancel()

        val lookupStarted = CountDownLatch(1)
        val releaseLookup = CountDownLatch(1)
        val delayedTmdbApi = Proxy.newProxyInstance(
            TmdbApiService::class.java.classLoader,
            arrayOf(TmdbApiService::class.java)
        ) { _, method, args ->
            if (method.name == "findByExternalId") {
                if (args?.firstOrNull() == imdbId) {
                    lookupStarted.countDown()
                    check(releaseLookup.await(5, TimeUnit.SECONDS)) {
                        "Test did not release the previous TMDB lookup"
                    }
                }
            }
            Response.success<TmdbFindResponse>(200, TmdbFindResponse())
        } as TmdbApiService
        val tmdb = TmdbService(RuntimeEnvironment.getApplication(), delayedTmdbApi)
        val metadata = TmdbMetadataService(delayedTmdbApi, tmdb)
        detailsViewModel = DetailsViewModel(
            fixture.dao, fixture.sources, fixture.tracks, fixture.app.repository, fixture.subtitles,
            fixture.app.configuration, fixture.sorting, tmdb, metadata, fixture.traktSync,
            fixture.cache, fixture.app.display, fixture.sync, fixture.userStateCommands
        )

        detailsViewModel.loadDetails("movie", imdbId, addonBaseUrl = "https://fixture.invalid")
        awaitAppState {
            !detailsViewModel.state.value.isLoading &&
                detailsViewModel.state.value.tmdbLoading &&
                lookupStarted.count == 0L
        }
        try {
            detailsViewModel.loadDetails("movie", nextImdbId, addonBaseUrl = "https://fixture.invalid")
            awaitAppState {
                !detailsViewModel.state.value.isLoading &&
                    detailsViewModel.state.value.resumeStateReady &&
                    detailsViewModel.state.value.meta?.name == "Next Details"
            }
            assertEquals("Next Details", detailsViewModel.state.value.meta?.name)
        } finally {
            // Identity lookups are process-scoped single-flight requests; the screen detaches
            // immediately, while a shared request is allowed to finish for another consumer/cache.
            releaseLookup.countDown()
        }
    }

    private fun showDetails(
        type: String,
        id: String,
        autoSelectSource: Boolean = false,
        onPlayClick: DetailsPlayHandler? = null
    ) {
        lastPlayRequest = null
        detailsViewModel.loadDetails(type, id, addonBaseUrl = "https://fixture.invalid")
        awaitAppState {
            !detailsViewModel.state.value.isLoading &&
                detailsViewModel.state.value.resumeStateReady &&
                detailsViewModel.state.value.contentKey == "$type:$id"
        }
        compose.setContent {
            MaterialTheme {
                DetailsScreen(
                    type = type,
                    id = id,
                    onPlayClick = onPlayClick ?: { url, playbackId, playbackType, playbackTitle,
                                                   _, _, _, _, _, _, detailContentId, detailContentType, detailPoster ->
                        lastPlayRequest = DetailsPlayRequest(
                            url, playbackId, playbackType, playbackTitle,
                            detailContentId, detailContentType, detailPoster
                        )
                    },
                    autoSelectSource = autoSelectSource,
                    viewModel = detailsViewModel,
                    trailerHostViewModel = homeViewModel
                )
            }
        }
    }
}
