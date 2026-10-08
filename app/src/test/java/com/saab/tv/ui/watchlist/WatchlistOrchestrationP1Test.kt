package com.saab.tv.ui.watchlist

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.model.*
import com.saab.tv.data.model.stremio.MetaItem
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
class WatchlistOrchestrationP1Test {
    private lateinit var f: FeatureFixture
    private lateinit var vm: WatchlistViewModel
    private lateinit var collectors: CoroutineScope
    private val item = MetaItem("tt1", "movie", "Movie")
    @Before fun setup() = runBlocking {
        f = FeatureFixture(RuntimeEnvironment.getApplication())
        f.dao.insertProfile(ProfileEntity(id=1,name="One"))
        f.dao.insertProfile(ProfileEntity(id=2,name="Two"))
        vm = WatchlistViewModel(f.dao,f.app.repository,f.sync)
        collectors = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        collectors.launch { vm.libraryItems.collect {} }
        Unit
    }
    @After fun cleanup() { collectors.cancel(); vm.viewModelScope.cancel(); f.close() }
    private fun add(profile: Int, type: String = "movie", poster: String? = null) = runBlocking {
        f.dao.addToWatchlist(WatchlistEntity(profile,"tt1",type,"Profile $profile",poster,1))
    }
    @Test fun rowsFollowProfileAndTypeAndNullProfileClearsBoth() {
        add(1); add(2,"series"); vm.setProfileId(1)
        awaitAppState { vm.libraryItems.value.watchlist.size == 1 }
        assertEquals("Profile 1", vm.libraryItems.value.watchlist.single().name)
        assertEquals("movie", vm.libraryItems.value.watchlist.single().type)
        vm.setProfileId(2)
        awaitAppState {
            vm.libraryItems.value.watchlist.size == 1 && vm.libraryItems.value.watchlist.single().type == "series"
        }
        vm.setProfileId(null); awaitAppState { vm.libraryItems.value == MyLibraryItems() }
    }
    @Test fun duplicateRemovalIsIdempotentAndProfileScoped() = runBlocking {
        add(1); add(2); vm.setProfileId(1); vm.remove(item); vm.remove(item)
        awaitAppState { runBlocking { !f.dao.isInWatchlist(1,"tt1") } }
        assertTrue(f.dao.isInWatchlist(2,"tt1"))
    }
    @Test fun profileChangeResetsFocusButSameProfileDoesNot() {
        vm.setProfileId(1); vm.lastFocusedKey="tt1"; vm.setProfileId(1)
        assertEquals("tt1",vm.lastFocusedKey); vm.setProfileId(2); assertNull(vm.lastFocusedKey)
    }
    @Test fun existingPosterAndNoProfileDoNotResolve() {
        vm.resolvePosterIfNeeded(item); vm.setProfileId(1)
        vm.resolvePosterIfNeeded(item.copy(poster="https://fixture.invalid/poster"))
        assertTrue(f.api.calls.isEmpty())
    }
    @Test fun inFlightPosterResolutionIsSingleFlightAndPersists() = runBlocking {
        add(1); vm.setProfileId(1)
        f.api.metadata["tt1"]=item.copy(poster="https://fixture.invalid/poster")
        val gate=CompletableDeferred<Unit>(); f.api.metadataGate=gate
        repeat(10) { vm.resolvePosterIfNeeded(item) }
        awaitAppState { f.api.calls.isNotEmpty() }
        assertEquals(1,f.api.calls.size); gate.complete(Unit)
        awaitAppState { runBlocking { f.dao.getWatchlistItem(1,"tt1")?.poster != null } }
        assertEquals("https://fixture.invalid/poster",f.dao.getWatchlistItem(1,"tt1")?.poster)
    }
    @Test fun deletionDuringResolutionCannotResurrectTitle() = runBlocking {
        add(1); vm.setProfileId(1); f.api.metadata["tt1"]=item.copy(poster="poster")
        val gate=CompletableDeferred<Unit>(); f.api.metadataGate=gate
        vm.resolvePosterIfNeeded(item); awaitAppState { f.api.calls.isNotEmpty() }
        f.dao.removeFromWatchlist(1,"tt1"); gate.complete(Unit)
        // Wait for the resolver's finally block, not just the provider gate.
        val field=WatchlistViewModel::class.java.getDeclaredField("resolveInFlight").apply { isAccessible=true }
        awaitAppState { (field.get(vm) as Set<*>).isEmpty() }
        assertNull(f.dao.getWatchlistItem(1,"tt1"))
    }
}
