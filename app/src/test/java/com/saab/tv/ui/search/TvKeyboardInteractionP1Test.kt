package com.saab.tv.ui.search

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class TvKeyboardInteractionP1Test {
    @get:Rule val compose=createComposeRule()
    private val entry=FocusRequester();private val drawer=FocusRequester();private val result=FocusRequester()
    private val entered=mutableListOf<String>();private var spaces=0;private var deletes=0;private var clears=0;private var qr=0
    private fun show(hasResults:Boolean=true) {
        compose.setContent { MaterialTheme { Column(Modifier.padding(16.dp)) {
            TvKeyboard({entered+=it},{deletes++},{spaces++},{clears++},{qr++},entry,drawer,false,hasResults,result)
            Button(onClick={},modifier=Modifier.focusRequester(result)) { Text("Search Result") }
            Button(onClick={},modifier=Modifier.focusRequester(drawer)) { Text("Main Menu") }
        } } }
        compose.runOnIdle { entry.requestFocus() }
    }
    @Test fun qwertyAndSpaceBackspaceClearAndQrButtonsDispatchExactlyOnce() {
        show()
        compose.onNodeWithText("Q").performClick();compose.onNodeWithText("Space").performClick()
        compose.onNodeWithText("Backspace").performClick();compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithText("QR Search").performClick()
        compose.runOnIdle { assertEquals(listOf("q"),entered);assertEquals(1,spaces);assertEquals(1,deletes);assertEquals(1,clears);assertEquals(1,qr) }
    }
    @Test fun topKeyboardRowMovesUpToSearchResultsWhenPresent() {
        show(true)
        compose.onNodeWithText("Q").assertIsFocused()
        compose.onNodeWithText("Q").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("1").assertIsFocused()
        compose.onNodeWithText("1").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("Search Result").assertIsFocused()
    }

    @Test fun topKeyboardRowFallsBackToMenuWhenSearchIsEmpty() {
        show(false)
        compose.onNodeWithText("Q").assertIsFocused()
        compose.onNodeWithText("Q").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("1").assertIsFocused()
        compose.onNodeWithText("1").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("Main Menu").assertIsFocused()
    }
}
