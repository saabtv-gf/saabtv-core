package com.saab.tv.ui.watchlist

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.model.*
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.data.repository.AddonRepository
import com.saab.tv.data.repository.CanonicalMetadataRepository
import com.saab.tv.data.stream.TorBoxAvailabilityService
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
        vm = WatchlistViewModel(f.dao,f.app.repository,f.sync,f.userStateCommands)
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
        assertEquals("Profile 1", vm.libraryItems.value.watchlist.single().asActionItem().name)
        assertEquals("movie", vm.libraryItems.value.watchlist.single().asActionItem().type)
        vm.setProfileId(2)
        assertFalse("A previous profile must not flash during the new Room subscription", vm.libraryItems.value.watchlist
            .any { it.asActionItem().name == "Profile 1" })
        awaitAppState {
            vm.libraryItems.value.watchlist.size == 1 && vm.libraryItems.value.watchlist.single().asActionItem().type == "series"
        }
        vm.setProfileId(null); awaitAppState { vm.libraryItems.value == MyLibraryItems() }
    }
    @Test fun duplicateRemovalIsIdempotentAndProfileScoped() = runBlocking {
        add(1); add(2); vm.setProfileId(1); vm.remove(item); vm.remove(item)
        awaitAppState { runBlocking { !f.dao.isInWatchlist(1,"tt1") } }
        assertTrue(f.dao.isInWatchlist(2,"tt1"))
    }
    @Test fun profileChangeResetsFocusButSameProfileDoesNot() {
        vm.setProfileId(1); vm.lastFocusedSectionTitle = LIBRARY_WATCHLIST
        vm.lastFocusedKey="tt1"; vm.lastFocusedItemIndex = 3; vm.setProfileId(1)
        assertEquals("tt1",vm.lastFocusedKey); vm.setProfileId(2)
        assertNull(vm.lastFocusedKey)
        assertNull(vm.lastFocusedSectionTitle)
        assertNull(vm.lastFocusedItemIndex)
    }
    @Test fun noProfileAndCompleteExistingArtworkDoNotResolve() {
        vm.resolvePosterIfNeeded(item); vm.setProfileId(1)
        vm.resolvePosterIfNeeded(item.copy(
            poster="https://fixture.invalid/poster",
            background="https://fixture.invalid/backdrop",
            logo="https://fixture.invalid/logo"
        ))
        assertTrue(f.api.calls.isEmpty())
    }
    @Test fun inFlightPosterResolutionIsSingleFlightAndPersists() = runBlocking {
        val id = "tt1234567"
        val libraryItem = item.copy(id = id)
        f.dao.addToWatchlist(WatchlistEntity(1, id, "movie", "Movie", null, 1))
        val canonicalStore = CanonicalMetadataRepository(f.app.db.canonicalMetadataDao())
        val canonicalRepository = AddonRepository(
            f.api, f.dao, TorBoxAvailabilityService(f.context), canonicalStore, canonicalStore, canonicalStore
        )
        val canonicalVm = WatchlistViewModel(f.dao, canonicalRepository, f.sync, f.userStateCommands)
        collectors.launch { canonicalVm.libraryItems.collect {} }
        canonicalVm.setProfileId(1)
        f.api.metadata[id]=libraryItem.copy(
            poster="https://fixture.invalid/poster",
            background="https://fixture.invalid/backdrop",
            logo="https://fixture.invalid/logo"
        )
        val gate=CompletableDeferred<Unit>(); f.api.metadataGate=gate
        repeat(10) { canonicalVm.resolvePosterIfNeeded(libraryItem) }
        awaitAppState { f.api.calls.isNotEmpty() }
        assertEquals(1,f.api.calls.size); gate.complete(Unit)
        awaitAppState {
            canonicalVm.libraryItems.value.watchlist.singleOrNull()?.content
                ?.artwork(com.saab.tv.domain.schema.ArtworkRole.POSTER)?.locator != null
        }
        assertEquals(
            "https://fixture.invalid/poster",
            canonicalVm.libraryItems.value.watchlist.single().content
                ?.artwork(com.saab.tv.domain.schema.ArtworkRole.POSTER)?.locator
        )
        assertNull(f.dao.getWatchlistItem(1, id)?.poster) // artwork lives in the canonical metadata store, not duplicate user state
        canonicalVm.viewModelScope.cancel()
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
