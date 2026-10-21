package com.saab.tv.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import com.saab.tv.R
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
        vm = DashboardViewModel(app.dao, app.configuration, app.userStateCommands)
    }

    @After fun tearDown() {
        vm.viewModelScope.cancel()
        compose.waitForIdle()
        app.close()
    }

    @Test fun heroSlidersExposeLocalizedIncreaseAndDisabledDecreaseActions() {
        var value = 5f
        val label = "5 posters"
        compose.setContent {
            MaterialTheme {
                VoidSlider(
                    value = value,
                    accessibilityLabel = label,
                    onValueChange = { value = it },
                    valueRange = 5f..20f,
                    steps = 14
                )
            }
        }

        val context = RuntimeEnvironment.getApplication()
        val decrease = context.getString(R.string.player_decrease_control, label)
        val increase = context.getString(R.string.player_increase_control, label)
        compose.onNodeWithContentDescription(decrease).assertIsNotEnabled()
        compose.onNodeWithContentDescription(increase).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(6f, value, 0f) }
    }

    @Test fun defaultTmdbCatalogsRemainVisibleAndHomeTabBackActionWorks() {
        renderEditor()
        compose.onNodeWithText("Home Screen Editor").assertExists()
        compose.onNodeWithText("TMDB · Top Suggestions").assertExists()
        compose.onNodeWithText("TMDB · More in Your Favorite Genres").assertExists()
        compose.onNodeWithText("TMDB · New for You").assertExists()
        compose.onNodeWithText("TMDB · Popular in Your Taste").assertExists()
        val homeTab = compose.onNodeWithText("Home", substring = false)
        val initialBounds = homeTab.fetchSemanticsNode().boundsInRoot
        homeTab.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        homeTab.assertIsFocused()
        assertEquals("Dashboard tabs must not resize when focused", initialBounds, homeTab.fetchSemanticsNode().boundsInRoot)
        homeTab.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionLeft) }
        compose.runOnIdle { assertEquals(1, backCalls) }
    }

    @Test fun dashboardCompactActionAndTabKeepTheirBoundsWhenFocused() {
        compose.setContent {
            MaterialTheme {
                Row {
                    VoidCompactActionButton("New Hub", {}, Modifier.testTag("dashboard-compact-action"))
                    VoidTabBtn(
                        label = "Home",
                        isSelected = false,
                        isTabRowFocused = false,
                        onClick = {},
                        modifier = Modifier.testTag("dashboard-tab")
                    )
                }
            }
        }

        val action = compose.onNodeWithTag("dashboard-compact-action")
        val actionBounds = action.fetchSemanticsNode().boundsInRoot
        action.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        action.assertIsFocused()
        assertEquals("Compact action focus must not resize the control", actionBounds, action.fetchSemanticsNode().boundsInRoot)

        val tab = compose.onNodeWithTag("dashboard-tab")
        val tabBounds = tab.fetchSemanticsNode().boundsInRoot
        tab.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        tab.assertIsFocused()
        assertEquals("Dashboard tab focus must not resize the control", tabBounds, tab.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun addHiddenCategoryToHomeMakesItVisibleInTheEditor() {
        renderEditor()
        val config = catalogConfig("add-me")
        runBlocking { app.dao.saveCatalogConfig(config) }
        awaitAppState { vm.configs.value.any { it.uniqueId == config.uniqueId } }

        compose.onNodeWithText("Add", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Add to Home").assertExists()
        compose.onNodeWithText("Popular - Movie").performClick()

        awaitAppState { runBlocking { app.dao.getCatalogConfig(config.uniqueId)?.showInHome == true } }
        awaitAppState { vm.configs.value.first { it.uniqueId == config.uniqueId }.showInHome }
        compose.onNodeWithText("Popular - Movie").assertExists()
    }

    @Test fun categoryCanBeRenamedConfiguredAndHiddenFromItsTab() {
        renderEditor()
        val config = catalogConfig("managed", showInHome = true)
        runBlocking { app.dao.saveCatalogConfig(config) }
        awaitAppState { vm.configs.value.any { it.uniqueId == config.uniqueId } }

        val category = compose.onNodeWithText("Popular - Movie")
        val initialBounds = category.fetchSemanticsNode().boundsInRoot
        category.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        category.assertIsFocused()
        assertEquals("Dashboard rows must not resize when focused", initialBounds, category.fetchSemanticsNode().boundsInRoot)
        category.performClick()
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
        compose.onNodeWithText("TMDB · Top Suggestions").assertExists()
        compose.onNodeWithText("Popular - Movie").assertDoesNotExist()
    }

    @Test fun heroCarouselDialogShowsActionsAndPersistsConfiguration() {
        val config = catalogConfig("hero-source", showInHome = true)
        val profile = ProfileEntity(
            name = "Hero editor",
            homeTabLayout = "simple",
            homeHeroCategory = config.uniqueId
        )
        val profileId = runBlocking {
            val id = app.dao.insertProfile(profile).toInt()
            app.dao.saveCatalogConfig(config)
            id
        }
        awaitAppState { vm.configs.value.any { it.uniqueId == config.uniqueId } }
        val storedProfile = runBlocking { app.dao.getProfileById(profileId)!! }
        renderEditor(storedProfile)

        compose.onNodeWithText("10 posters").performClick()
        compose.onNodeWithText("Hero Carousel").assertExists()
        compose.onNodeWithText("Disable").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Disable").performClick()
        compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        compose.onNodeWithText("Save").assertIsDisplayed().performClick()

        awaitAppState {
            runBlocking {
                val updated = app.dao.getProfileById(storedProfile.id)
                updated != null && updated.homeHeroCategory == config.uniqueId &&
                    updated.homeHeroPosterCount == storedProfile.homeHeroPosterCount &&
                    updated.homeHeroAutoScrollSeconds == storedProfile.homeHeroAutoScrollSeconds
            }
        }
    }

    @Test fun newHubRequiresNameAndCategoryThenPersistsSelectedShapeAndItem() {
        renderEditor()
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
        compose.onNodeWithText("Shape").assertExists()
        compose.onNodeWithText("Landscape").assertExists()
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

    private fun renderEditor(profile: ProfileEntity = ProfileEntity(id = 77, name = "Editor")) {
        compose.setContent {
            MaterialTheme {
                DashboardEditorScreen(
                    onBack = { backCalls++ },
                    currentProfile = profile,
                    viewModel = vm
                )
            }
        }
    }
}
