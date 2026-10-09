package com.saab.tv.ui.details

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.model.*
import com.saab.tv.data.model.stremio.*
import com.saab.tv.testing.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DetailsOrchestrationP0Test {
    private lateinit var f: FeatureFixture
    private lateinit var vm: DetailsViewModel
    private val episodes = listOf(1, 2, 10).map { MetaVideo(id = "tt1:1:$it", season = 1, episode = it, title = "Episode $it") }
    @Before fun setup() = runBlocking {
        f = FeatureFixture(RuntimeEnvironment.getApplication())
        f.dao.insertProfile(ProfileEntity(id = 1, name = "One", isActive = true, tmdbEnabled = false, sourceSeasonPacksOnly = false))
        f.dao.insertAddon(AddonEntity("https://fixture.invalid", "fixture", "Fixture", "1", null, null, supportsStream = true))
        f.app.configuration.saveRuntimeState(1)
        f.api.metadata["tt1"] = MetaItem("tt1", "series", "Series", videos = episodes)
        vm = f.details()
    }
    @After fun cleanup() { vm.viewModelScope.cancel(); f.close() }
    private fun load(type: String = "series", id: String = "tt1") {
        vm.loadDetails(type, id, playbackOnly = true)
        awaitAppState { !vm.state.value.isLoading && vm.state.value.resumeStateReady }
    }
    private fun history(id: String, watched: Boolean, time: Long = 1, position: Long = 100_000) = runBlocking {
        f.dao.insertHistory(WatchHistoryEntity(profileId = 1, id = id, title = "Episode", poster = null, type = "series",
            position = position, duration = 1_000_000, watched = watched, lastWatched = time))
    }
    @Test fun episodeSidebarUsesCanonicalNonContiguousEpisodes() {
        load(); vm.openEpisodes()
        val sidebar = vm.state.value.sidebarState as SidebarState.Episodes
        assertEquals(listOf(1,2,10), sidebar.videos.map { it.episode })
    }
    @Test fun specificEpisodeRequestsItsOwnSourcesInsteadOfEpisodeOne() {
        f.api.streams["tt1:1:10"] = listOf(Stream(url = "https://fixture.invalid/episode10", title = "1080p English"))
        vm.loadStreams("series", "tt1:1:10", "Episode 10", autoSelectSource = true)
        awaitAppState { vm.state.value.autoPlayStream != null }
        assertEquals("https://fixture.invalid/episode10", vm.state.value.autoPlayStream?.url)
        assertTrue(f.api.calls.any { it.contains("/stream/series/tt1:1:10.json") })
        assertFalse(f.api.calls.any { it.contains("/stream/series/tt1:1:1.json") })
    }
    @Test fun malformedExactEpisodeTargetNeverFallsBackToEpisodeOne() {
        vm.loadStreams("series", "tt1:99:99", "Unavailable Episode", autoSelectSource = true)
        awaitAppState { !vm.state.value.isLoadingStreams }
        assertNull(vm.state.value.autoPlayStream)
        assertTrue(f.api.calls.any { it.contains("/stream/series/tt1:99:99.json") })
        assertFalse(f.api.calls.any { it.contains("/stream/series/tt1:1:1.json") })
    }
    @Test fun latestPlayedEpisodeWinsOverFurthestProgress() {
        history("tt1:1:1", false, 1, 800_000); history("tt1:1:2", false, 2, 0)
        load(); assertEquals("tt1:1:2", vm.state.value.resumePlaybackId)
        assertEquals("tt1:1:2", vm.state.value.lastPlayedEpisodeId)
    }
    @Test fun watchedEpisodeOffersNextEpisodeRatherThanResumingCompletedOne() {
        history("tt1:1:1", true)
        load(); assertEquals("tt1:1:2", vm.state.value.resumePlaybackId)
        assertTrue(vm.state.value.resumeIsNextEpisode)
    }
    @Test fun completedSeriesHasNoResumeAndRemovesWatchlistEntry() = runBlocking {
        episodes.forEach { history(it.id, true) }
        f.dao.addToWatchlist(WatchlistEntity(profileId = 1, id = "tt1", type = "series", title = "Series", poster = null, addedAt = 1))
        load(); assertNull(vm.state.value.resumePlaybackId)
        assertTrue(f.dao.getSeriesNextUp("tt1")!!.isComplete)
        assertFalse(f.dao.isInWatchlist(1, "tt1"))
    }
    @Test fun indexedHistorySuffixMapsToCorrectSeasonEpisode() {
        history("tt1:1:2:0", false, position = 250_000)
        load()
        assertEquals(0.25f, vm.state.value.episodeProgressMap["S1:E2"]!!.progress)
        assertEquals("tt1:1:2:0", vm.state.value.resumePlaybackId)
    }
    @Test fun forcedPickerDoesNotAutoplayRememberedSource() {
        val stream = Stream(url = "https://fixture.invalid/movie", title = "1080p English")
        f.api.streams["tt1"] = listOf(stream); f.sources.rememberSelection("tt1", stream)
        vm.loadStreams("movie", "tt1", "Movie", forceSourcePicker = true, autoSelectSource = true)
        awaitAppState { vm.state.value.sidebarState is SidebarState.Sources && !vm.state.value.isLoadingStreams }
        assertNull(vm.state.value.autoPlayStream); assertEquals(1, vm.state.value.availableStreams.size)
    }
    @Test fun emptySourcesFinishLoadingAndKeepPickerUsable() {
        vm.loadStreams("series", "tt1:1:2", "Episode 2", autoSelectSource = true)
        awaitAppState { vm.state.value.sidebarState is SidebarState.Sources && !vm.state.value.isLoadingStreams }
        assertNull(vm.state.value.autoPlayStream); assertTrue(vm.state.value.availableStreams.isEmpty())
    }
    @Test fun pickerAndAutoplayUseSameDescendingScoreOrder() {
        f.api.streams["tt1"] = listOf(Stream(url = "https://fixture.invalid/720", title = "720p English", seeders = 200),
            Stream(url = "https://fixture.invalid/1080", title = "1080p English", seeders = 20))
        vm.loadStreams("movie", "tt1", "Movie", autoSelectSource = true, rememberSourceSelection = false)
        awaitAppState { vm.state.value.autoPlayStream != null }
        assertEquals(vm.state.value.availableStreams.first(), vm.state.value.autoPlayStream)
        assertEquals("https://fixture.invalid/1080", vm.state.value.autoPlayStream?.url)
        vm.consumeAutoPlayStream(); assertNull(vm.state.value.autoPlayStream)
    }
    @Test fun reopeningSameDetailsClosesSidebarWithoutResettingMetadata() {
        load(); val meta = vm.state.value.meta
        vm.openEpisodes(); load()
        assertEquals(meta, vm.state.value.meta); assertTrue(vm.state.value.sidebarState is SidebarState.Closed)
    }
    @Test fun markingMovieWatchedRemovesWatchlistAndCanBeUndone() = runBlocking {
        f.api.metadata["movie-id"] = MetaItem("movie-id", "movie", "Movie")
        f.dao.addToWatchlist(WatchlistEntity(profileId = 1, id = "movie-id", type = "movie", title = "Movie", poster = null, addedAt = 1))
        vm.loadDetails("movie", "movie-id", playbackOnly = true)
        awaitAppState { !vm.state.value.isLoading }

        vm.toggleMovieWatched()
        awaitAppState {
            vm.state.value.isMovieWatched && runBlocking {
                f.dao.getHistoryItem("movie-id")?.watched == true && !f.dao.isInWatchlist(1, "movie-id")
            }
        }
        assertTrue(vm.state.value.isMovieWatched)

        vm.toggleMovieWatched()
        awaitAppState { !vm.state.value.isMovieWatched && runBlocking { f.dao.getHistoryItem("movie-id") == null } }
        assertFalse(vm.state.value.isMovieWatched)
    }

    @Test fun clearingMovieProgressAlsoClearsRememberedSourceAndTracks() = runBlocking {
        f.api.metadata["movie-id"] = MetaItem("movie-id", "movie", "Movie")
        f.dao.upsertHistory(WatchHistoryEntity(profileId = 1, id = "movie-id", title = "Movie", poster = null,
            position = 420_000, duration = 1_000_000, watched = false, lastWatched = 10, type = "movie"))
        val source = Stream(url = "https://fixture.invalid/movie", title = "1080p")
        f.sources.rememberSelection("movie-id", source)
        f.tracks.updateSelection("movie-id", "audio-en", "subtitle-en", updateAudio = true, updateSubtitle = true)
        vm.loadDetails("movie", "movie-id", playbackOnly = true)
        awaitAppState { !vm.state.value.isLoading }

        vm.confirmClearProgress()
        awaitAppState {
            runBlocking { f.dao.getHistoryItem("movie-id") == null } &&
                !f.sources.hasRememberedSelection("movie-id") &&
                f.tracks.getSelection("movie-id") == null &&
                vm.state.value.resumePlaybackId == null
        }
        assertNull(vm.state.value.resumePlaybackId)
    }

    @Test fun clearingSeriesProgressRemovesAllEpisodesAndEpisodeSelections() = runBlocking {
        history("tt1:1:1", watched = true, time = 10)
        history("tt1:1:2:0", watched = false, time = 20, position = 250_000)
        f.sources.rememberSelection("tt1:1:2", Stream(url = "https://fixture.invalid/episode2", title = "720p"))
        f.tracks.updateSelection("tt1:1:2", "audio-en", null, updateAudio = true, updateSubtitle = false)
        load()
        assertTrue(vm.state.value.episodeProgressMap.isNotEmpty())

        vm.confirmClearProgress()
        awaitAppState {
            runBlocking { f.dao.getSeriesEpisodeHistory("tt1:%").isEmpty() } &&
                !f.sources.hasRememberedSelection("tt1:1:2") &&
                f.tracks.getSelection("tt1:1:2") == null &&
                vm.state.value.episodeProgressMap.isEmpty() &&
                vm.state.value.resumePlaybackId == null
        }
        assertTrue(vm.state.value.episodeProgressMap.isEmpty())
        assertNull(f.dao.getSeriesNextUp("tt1"))
    }

    @Test fun newTitleClearsOldMetadataImmediatelyAndMissingMetaEndsLoading() {
        load(); vm.loadDetails("series", "missing", playbackOnly = true)
        assertNull(vm.state.value.meta)
        awaitAppState { !vm.state.value.isLoading }; assertNull(vm.state.value.meta)
    }
}
