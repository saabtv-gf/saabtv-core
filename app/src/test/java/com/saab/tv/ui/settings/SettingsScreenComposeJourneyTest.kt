package com.saab.tv.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.cache.SeekThumbnailCache
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.testing.OfflineAppFixture
import com.saab.tv.ui.navigation.LocalNavigationFocusHandoff
import com.saab.tv.ui.navigation.NavigationFocusHandoffHost
import com.saab.tv.ui.navigation.NavDestination
import com.saab.tv.ui.navigation.NavDrawer
import com.saab.tv.ui.navigation.TopNavigationBar
import androidx.compose.foundation.layout.Box
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class SettingsScreenComposeJourneyTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: OfflineAppFixture
    private lateinit var viewModel: SettingsViewModel
    private lateinit var profile: ProfileEntity

    @Test fun dpadReturnsFromSettingsControlsThroughSidebarToTheRealMenu() = assertReturnToMenu(top = false)

    @Test fun backReturnsFromSettingsSidebarToTheTopMenu() = assertReturnToMenu(top = true)

    private fun assertReturnToMenu(top: Boolean) {
        val entry = FocusRequester()
        val navigation = NavDestination.entries.associateWith { FocusRequester() }
        compose.setContent {
            MaterialTheme {
                NavigationFocusHandoffHost {
                    Box {
                        SettingsScreen(
                            currentProfile = profile.copy(navPosition = if (top) "top" else "left"), onBack = {}, entryRequester = entry,
                            drawerRequester = navigation.getValue(NavDestination.Settings), viewModel = viewModel
                        )
                        if (top) TopNavigationBar(
                            currentDestination = NavDestination.Settings, currentProfile = profile,
                            topNavRequesters = navigation, onNavigate = {}, onEnterContent = { entry.requestFocus() }, content = {}
                        ) else NavDrawer(
                            currentDestination = NavDestination.Settings, currentProfile = profile,
                            drawerRequesters = navigation, onNavigate = {}, onClose = { entry.requestFocus() }, content = {}
                        )
                    }
                }
            }
        }
        val section = compose.onNode(hasText("Personalization") and hasClickAction())
        section.performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionRight) }
        val control = compose.onNodeWithTag("personalization-reduce-motion")
        compose.waitUntil(3_000) { runCatching { control.assertIsFocused() }.isSuccess }
        control.performKeyInput { pressKey(Key.DirectionLeft) }
        section.assertIsFocused()
        if (top) {
            section.performKeyInput { pressKey(Key.Back) }
        } else section.performKeyInput { pressKey(Key.DirectionLeft) }
        val settings = compose.onNode(hasContentDescription("Settings") and hasClickAction())
        settings.assertIsFocused()
        compose.onNodeWithTag(if (top) "top-nav-profile-avatar" else "nav-profile-avatar").assertIsNotFocused()
        if (!top) {
            settings.performKeyInput { pressKey(Key.DirectionUp) }
            compose.onNode(hasText("My Library") and hasClickAction()).assertIsFocused()
        }
    }

    @Before fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        app = OfflineAppFixture(context)
        profile = ProfileEntity(id = 41, name = "Settings Journey")
        app.dao.insertProfile(profile)
        viewModel = SettingsViewModel(app.dao, app.configuration, SeekThumbnailCache(context), context, app.display, app.userStateCommands)
    }

    @After fun tearDown() {
        viewModel.viewModelScope.cancel()
        app.close()
    }

    @Test fun settingsNavigationOpensPlaybackAndPersistsToggleInteraction() {
        val entryRequester = FocusRequester()
        val drawerRequester = FocusRequester()
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    currentProfile = profile,
                    onBack = {},
                    entryRequester = entryRequester,
                    drawerRequester = drawerRequester,
                    viewModel = viewModel
                )
            }
        }

        compose.onNodeWithText("Playback").performClick()
        compose.onNodeWithText("Configure video decoding and display settings.").assertExists()
        compose.onNodeWithText("Frame Rate Matching").performScrollTo().performClick()

        compose.waitUntil(5_000) {
            runBlocking { app.dao.getProfileById(profile.id)?.frameRateMatching == true }
        }
        compose.runOnIdle { assertTrue(runBlocking { app.dao.getProfileById(profile.id)!!.frameRateMatching }) }
    }

    @Test fun playbackLanguagePriorityAndSubtitleRowsKeepVisibleSpacing() {
        val entryRequester = FocusRequester()
        val drawerRequester = FocusRequester()
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    currentProfile = profile,
                    onBack = {},
                    entryRequester = entryRequester,
                    drawerRequester = drawerRequester,
                    viewModel = viewModel
                )
            }
        }

        val playbackSection = compose.onNodeWithText("Playback")
        playbackSection.performSemanticsAction(SemanticsActions.RequestFocus)
        playbackSection.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(2_000) {
            compose.onAllNodesWithText("Configure video decoding and display settings.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Languages", substring = true).performScrollTo().performClick()

        val thirdPriority = compose.onNodeWithTag("language-priority-3").performScrollTo()
        val subtitle = compose.onNodeWithTag("subtitle-language-primary").performScrollTo()
        val gapPx = subtitle.fetchSemanticsNode().boundsInRoot.top -
            thirdPriority.fetchSemanticsNode().boundsInRoot.bottom
        val minimumGapPx = with(compose.density) { 16.dp.toPx() }
        assertTrue("Playback rows should remain separated, gap=$gapPx px", gapPx >= minimumGapPx)
    }

    @Test fun settingsNavigationOpensSortAndFilterWithoutLeavingTheSection() {
        val entryRequester = FocusRequester()
        val drawerRequester = FocusRequester()
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    currentProfile = profile,
                    onBack = {},
                    entryRequester = entryRequester,
                    drawerRequester = drawerRequester,
                    viewModel = viewModel
                )
            }
        }

        compose.onNodeWithText("Sort & Filter").performClick()
        compose.onNodeWithText("Configure how sources are sorted and filtered.").assertExists()
        compose.onNodeWithText("Sort Sources").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { app.dao.getProfileById(profile.id)?.sourceSortingEnabled == false } }
        assertEquals(false, runBlocking { app.dao.getProfileById(profile.id)?.sourceSortingEnabled })
    }

    @Test fun dpadCenterEntersSelectedSettingsSectionAndMovesFocusIntoContent() {
        val entryRequester = FocusRequester()
        val drawerRequester = FocusRequester()
        var contentHasFocus = false
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    currentProfile = profile,
                    onBack = {},
                    entryRequester = entryRequester,
                    drawerRequester = drawerRequester,
                    onContentFocusChanged = { contentHasFocus = it },
                    viewModel = viewModel
                )
            }
        }

        val playbackSection = compose.onNodeWithText("Playback")
        playbackSection.performSemanticsAction(SemanticsActions.RequestFocus)
        playbackSection.performKeyInput { pressKey(Key.DirectionCenter) }

        compose.waitUntil(2_000) { contentHasFocus }
        compose.onNodeWithText("Configure video decoding and display settings.").assertExists()
    }

    @Test fun dpadRightEntersPlaybackAndFocusedControlCanBeChanged() {
        val entryRequester = FocusRequester()
        val drawerRequester = FocusRequester()
        var contentHasFocus = false
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    currentProfile = profile,
                    onBack = {},
                    entryRequester = entryRequester,
                    drawerRequester = drawerRequester,
                    onContentFocusChanged = { contentHasFocus = it },
                    viewModel = viewModel
                )
            }
        }

        val playback = compose.onAllNodesWithText("Playback")
            .filter(hasClickAction())
            .onFirst()
        playback.performSemanticsAction(SemanticsActions.RequestFocus)
        playback.performKeyInput { pressKey(Key.DirectionRight) }

        compose.waitUntil(2_000) { contentHasFocus }
        compose.onNodeWithText("Configure video decoding and display settings.").assertExists()

        val frameRateMatching = compose.onNodeWithText("Frame Rate Matching").performScrollTo()
        frameRateMatching.performSemanticsAction(SemanticsActions.RequestFocus)
        frameRateMatching.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(5_000) {
            runBlocking { app.dao.getProfileById(profile.id)?.frameRateMatching == true }
        }
        assertTrue(runBlocking { app.dao.getProfileById(profile.id)!!.frameRateMatching })
    }

    @Test fun dpadCanEnterPlaybackAccordionAndReachItsFirstSetting() {
        val entryRequester = FocusRequester()
        val drawerRequester = FocusRequester()
        viewModel.deviceDisplay.configure(false)
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    currentProfile = profile,
                    onBack = {},
                    entryRequester = entryRequester,
                    drawerRequester = drawerRequester,
                    viewModel = viewModel
                )
            }
        }

        val playback = compose.onAllNodesWithText("Playback")
            .filter(hasClickAction())
            .onFirst()
        playback.performSemanticsAction(SemanticsActions.RequestFocus)
        playback.performKeyInput { pressKey(Key.DirectionRight) }

        compose.waitUntil(2_000) {
            compose.onAllNodesWithText("Configure video decoding and display settings.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        val displaySection = compose.onNode(hasText("Display & Player", substring = true) and hasClickAction())
        displaySection.assertIsFocused()
        // The section is expanded by default; Down enters its first setting.
        displaySection.performKeyInput { pressKey(Key.DirectionDown) }

        val firstSetting = compose.onNodeWithText("This Device Has A 4K Display")
        firstSetting.assertIsFocused()
        assertEquals(false, viewModel.deviceDisplay.is4k())
        firstSetting.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(2_000) { viewModel.deviceDisplay.is4k() }
        assertTrue(viewModel.deviceDisplay.is4k())
    }

    @Test fun settingsCanReturnToTheCallerWhenNavigationCannotTakeFocus() {
        val entryRequester = FocusRequester()
        val drawerRequester = FocusRequester()
        var backFallbackCalled = false
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    currentProfile = profile,
                    onBack = { backFallbackCalled = true },
                    entryRequester = entryRequester,
                    drawerRequester = drawerRequester,
                    viewModel = viewModel
                )
            }
        }

        val personalization = compose.onAllNodesWithText("Personalization")
            .filter(hasClickAction())
            .onFirst()
        personalization.performSemanticsAction(SemanticsActions.RequestFocus)
        personalization.performKeyInput { pressKey(Key.Back) }

        compose.runOnIdle { assertTrue(backFallbackCalled) }
    }

    @Test fun settingsSectionTransitionLockAllowsRepeatedSectionChanges() {
        val entryRequester = FocusRequester()
        val drawerRequester = FocusRequester()
        var contentHasFocus = false
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    currentProfile = profile,
                    onBack = {},
                    entryRequester = entryRequester,
                    drawerRequester = drawerRequester,
                    onContentFocusChanged = { contentHasFocus = it },
                    viewModel = viewModel
                )
            }
        }

        val playback = compose.onAllNodesWithText("Playback").filter(hasClickAction()).onFirst()
        playback.performSemanticsAction(SemanticsActions.RequestFocus)
        playback.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(2_000) {
            contentHasFocus && compose.onAllNodesWithText("Configure video decoding and display settings.")
                .fetchSemanticsNodes().isNotEmpty()
        }

        val personalization = compose.onAllNodesWithText("Personalization")
            .filter(hasClickAction())
            .onFirst()
        repeat(3) {
            personalization.performClick()
            compose.waitUntil(2_000) {
                compose.onAllNodesWithText("Customize your viewing experience.")
                    .fetchSemanticsNodes().isNotEmpty()
            }

            playback.performClick()
            compose.waitUntil(2_000) {
                compose.onAllNodesWithText("Configure video decoding and display settings.")
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    @Test fun topNavigationSettingsDoesNotRequestMenuFocusFromTheLeftEdge() {
        val entryRequester = FocusRequester()
        val drawerRequester = FocusRequester()
        var handoffAttempts = 0
        compose.setContent {
            CompositionLocalProvider(
                LocalNavigationFocusHandoff provides { _: androidx.compose.ui.input.key.KeyEvent? ->
                    handoffAttempts++
                    true
                }
            ) {
                MaterialTheme {
                    SettingsScreen(
                        currentProfile = profile.copy(navPosition = "top"),
                        onBack = {},
                        entryRequester = entryRequester,
                        drawerRequester = drawerRequester,
                        viewModel = viewModel
                    )
                }
            }
        }

        compose.onNodeWithText("Playback").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("Playback").performKeyInput { pressKey(Key.DirectionLeft) }

        compose.runOnIdle { assertEquals(0, handoffAttempts) }
    }

    @Test fun backFromSettingsSidebarRequestsExplicitMenuFocusHandoff() {
        val entryRequester = FocusRequester()
        val drawerRequester = FocusRequester()
        var requestedBackHandoff = false
        compose.setContent {
            CompositionLocalProvider(
                LocalNavigationFocusHandoff provides { event ->
                    requestedBackHandoff = event == null
                    true
                }
            ) {
                MaterialTheme {
                    SettingsScreen(
                        currentProfile = profile,
                        onBack = {},
                        entryRequester = entryRequester,
                        drawerRequester = drawerRequester,
                        viewModel = viewModel
                    )
                }
            }
        }

        compose.onNodeWithText("Playback").performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.Back) }

        compose.runOnIdle { assertTrue("Back must request a deliberate drawer handoff", requestedBackHandoff) }
    }

    @Test fun enteringPersonalizationMovesFocusIntoItsSettings() {
        val entryRequester = FocusRequester()
        val drawerRequester = FocusRequester()
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    currentProfile = profile,
                    onBack = {},
                    entryRequester = entryRequester,
                    drawerRequester = drawerRequester,
                    viewModel = viewModel
                )
            }
        }

        compose.onAllNodesWithText("Personalization")
            .filter(hasClickAction())
            .onFirst()
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }

        val reduceMotion = compose.onNodeWithTag("personalization-reduce-motion")
        compose.waitUntil(5_000) {
            runCatching { reduceMotion.assertIsFocused() }.isSuccess
        }
        reduceMotion.assertIsFocused()
    }
}
