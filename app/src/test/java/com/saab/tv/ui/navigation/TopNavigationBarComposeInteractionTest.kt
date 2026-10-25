package com.saab.tv.ui.navigation

import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.saab.tv.data.model.ProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalTestApi::class)
class TopNavigationBarComposeInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun upFromSettingsBoundaryOpensItsCollapsedMenuAndDpadCanReturn() {
        val requesters = NavDestination.entries.associateWith { FocusRequester() }
        val contentRequester = FocusRequester()
        compose.setContent {
            MaterialTheme {
                TopNavigationBar(
                    currentDestination = NavDestination.Settings, currentProfile = null,
                    topNavRequesters = requesters, onNavigate = {},
                    onEnterContent = { contentRequester.requestFocus() }
                ) {
                    val handoff = LocalNavigationFocusHandoff.current
                    Text("Settings boundary", Modifier.focusRequester(contentRequester)
                        .onPreviewKeyEvent { handoffNavigationFocus(it, handoff) }.focusable())
                }
            }
        }
        compose.runOnIdle { contentRequester.requestFocus() }
        compose.onNodeWithText("Settings boundary").performKeyInput { pressKey(Key.DirectionUp) }
        val settings = compose.onNodeWithContentDescription("Settings")
        settings.assertIsFocused()
        compose.onNodeWithTag("top-nav-profile-avatar").assertIsNotFocused()
        settings.performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("Exit").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Settings boundary").assertIsFocused()
    }

    @Test fun centerItemNavigatesAndSelectedItemEntersContent() {
        val requesters = NavDestination.entries.associateWith { FocusRequester() }
        val contentRequester = FocusRequester()
        val navigations = mutableListOf<NavDestination>()
        var enterContentCalls = 0
        compose.setContent {
            MaterialTheme {
                TopNavigationBar(
                    currentDestination = NavDestination.Home,
                    currentProfile = ProfileEntity(id = 1, name = "Viewer"),
                    topNavRequesters = requesters,
                    onNavigate = navigations::add,
                    onEnterContent = { enterContentCalls++ },
                    content = { Text("Screen content", Modifier.focusRequester(contentRequester).focusable()) }
                )
            }
        }

        compose.runOnIdle { contentRequester.requestFocus() }
        compose.onNodeWithText("Screen content").performKeyInput { pressKey(Key.DirectionUp) }
        compose.runOnIdle { requesters.getValue(NavDestination.Search).requestFocus() }
        compose.onNodeWithContentDescription("Search").assertExists()
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf(NavDestination.Search), navigations)

        compose.onNodeWithContentDescription("Home").performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, enterContentCalls)
    }

    @Test fun remoteCanTraverseAndSelectEveryTopBarDestination() {
        val requesters = NavDestination.entries.associateWith { FocusRequester() }
        val contentRequester = FocusRequester()
        val navigations = mutableListOf<NavDestination>()
        var contentEntries = 0
        compose.setContent {
            MaterialTheme {
                TopNavigationBar(
                    currentDestination = NavDestination.Home,
                    currentProfile = null,
                    topNavRequesters = requesters,
                    onNavigate = navigations::add,
                    onEnterContent = { contentEntries++ },
                    content = { Text("Screen content", Modifier.focusRequester(contentRequester).focusable()) }
                )
            }
        }

        compose.runOnIdle { contentRequester.requestFocus() }
        compose.onNodeWithText("Screen content").performKeyInput { pressKey(Key.DirectionUp) }
        compose.runOnIdle { requesters.getValue(NavDestination.Search).requestFocus() }

        val labels = mapOf(
            NavDestination.Search to "Search",
            NavDestination.Home to "Home",
            NavDestination.Movies to "Movies",
            NavDestination.Series to "Series",
            NavDestination.Ott to "OTT",
            NavDestination.Watchlist to "My Library"
        )
        NavigationDestinations.topBar.forEachIndexed { index, destination ->
            val item = compose.onNodeWithContentDescription(labels.getValue(destination))
            item.assertIsFocused()
            item.performKeyInput { pressKey(Key.DirectionCenter) }
            if (destination == NavDestination.Home) {
                compose.runOnIdle { assertEquals(1, contentEntries) }
            } else {
                compose.runOnIdle {
                    assertEquals(NavigationDestinations.topBar.take(index + 1).filter { it != NavDestination.Home }, navigations)
                }
            }
            if (index < NavigationDestinations.topBar.lastIndex) {
                item.performKeyInput { pressKey(Key.DirectionRight) }
            }
        }
    }

    @Test fun downOnCenterNavigationEntersContentWithoutChangingTab() {
        val requesters = NavDestination.entries.associateWith { FocusRequester() }
        val contentRequester = FocusRequester()
        val navigations = mutableListOf<NavDestination>()
        var enterContentCalls = 0
        compose.setContent {
            MaterialTheme {
                TopNavigationBar(
                    currentDestination = NavDestination.Movies,
                    currentProfile = null,
                    topNavRequesters = requesters,
                    onNavigate = navigations::add,
                    onEnterContent = { enterContentCalls++ },
                    content = { Text("Screen content", Modifier.focusRequester(contentRequester).focusable()) }
                )
            }
        }

        compose.runOnIdle { contentRequester.requestFocus() }
        compose.onNodeWithText("Screen content").performKeyInput { pressKey(Key.DirectionUp) }
        compose.runOnIdle { requesters.getValue(NavDestination.Movies).requestFocus() }
        compose.onNodeWithContentDescription("Movies").performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionDown) }

        assertEquals(1, enterContentCalls)
        assertEquals(emptyList<NavDestination>(), navigations)
    }

    @Test fun backAndEscapeDoNotAuthorizeRestoredFocusIntoNavigation() {
        val requesters = NavDestination.entries.associateWith { FocusRequester() }
        val contentRequester = FocusRequester()
        compose.setContent {
            MaterialTheme {
                TopNavigationBar(
                    currentDestination = NavDestination.Home,
                    currentProfile = null,
                    topNavRequesters = requesters,
                    onNavigate = {},
                    onEnterContent = { contentRequester.requestFocus() },
                    content = { Text("Screen content", Modifier.focusRequester(contentRequester).focusable()) }
                )
            }
        }

        compose.runOnIdle { contentRequester.requestFocus() }
        listOf(Key.Back, Key.Escape).forEach { key ->
            compose.onNodeWithText("Screen content").performKeyInput { pressKey(key) }
            compose.runOnIdle { requesters.getValue(NavDestination.Search).requestFocus() }
            compose.waitForIdle()
            compose.onNodeWithText("Screen content").assertIsFocused()
        }
    }

    @Test fun settingsDropdownDownMovesToExitAndConfirmsExitAction() {
        val requesters = NavDestination.entries.associateWith { FocusRequester() }
        val contentRequester = FocusRequester()
        var exitCalls = 0
        compose.setContent {
            MaterialTheme {
                TopNavigationBar(
                    currentDestination = NavDestination.Home,
                    currentProfile = null,
                    topNavRequesters = requesters,
                    onNavigate = {},
                    onEnterContent = {},
                    onExit = { exitCalls++ },
                    content = { Text("Screen content", Modifier.focusRequester(contentRequester).focusable()) }
                )
            }
        }

        compose.runOnIdle { contentRequester.requestFocus() }
        compose.onNodeWithText("Screen content").performKeyInput { pressKey(Key.DirectionUp) }
        compose.runOnIdle { requesters.getValue(NavDestination.Search).requestFocus() }
        compose.runOnIdle { requesters.getValue(NavDestination.Profile).requestFocus() }
        compose.runOnIdle { requesters.getValue(NavDestination.Settings).requestFocus() }
        compose.onNodeWithContentDescription("Settings").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("Exit").performKeyInput { pressKey(Key.DirectionCenter) }

        assertEquals(1, exitCalls)
    }

    @Test fun collapsedDropdownCannotStealRestoredFocus() {
        val requesters = NavDestination.entries.associateWith { FocusRequester() }
        val contentRequester = FocusRequester()
        compose.setContent {
            MaterialTheme {
                TopNavigationBar(
                    currentDestination = NavDestination.Home,
                    currentProfile = null,
                    topNavRequesters = requesters,
                    onNavigate = {},
                    onEnterContent = {},
                    content = { Text("Screen content", Modifier.focusRequester(contentRequester).focusable()) }
                )
            }
        }

        compose.runOnIdle { contentRequester.requestFocus() }
        compose.runOnIdle { requesters.getValue(NavDestination.Exit).requestFocus() }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Exit").assertIsNotFocused()
        compose.onNodeWithText("Screen content").assertIsFocused()
    }

    @Test fun blockedNavigationDoesNotReopenFromStaleTopBarFocus() {
        val requesters = NavDestination.entries.associateWith { FocusRequester() }
        val contentRequester = FocusRequester()
        val navigationActive = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                TopNavigationBar(
                    currentDestination = NavDestination.Home,
                    currentProfile = null,
                    topNavRequesters = requesters,
                    onNavigate = {},
                    onEnterContent = { contentRequester.requestFocus() },
                    navigationActive = navigationActive.value,
                    content = { Text("Screen content", Modifier.focusRequester(contentRequester).focusable()) }
                )
            }
        }

        compose.runOnIdle { contentRequester.requestFocus() }
        compose.onNodeWithText("Screen content").performKeyInput { pressKey(Key.DirectionUp) }
        compose.runOnIdle { requesters.getValue(NavDestination.Home).requestFocus() }
        compose.onNodeWithContentDescription("Home").assertIsFocused()

        compose.runOnIdle { navigationActive.value = false }
        compose.waitForIdle()
        compose.runOnIdle { navigationActive.value = true }
        compose.waitForIdle()
        compose.runOnIdle { requesters.getValue(NavDestination.Home).requestFocus() }
        compose.waitForIdle()

        compose.onNodeWithText("Screen content").assertIsFocused()
    }
}
