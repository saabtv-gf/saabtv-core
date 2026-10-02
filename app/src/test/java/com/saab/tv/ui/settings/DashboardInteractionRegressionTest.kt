package com.saab.tv.ui.settings

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.model.*
import com.saab.tv.testing.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DashboardInteractionRegressionTest {
    private lateinit var app: OfflineAppFixture
    private lateinit var vm: DashboardViewModel
    private fun catalog(id: String, order: Int) = CatalogConfigEntity(id, "https://offline.invalid", "Addon", "movie", id,
        showInHome = true, homeOrder = order)
    @Before fun setup() = runBlocking {
        app = OfflineAppFixture(RuntimeEnvironment.getApplication())
        app.dao.saveCatalogConfigs(listOf(catalog("a", 0), catalog("b", 2)))
        app.dao.insertHubRowWithItems(HubRowEntity("hub", "Hub", "SQUARE", showInHome = true, homeOrder = 1), emptyList())
        vm = DashboardViewModel(app.dao, app.configuration)
        awaitAppState { vm.getEditorItems("home").size == 3 }
    }
    @After fun cleanup() { vm.viewModelScope.cancel(); app.close() }
    @Test fun combinesCatalogsAndHubsInTabOrderWithoutLeakingHiddenTabs() {
        assertEquals(listOf("cat:a", "hub:hub", "cat:b"), vm.getEditorItems("home").map { it.key })
        listOf("movies", "series", "unknown").forEach { assertTrue(vm.getEditorItems(it).isEmpty()) }
    }
    @Test fun movingCatalogAcrossHubNormalizesBothKindsOfOrder() {
        vm.moveEditorItem(vm.getEditorItems("home").last(), -1, "home")
        awaitAppState { vm.getEditorItems("home").map { it.key } == listOf("cat:a", "cat:b", "hub:hub") }
        assertEquals(listOf(0, 1, 2), vm.getEditorItems("home").map { it.order })
    }
    @Test fun outOfBoundsAndMissingMovesDoNotChangeOrder() {
        vm.moveEditorItem(vm.getEditorItems("home").first(), -1, "home")
        vm.moveEditorItem(EditorListItem.CategoryItem(catalog("absent", 9), 9), 1, "home")
        assertEquals(listOf("cat:a", "hub:hub", "cat:b"), vm.getEditorItems("home").map { it.key })
    }
    @Test fun addingAndRemovingCatalogFromMoviesPreservesHomeVisibility() {
        val item = vm.getEditorItems("home").first()
        vm.addItemToTab(item, "movies")
        awaitAppState { vm.getEditorItems("movies").size == 1 }
        val added = vm.getEditorItems("movies").single()
        assertEquals(0, added.order)
        vm.removeItemFromTab(added, "movies")
        awaitAppState { vm.getEditorItems("movies").isEmpty() }
        assertEquals(3, vm.getEditorItems("home").size)
    }
    @Test fun addingAndRemovingHubFromSeriesPreservesHomeVisibility() {
        vm.addItemToTab(vm.getEditorItems("home")[1], "series")
        awaitAppState { vm.getEditorItems("series").size == 1 }
        vm.removeItemFromTab(vm.getEditorItems("series").single(), "series")
        awaitAppState { vm.getEditorItems("series").isEmpty() }
        assertEquals(3, vm.getEditorItems("home").size)
    }
    @Test fun catalogRenameAndLayoutClampingPersistToObservedState() {
        vm.renameCatalog(vm.configs.value.first { it.uniqueId == "a" }, "Favorites")
        awaitAppState { vm.configs.value.any { it.customTitle == "Favorites" } }
        vm.updateLayoutSettings(vm.configs.value.first { it.uniqueId == "a" }, true, 1000, false)
        awaitAppState { vm.configs.value.first { it.uniqueId == "a" }.visibleItemCount == 50 }
        val updated = vm.configs.value.first { it.uniqueId == "a" }
        assertTrue(updated.isInfiniteLoopEnabled); assertFalse(updated.isInfiniteScrollingEnabled)
        vm.updateLayoutSettings(updated, false, 0, true)
        awaitAppState { vm.configs.value.first { it.uniqueId == "a" }.visibleItemCount == 5 }
    }
    @Test fun hubRenameAndDeletionAreReflectedInEditor() {
        vm.renameHubRow("hub", "Renamed")
        awaitAppState { vm.hubRows.value.single().hub.title == "Renamed" }
        vm.deleteHubRow("hub")
        awaitAppState { vm.hubRows.value.isEmpty() }
        assertEquals(listOf("cat:a", "cat:b"), vm.getEditorItems("home").map { it.key })
    }
    @Test fun hubCategoryAppendRenameArtworkAndRemovePersist() {
        val config = vm.configs.value.first { it.uniqueId == "a" }
        vm.addCategoryToHubRow("hub", config)
        awaitAppState { vm.hubRows.value.single().items.size == 1 }
        assertEquals(0, vm.hubRows.value.single().items.single().itemOrder)
        vm.renameHubRowItem("hub", "a", "Custom")
        awaitAppState { vm.hubRows.value.single().items.single().title == "Custom" }
        vm.updateHubItemImage("hub", "a", "https://offline.invalid/image.jpg")
        awaitAppState { vm.hubRows.value.single().items.single().customImageUrl != null }
        vm.removeCategoryFromHubRow("hub", "a")
        awaitAppState { vm.hubRows.value.single().items.isEmpty() }
    }
}
