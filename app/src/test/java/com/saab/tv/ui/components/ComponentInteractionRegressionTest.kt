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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.saab.tv.ui.settings.SettingToggleRow
import com.saab.tv.ui.settings.SettingsSidebarItem
import com.saab.tv.R
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

    @Test fun focusSoundPlaysOnlyOnEntryAndCoalescesRapidHandoffCallbacks() {
        assertTrue(CardFocusSoundPolicy.gainedFocus(wasFocused = false, isFocused = true))
        assertFalse(CardFocusSoundPolicy.gainedFocus(wasFocused = true, isFocused = true))
        assertFalse(CardFocusSoundPolicy.gainedFocus(wasFocused = true, isFocused = false))
        assertFalse(CardFocusSoundPolicy.mayPlay(lastPlayedAtMs = 1_000L, nowMs = 1_060L))
        assertTrue(CardFocusSoundPolicy.mayPlay(lastPlayedAtMs = 1_000L, nowMs = 1_080L))
    }

    @Test fun focusHandoffCanSuppressAnchorCueWithoutSuppressingDestinationCue() {
        val suppression = FocusSoundSuppression()
        suppression.suppressNext()
        assertFalse(suppression.shouldPlay())
        assertTrue(suppression.shouldPlay())
        suppression.suppressNext()
        suppression.clear()
        assertTrue(suppression.shouldPlay())
    }

    @Test fun focusHandoffSuppressesEveryIntermediateFocusCueUntilDestinationSettles() {
        val suppression = FocusSoundSuppression()
        suppression.beginHandoff()

        assertFalse(suppression.shouldPlay()) // inline control loses focus
        assertFalse(suppression.shouldPlay()) // overlay anchor regains focus
        assertFalse(suppression.shouldPlay()) // destination focus callback

        suppression.endHandoff()
        assertTrue(suppression.shouldPlay()) // one explicitly requested destination cue
    }

    @Test fun setupButtonLabelUsesHighContrastColorForEachActionState() {
        val onPrimary = Color(0xFF112233)
        val onError = Color(0xFF445566)
        val onSurfaceVariant = Color(0xFFE2E8F0)

        assertEquals(onSurfaceVariant, setupButtonTextColor(true, false, false, onPrimary, onError, onSurfaceVariant))
        assertEquals(onPrimary, setupButtonTextColor(true, true, false, onPrimary, onError, onSurfaceVariant))
        assertEquals(onError, setupButtonTextColor(true, true, true, onPrimary, onError, onSurfaceVariant))
        assertEquals(Color.White.copy(alpha = 0.9f), setupButtonTextColor(false, false, false, onPrimary, onError, onSurfaceVariant))
    }

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
    @Test fun setupButtonFocusKeepsItsLayoutBoundsStable() {
        val focus = FocusRequester()
        compose.setContent {
            MaterialTheme { SetupButton("Continue", {}, focusRequester = focus) }
        }
        val button = compose.onNodeWithText("Continue")
        val initialBounds = button.fetchSemanticsNode().boundsInRoot

        compose.runOnIdle { focus.requestFocus() }
        button.assertIsFocused()
        assertEquals(initialBounds, button.fetchSemanticsNode().boundsInRoot)
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
    @Test fun compactDetailActionKeepsBoundsWhenFocused() {
        val action = compose.onNodeWithContentDescription("Play")
        compose.setContent { MaterialTheme { DetailActionButton("Play", Icons.Default.PlayArrow, {}, compact = true) } }
        val initialBounds = action.fetchSemanticsNode().boundsInRoot
        action.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        action.assertIsFocused()
        assertEquals(initialBounds, action.fetchSemanticsNode().boundsInRoot)
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
    @Test fun settingsSidebarFocusKeepsItsLayoutBoundsStable() {
        val focus = FocusRequester()
        compose.setContent {
            MaterialTheme {
                SettingsSidebarItem(
                    label = "Playback",
                    iconRes = R.drawable.playback_icon,
                    isSelected = false,
                    modifier = Modifier.focusRequester(focus),
                    onClick = {},
                    onFocus = {}
                )
            }
        }
        val item = compose.onNodeWithText("Playback")
        val initialBounds = item.fetchSemanticsNode().boundsInRoot

        compose.runOnIdle { focus.requestFocus() }
        item.assertIsFocused()
        assertEquals(initialBounds, item.fetchSemanticsNode().boundsInRoot)
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
