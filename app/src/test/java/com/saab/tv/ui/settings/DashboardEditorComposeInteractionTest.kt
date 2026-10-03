package com.saab.tv.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.testing.OfflineAppFixture
import com.saab.tv.testing.awaitAppState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class DashboardEditorComposeInteractionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: OfflineAppFixture
    private lateinit var vm: DashboardViewModel
    private var backCalls = 0

    @Before fun setUp() {
        app = OfflineAppFixture(RuntimeEnvironment.getApplication())
        vm = DashboardViewModel(app.dao, app.configuration)
        compose.setContent {
            MaterialTheme {
                DashboardEditorScreen(
                    onBack = { backCalls++ },
                    currentProfile = ProfileEntity(id = 77, name = "Editor"),
                    viewModel = vm
                )
            }
        }
    }

    @After fun tearDown() {
        vm.viewModelScope.cancel()
        app.close()
    }

    @Test fun emptyDashboardOffersAddFlowAndHomeTabBackAction() {
        compose.onNodeWithText("Home Screen Editor").assertExists()
        compose.onNodeWithText("No rows on Home").assertExists()
        compose.onNodeWithText("Home", substring = false)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionLeft) }
        compose.runOnIdle { assertEquals(1, backCalls) }
    }

    @Test fun addHiddenCategoryToHomeMakesItVisibleInTheEditor() {
        val config = catalogConfig("add-me")
        runBlocking { app.dao.saveCatalogConfig(config) }
        awaitAppState { vm.configs.value.any { it.uniqueId == config.uniqueId } }

        compose.onNodeWithText("Add", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Add To Home").assertExists()
        compose.onNodeWithText("Popular - Movie").performClick()

        awaitAppState { runBlocking { app.dao.getCatalogConfig(config.uniqueId)?.showInHome == true } }
        awaitAppState { vm.configs.value.first { it.uniqueId == config.uniqueId }.showInHome }
        compose.onNodeWithText("Popular - Movie").assertExists()
    }

    @Test fun categoryCanBeRenamedConfiguredAndHiddenFromItsTab() {
        val config = catalogConfig("managed", showInHome = true)
        runBlocking { app.dao.saveCatalogConfig(config) }
        awaitAppState { vm.configs.value.any { it.uniqueId == config.uniqueId } }

        compose.onNodeWithText("Popular - Movie").performClick()
        compose.onNodeWithText("Manage Category").assertExists()
        compose.onNodeWithText("Rename").performClick()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("My Picks")
        compose.onNodeWithText("Save").performClick()
        awaitAppState { vm.configs.value.firstOrNull { it.uniqueId == config.uniqueId }?.customTitle == "My Picks" }

        compose.onNodeWithText("My Picks").performClick()
        compose.onNodeWithText("Layout Settings").performClick()
        compose.onNodeWithText("Enable Grid View").performClick()
        compose.onNodeWithText("Enable Infinite Looping").assertExists()
        compose.onNodeWithText("Save").performClick()
        awaitAppState { vm.configs.value.firstOrNull { it.uniqueId == config.uniqueId }?.isInfiniteLoopEnabled == true }

        compose.onNodeWithText("My Picks").performClick()
        compose.onNodeWithText("Hide from Home").performClick()
        awaitAppState { vm.configs.value.firstOrNull { it.uniqueId == config.uniqueId }?.showInHome == false }
        compose.onNodeWithText("No rows on Home").assertExists()
    }

    @Test fun newHubRequiresNameAndCategoryThenPersistsSelectedShapeAndItem() {
        val config = catalogConfig("hub-source")
        runBlocking { app.dao.saveCatalogConfig(config) }
        awaitAppState { vm.configs.value.any { it.uniqueId == config.uniqueId } }

        compose.onNodeWithText("New Hub", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Create New Hub Row").assertExists()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("Weekend Picks")
        compose.onNodeWithText("Add Category").performClick()
        compose.onNodeWithText("Popular - Movie").performClick()
        compose.onNodeWithText("Confirm").performClick()
        compose.onNodeWithText("Categories (1)").assertExists()
        compose.onNodeWithText("Save").performClick()

        awaitAppState { vm.hubRows.value.any { it.hub.title == "Weekend Picks" } }
        val savedHub = vm.hubRows.value.first { it.hub.title == "Weekend Picks" }
        assertEquals("HORIZONTAL", savedHub.hub.shape)
        assertEquals(listOf(config.uniqueId), savedHub.items.map { it.configUniqueId })
    }

    private fun catalogConfig(id: String, showInHome: Boolean = false) =
        com.saab.tv.data.model.CatalogConfigEntity(
            uniqueId = id,
            transportUrl = "https://fixture.invalid/manifest.json",
            addonName = "Fixture",
            catalogType = "movie",
            catalogId = "popular",
            catalogName = "Popular",
            showInHome = showInHome,
            homeOrder = if (showInHome) 0 else 999
        )
}
