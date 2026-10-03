package com.saab.tv.data.trakt

import android.app.Application
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.SeriesNextUpEntity
import com.saab.tv.data.model.WatchHistoryEntity
import com.saab.tv.data.model.WatchlistEntity
import com.saab.tv.data.model.trakt.*
import com.saab.tv.data.remote.TraktSyncApiService
import com.saab.tv.data.security.SecurePreferences
import com.saab.tv.testing.FeatureFixture
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import retrofit2.Response

/** End-to-end Trakt reconciliation cases backed by a deterministic API fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TraktSyncManagerRegressionScenariosTest {
    private lateinit var fixture: FeatureFixture
    private lateinit var api: FakeTraktSyncApi
    private lateinit var manager: TraktSyncManager

    @Before fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        fixture = FeatureFixture(context)
        fixture.dao.insertProfile(ProfileEntity(id = 101, name = "Trakt", isActive = true))
        fixture.app.configuration.saveRuntimeState(101)
        SecurePreferences.create(context, "trakt_auth", "saabtv_trakt_master_key")
            .preferences.edit().putString("access_token_101", "fixture-access-token").commit()
        fixture.traktAuth.refreshConnectionState()
        api = FakeTraktSyncApi()
        manager = TraktSyncManager(api, fixture.traktAuth, fixture.dao, fixture.app.configuration, fixture.cache)
    }

    @After fun tearDown() = fixture.close()

    @Test fun watchlistPullsNewMovieAndShowAndRemovesOnlyMissingImdbItems() = runBlocking {
        fixture.dao.addToWatchlist(WatchlistEntity(101, "tt-removed", "movie", "Removed", null, 1))
        fixture.dao.addToWatchlist(WatchlistEntity(101, "addon-local", "movie", "Unmatchable", null, 2))
        api.watchlist = listOf(
            TraktWatchlistItem(1, 1, null, "movie", TraktMovie("New movie", 2025, TraktIds(imdb = "tt-new")), null),
            TraktWatchlistItem(2, 2, null, "show", null, TraktShow("New show", 2024, TraktIds(imdb = "tt-show"))),
            TraktWatchlistItem(3, 3, null, "unknown", null, null)
        )

        assertTrue(manager.syncWatchlist().isSuccess)

        assertNull(fixture.dao.getWatchlistItem(101, "tt-removed"))
        assertEquals("Unmatchable", fixture.dao.getWatchlistItem(101, "addon-local")?.title)
        assertEquals("New movie", fixture.dao.getWatchlistItem(101, "tt-new")?.title)
        assertEquals("series", fixture.dao.getWatchlistItem(101, "tt-show")?.type)
        assertEquals(listOf(1), api.watchlistPages)
    }

    @Test fun failedLaterWatchlistPageDoesNotApplyPartialResultsOrDeleteLocalItems() = runBlocking {
        val local = WatchlistEntity(101, "tt-local", "movie", "Keep me", null, 1)
        fixture.dao.addToWatchlist(local)
        api.watchlistPageOne = List(100) { index ->
            TraktWatchlistItem(index, index.toLong(), null, "movie", TraktMovie("Remote $index", 2024, TraktIds(imdb = "tt$index")), null)
        }
        api.failWatchlistPage = 2

        assertTrue(manager.syncWatchlist().isFailure)

        assertEquals("Keep me", fixture.dao.getWatchlistItem(101, "tt-local")?.title)
        assertNull(fixture.dao.getWatchlistItem(101, "tt0"))
        assertEquals(listOf(1, 2), api.watchlistPages)
    }

    @Test fun activityPollingSyncsChangedCategoriesOnceAndSkipsUnchangedTimestamps() = runBlocking {
        api.activities = TraktLastActivities(
            watchlist = TraktActivityTimestamp("2026-10-03T10:00:00Z"),
            episodes = TraktActivityTimestamps(watchedAt = "2026-10-03T10:02:00Z", pausedAt = "2026-10-03T10:01:00Z"),
            movies = TraktActivityTimestamps(watchedAt = "2026-10-03T10:03:00Z", pausedAt = "2026-10-03T09:59:00Z")
        )

        assertTrue(manager.checkAndSync())
        val callsAfterFirstPoll = api.syncReadCalls
        assertEquals(1, api.activityCalls)
        assertTrue(callsAfterFirstPoll > 1)

        assertFalse(manager.checkAndSync())
        assertEquals(2, api.activityCalls)
        assertEquals("unchanged activity must not repeat category downloads", callsAfterFirstPoll, api.syncReadCalls)
    }

    @Test fun playbackSyncPreservesActiveLocalProgressImportsRemoteProgressAndReconcilesWatchedState() = runBlocking {
        addHistory("tt-active", "movie", position = 410_000)
        addHistory("tt-cleared", "movie", position = 100_000)
        addHistory("tt-watched", "movie", position = 800_000)
        addHistory("tt-series:1:2:0", "series", position = 250_000)
        api.playback = listOf(
            TraktPlaybackItem(1, 45f, "movie", null, TraktMovie("Active elsewhere", null, TraktIds(imdb = "tt-active")), null, null),
            TraktPlaybackItem(2, 150f, "movie", "2026-10-03T10:00:00Z", TraktMovie("Remote new", null, TraktIds(imdb = "tt-new")), null, null)
        )
        api.watchedMovies = listOf(TraktWatchedMovie(TraktMovie("Finished", null, TraktIds(imdb = "tt-watched")), null))
        api.watchedShows = listOf(
            TraktWatchedShow(
                TraktShow("Series", null, TraktIds(imdb = "tt-series", slug = "series")), null,
                listOf(TraktWatchedSeason(1, listOf(TraktWatchedEpisode(2, null))))
            )
        )

        manager.syncPlaybackProgress()

        assertEquals(410_000L, fixture.dao.getHistoryItem("tt-active")?.position)
        assertNull(fixture.dao.getHistoryItem("tt-cleared"))
        assertTrue(fixture.dao.getHistoryItem("tt-watched")?.watched == true)
        assertTrue(fixture.dao.getHistoryItem("tt-series:1:2:0")?.watched == true)
        assertEquals(90 * 60 * 1000L, fixture.dao.getHistoryItem("tt-new")?.position)
        assertEquals(90 * 60 * 1000L, fixture.dao.getHistoryItem("tt-new")?.duration)
    }

    @Test fun watchedHistoryFailureNeverDeletesLocalProgress() = runBlocking {
        addHistory("tt-protected", "movie", position = 300_000)
        api.playback = emptyList()
        api.failWatchedShows = true

        manager.syncPlaybackProgress()

        assertEquals(300_000L, fixture.dao.getHistoryItem("tt-protected")?.position)
    }

    @Test fun watchedSeriesProgressSetsNextEpisodeAndRecordsSpecificWatchedEpisodes() = runBlocking {
        api.watchedShows = listOf(
            TraktWatchedShow(
                TraktShow("Show", 2025, TraktIds(imdb = "tt-show", slug = "show-slug")), "2026-09-30T10:00:00Z",
                listOf(TraktWatchedSeason(2, listOf(TraktWatchedEpisode(3, "2026-09-30T10:00:00Z"))))
            )
        )
        api.progressBySlug["show-slug"] = TraktShowProgress(
            aired = 8, completed = 4,
            nextEpisode = TraktProgressEpisode(2, 4, "Next", firstAired = "2026-10-10T00:00:00Z")
        )

        manager.syncSeriesNextUp()

        assertEquals(4, fixture.dao.getSeriesNextUp("tt-show")?.nextEpisode)
        assertEquals("Next", fixture.dao.getSeriesNextUp("tt-show")?.nextEpisodeTitle)
        assertTrue(fixture.dao.getHistoryItem("tt-show:2:3")?.watched == true)
    }

    @Test fun locallySelectedMovieAndEpisodeActionsSendCorrectTraktPayloads() = runBlocking {
        manager.pushAdd(WatchlistEntity(101, "tt-movie", "movie", "Movie", null, 1))
        manager.pushAdd(WatchlistEntity(101, "tt-show", "series", "Show", null, 2))
        manager.pushRemove("tt-movie", "movie")
        manager.pushRemove("tt-show", "series")
        manager.pushMovieWatched("tt-movie")
        manager.pushMovieUnwatched("tt-movie")
        manager.pushEpisodeWatched("tt-show", 2, 4)
        manager.pushEpisodeUnwatched("tt-show", 2, 4)

        assertEquals("tt-movie", api.watchlistAdds[0].movies?.single()?.ids?.imdb)
        assertEquals("tt-show", api.watchlistAdds[1].shows?.single()?.ids?.imdb)
        assertEquals(2, api.watchlistRemovals.size)
        assertEquals("tt-movie", api.watchlistRemovals[0].movies?.single()?.ids?.imdb)
        assertEquals("tt-show", api.watchlistRemovals[1].shows?.single()?.ids?.imdb)
        assertEquals("tt-movie", api.historyAdds[0].movies?.single()?.ids?.imdb)
        assertEquals("tt-movie", api.historyRemovals[0].movies?.single()?.ids?.imdb)
        assertEquals(TraktSyncSeason(2, listOf(TraktSyncEpisode(4))), api.historyAdds[1].shows?.single()?.seasons?.single())
        assertEquals(TraktSyncSeason(2, listOf(TraktSyncEpisode(4))), api.historyRemovals[1].shows?.single()?.seasons?.single())
    }

    @Test fun clearingProgressDeletesMatchingTraktPlaybackUsingNormalizedEpisodeId() = runBlocking {
        api.playback = listOf(
            TraktPlaybackItem(42, 30f, "episode", null, null,
                TraktShow("Show", null, TraktIds(imdb = "tt-show")), TraktPlaybackEpisode(2, 4, "Episode")),
            TraktPlaybackItem(43, 30f, "movie", null,
                TraktMovie("Other", null, TraktIds(imdb = "tt-other")), null, null)
        )

        manager.deletePlaybackFromTrakt("tt-show:2:4:0")

        assertEquals(listOf(42L), api.deletedPlaybackIds)
    }

    @Test fun noNextEpisodeMarksExistingSeriesAsComplete() = runBlocking {
        fixture.dao.upsertSeriesNextUp(
            SeriesNextUpEntity(
                profileId = 101, seriesId = "tt-complete", title = "Complete", poster = null,
                nextSeason = 2, nextEpisode = 1, nextEpisodeTitle = "Old next", updatedAt = 1
            )
        )
        api.watchedShows = listOf(
            TraktWatchedShow(TraktShow("Complete", null, TraktIds(imdb = "tt-complete", slug = "complete")), null, null)
        )
        api.progressBySlug["complete"] = TraktShowProgress(aired = 10, completed = 10, nextEpisode = null)

        manager.syncSeriesNextUp()

        assertTrue(fixture.dao.getSeriesNextUp("tt-complete")?.isComplete == true)
    }

    private suspend fun addHistory(id: String, type: String, position: Long) {
        fixture.dao.upsertHistory(
            WatchHistoryEntity(
                profileId = 101, id = id, title = id, poster = null, position = position,
                duration = 1_000_000, lastWatched = 1, type = type, watched = false, scrobbled = true
            )
        )
    }

    private class FakeTraktSyncApi : TraktSyncApiService {
        val watchlistPages = mutableListOf<Int>()
        var activities = TraktLastActivities(null, null, null)
        var activityCalls = 0
        var syncReadCalls = 0
        var watchlist: List<TraktWatchlistItem> = emptyList()
        var watchlistPageOne: List<TraktWatchlistItem>? = null
        var failWatchlistPage: Int? = null
        var playback: List<TraktPlaybackItem> = emptyList()
        var watchedMovies: List<TraktWatchedMovie> = emptyList()
        var watchedShows: List<TraktWatchedShow> = emptyList()
        var failWatchedShows = false
        val progressBySlug = mutableMapOf<String, TraktShowProgress>()
        val watchlistAdds = mutableListOf<TraktSyncRequest>()
        val watchlistRemovals = mutableListOf<TraktSyncRequest>()
        val historyAdds = mutableListOf<TraktSyncRequest>()
        val historyRemovals = mutableListOf<TraktSyncRequest>()
        val deletedPlaybackIds = mutableListOf<Long>()

        override suspend fun getLastActivities(): Response<TraktLastActivities> {
            activityCalls++
            return Response.success(activities)
        }
        override suspend fun getWatchlist(page: Int, limit: Int): Response<List<TraktWatchlistItem>> {
            syncReadCalls++
            watchlistPages += page
            if (page == failWatchlistPage) return errorResponse()
            val rows = if (page == 1) watchlistPageOne ?: watchlist else emptyList()
            return Response.success(rows)
        }
        override suspend fun addToWatchlist(body: TraktSyncRequest): Response<TraktSyncResponse> {
            watchlistAdds += body
            return Response.success(emptySyncResponse())
        }
        override suspend fun removeFromWatchlist(body: TraktSyncRequest): Response<TraktSyncResponse> {
            watchlistRemovals += body
            return Response.success(emptySyncResponse())
        }
        override suspend fun getPlaybackProgress(): Response<List<TraktPlaybackItem>> {
            syncReadCalls++
            return Response.success(playback)
        }
        override suspend fun deletePlaybackItem(playbackId: Long): Response<Unit> {
            deletedPlaybackIds += playbackId
            return Response.success(Unit)
        }
        override suspend fun getWatchedMovies(): Response<List<TraktWatchedMovie>> {
            syncReadCalls++
            return Response.success(watchedMovies)
        }
        override suspend fun getWatchedShows(): Response<List<TraktWatchedShow>> {
            syncReadCalls++
            return if (failWatchedShows) errorResponse() else Response.success(watchedShows)
        }
        override suspend fun getShowProgress(showId: String): Response<TraktShowProgress> {
            syncReadCalls++
            return Response.success(progressBySlug[showId] ?: TraktShowProgress(null, null, null))
        }
        override suspend fun addToHistory(body: TraktSyncRequest): Response<TraktSyncResponse> {
            historyAdds += body
            return Response.success(emptySyncResponse())
        }
        override suspend fun removeFromHistory(body: TraktSyncRequest): Response<TraktSyncResponse> {
            historyRemovals += body
            return Response.success(emptySyncResponse())
        }
        override suspend fun scrobbleStart(body: TraktScrobbleRequest) = Response.success("{}".toResponseBody())
        override suspend fun scrobblePause(body: TraktScrobbleRequest) = Response.success("{}".toResponseBody())
        override suspend fun scrobbleStop(body: TraktScrobbleRequest) = Response.success("{}".toResponseBody())

        private fun emptySyncResponse() = TraktSyncResponse(null, null, null, null)
        private inline fun <reified T> errorResponse(): Response<T> = Response.error(
            503, "fixture offline".toResponseBody("text/plain".toMediaType())
        )
    }
}
