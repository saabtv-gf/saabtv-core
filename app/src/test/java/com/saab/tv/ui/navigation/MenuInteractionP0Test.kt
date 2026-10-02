package com.saab.tv.ui.navigation

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.saab.tv.ui.components.SetupButton
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class MenuInteractionP0Test {
    @get:Rule val compose=createComposeRule()
    private val requesters=NavDestination.entries.associateWith { FocusRequester() }
    private val content=FocusRequester()
    private val selected=mutableStateOf(NavDestination.Home)
    private val actions=mutableListOf<NavDestination>()
    private var returns=0
    private fun show(hidden:Boolean=false) {
        compose.setContent { MaterialTheme { NavDrawer(selected.value,null,requesters,
            onNavigate={actions+=it;selected.value=it;content.requestFocus()},onClose={returns++;content.requestFocus()},hideNavigation=hidden) {
            Box(Modifier.padding(start=250.dp,top=50.dp)) {
                SetupButton("Content",{},focusRequester=content,modifier=Modifier.focusProperties {left=requesters.getValue(NavDestination.Home)})
            }
        } } }
        compose.runOnIdle { content.requestFocus() }
    }
    private fun enter() { compose.onNodeWithText("Content").performKeyInput {pressKey(Key.DirectionLeft)} }
    @Test fun leftEntersDrawerAndDownEnterSelectsMovies() {
        show();enter()
        compose.onNodeWithText("Home").performKeyInput {pressKey(Key.DirectionDown)}
        compose.onNodeWithText("Movies").performKeyInput {pressKey(Key.DirectionCenter)}
        compose.runOnIdle {assertEquals(listOf(NavDestination.Movies),actions)}
        compose.onNodeWithText("Content").assertIsFocused()
    }
    @Test fun repeatedUpDownRemainsNavigableAndEnterSelectsExpectedTab() {
        show();enter()
        compose.onNodeWithText("Home").performKeyInput {pressKey(Key.DirectionDown)}
        compose.onNodeWithText("Movies").performKeyInput {pressKey(Key.DirectionDown)}
        compose.onNodeWithText("Series").performKeyInput {pressKey(Key.DirectionUp)}
        compose.onNodeWithText("Movies").performKeyInput {pressKey(Key.DirectionCenter)}
        compose.runOnIdle {assertEquals(NavDestination.Movies,actions.single())}
    }
    @Test fun automaticMenuFocusIsReturnedToContentWithoutNavigationAction() {
        show();compose.runOnIdle {requesters.getValue(NavDestination.Settings).requestFocus()}
        compose.waitForIdle()
        compose.onNodeWithText("Content").assertIsFocused()
        compose.runOnIdle {assertTrue(actions.isEmpty());assertTrue(returns>0)}
    }
    @Test fun hiddenDrawerCannotStealFocusFromContent() {
        show(true);compose.runOnIdle {requesters.getValue(NavDestination.Home).requestFocus()}
        compose.onNodeWithText("Content").assertIsFocused()
        compose.runOnIdle {assertTrue(actions.isEmpty())}
    }
}
