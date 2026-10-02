package com.saab.tv.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.saab.tv.ui.settings.SettingToggleRow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Compose semantics and key dispatch; no network, Hilt graph or media decoder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class ComponentInteractionRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun setupButtonInvokesActionOnce() {
        var calls = 0
        compose.setContent { MaterialTheme { SetupButton("Sign In", { calls++ }, primary = true) } }
        compose.onNodeWithText("Sign In").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }
    @Test fun disabledSetupButtonDoesNotInvokeAction() {
        var calls = 0
        compose.setContent { MaterialTheme { SetupButton("Create Account", { calls++ }, enabled = false) } }
        compose.onNodeWithText("Create Account").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, calls) }
    }
    @Test fun compactDestructiveButtonIsFocusableAndRemoteEnterActivatesIt() {
        var calls = 0
        val focus = FocusRequester()
        compose.setContent { MaterialTheme { SetupButton("Sign Out", { calls++ }, destructive = true, compact = true, focusRequester = focus) } }
        compose.runOnIdle { focus.requestFocus() }
        compose.onNodeWithText("Sign Out").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, calls) }
    }
    @Test fun setupHeaderShowsOnlyNonBlankStage() {
        compose.setContent { MaterialTheme { Column { SetupHeader("Welcome", "Choose your preferences", "Step 1") } } }
        compose.onNodeWithText("Welcome").assertExists(); compose.onNodeWithText("Choose your preferences").assertExists()
        compose.onNodeWithText("Step 1").assertExists()
    }
    @Test fun setupHeaderWithoutStageStillShowsTitleAndDescription() {
        compose.setContent { MaterialTheme { Column { SetupHeader("Welcome", "Description", "") } } }
        compose.onNodeWithText("Welcome").assertExists(); compose.onNodeWithText("Description").assertExists()
    }
    @Test fun detailActionIconRemainsClickableWithoutExpandedLabel() {
        var calls = 0
        compose.setContent { MaterialTheme { DetailActionButton("Start Watching", Icons.Default.PlayArrow, { calls++ }, compact = true) } }
        compose.onNodeWithContentDescription("Start Watching").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }
    @Test fun detailActionFocusExpandsLabelAndRemoteEnterInvokesAction() {
        val focus = FocusRequester()
        var calls = 0
        compose.setContent { MaterialTheme { DetailActionButton("Resume", Icons.Default.PlayArrow, { calls++ },
            modifier = Modifier.focusRequester(focus), isActive = true) } }
        compose.runOnIdle { focus.requestFocus() }
        compose.onNodeWithText("Resume").assertExists()
        compose.onNodeWithContentDescription("Resume").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, calls) }
    }
    @Test fun toggleChangesStateAndKeepsHelperTextVisible() {
        val checked = mutableStateOf(false)
        compose.setContent { MaterialTheme { SettingToggleRow("Thumbnails", "Generate seek previews", checked.value, { checked.value = it }) } }
        compose.onNodeWithText("Thumbnails").performClick()
        compose.runOnIdle { assertTrue(checked.value) }
        compose.onNodeWithText("Generate seek previews").assertExists()
        compose.onNodeWithText("Thumbnails").performClick()
        compose.runOnIdle { assertFalse(checked.value) }
    }
    @Test fun toggleWithoutHelperTextCanReceiveFocusAndReturnWithLeftKey() {
        val focus = FocusRequester()
        var backCalls = 0
        var focusCalls = 0
        compose.setContent { MaterialTheme { SettingToggleRow("Autoplay", isChecked = true, onCheckedChange = {},
            onBack = { backCalls++ }, blockUp = true, onFocus = { focusCalls++ }, modifier = Modifier.focusRequester(focus)) } }
        compose.runOnIdle { focus.requestFocus() }
        compose.onNodeWithText("Autoplay").performKeyInput { pressKey(Key.DirectionUp); pressKey(Key.DirectionLeft) }
        compose.runOnIdle { assertEquals(1, backCalls); assertTrue(focusCalls > 0) }
    }
}
