package com.saab.tv.ui.home

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
@Config(sdk=[28],application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class HomeOrchestrationP1Test {
    private lateinit var f: FeatureFixture
    private lateinit var vm: HomeViewModel
    private val movie=MetaItem("tt1","movie","Movie")
    @Before fun setup() = runBlocking {
        f=FeatureFixture(RuntimeEnvironment.getApplication())
        f.dao.insertProfile(ProfileEntity(id=1,name="One",isActive=true,tmdbEnabled=false))
        f.dao.insertProfile(ProfileEntity(id=2,name="Two"))
        f.app.configuration.saveRuntimeState(1); vm=f.home()
    }
    @After fun cleanup() { vm.viewModelScope.cancel(); f.close() }
    @Test fun watchlistToggleAddsThenRemovesOnlySelectedProfile() = runBlocking {
        f.dao.addToWatchlist(WatchlistEntity(2,"tt1","movie","Other",null,1))
        vm.toggleWatchlist(1,movie); awaitAppState { runBlocking { f.dao.isInWatchlist(1,"tt1") } }
        assertEquals("Movie",f.dao.getWatchlistItem(1,"tt1")?.title)
        vm.toggleWatchlist(1,movie); awaitAppState { runBlocking { !f.dao.isInWatchlist(1,"tt1") } }
        assertTrue(f.dao.isInWatchlist(2,"tt1"))
    }
    @Test fun clearMovieProgressDoesNotClearOtherProfileOrTitle() = runBlocking {
        listOf(1 to "tt1",2 to "tt1",1 to "tt2").forEach { (profile,id) ->
            f.dao.insertHistory(WatchHistoryEntity(profile,id,"Title",null,position=10000,duration=100000,lastWatched=1,type="movie"))
        }
        vm.clearContinueProgress(1,movie)
        awaitAppState { runBlocking { f.dao.getHistoryItemForProfile(1,"tt1") == null } }
        assertNotNull(f.dao.getHistoryItemForProfile(2,"tt1")); assertNotNull(f.dao.getHistoryItemForProfile(1,"tt2"))
    }
    @Test fun clearSeriesProgressClearsAllItsEpisodesButNotOtherSeries() = runBlocking {
        listOf("tt1:1:1","tt1:2:2","tt2:1:1").forEach { id ->
            f.dao.insertHistory(WatchHistoryEntity(1,id,"Episode",null,position=10000,duration=100000,lastWatched=1,type="series"))
        }
        vm.clearContinueProgress(1,movie.copy(type="series"))
        awaitAppState { runBlocking { f.dao.getHistoryItemForProfile(1,"tt1:2:2") == null } }
        assertNull(f.dao.getHistoryItemForProfile(1,"tt1:1:1")); assertNotNull(f.dao.getHistoryItemForProfile(1,"tt2:1:1"))
    }
    @Test fun embeddedTrailerMetadataAvoidsExtraProviderLookup() = runBlocking {
        val result=vm.trailerFor(movie.copy(trailerStreams=listOf(MetaTrailerStream(ytId="B3tR6qQjbgI",title="Trailer"))))
        assertEquals("B3tR6qQjbgI",result?.first); assertTrue(f.api.calls.isEmpty())
    }
    @Test fun noProfileMakesTrailerPrefetchANoOp() = runBlocking {
        f.dao.insertProfile(f.dao.getProfileById(1)!!.copy(isActive=false))
        vm.prefetchTrailerSources(movie); assertTrue(f.api.calls.isEmpty())
    }
    @Test fun emptyCatalogCompletesLoadingWithoutGhostRowsAndResetsFocus() {
        vm.setLastFocusedKey("old")
        vm.loadScreen("movies",ProfileEntity(id=1,name="One",tmdbEnabled=false))
        awaitAppState { !vm.state.value.isLoading && vm.state.value.loadedScreen == "movies" }
        assertTrue(vm.state.value.rows.isEmpty()); assertNull(vm.state.value.lastFocusedKey)
    }
}
