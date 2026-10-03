package com.saab.tv.ui.navigation

import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.input.key.Key
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
        compose.runOnIdle { requesters.getValue(NavDestination.Settings).requestFocus() }
        compose.onNodeWithContentDescription("Settings").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("Exit").performKeyInput { pressKey(Key.DirectionCenter) }

        assertEquals(1, exitCalls)
    }
}
