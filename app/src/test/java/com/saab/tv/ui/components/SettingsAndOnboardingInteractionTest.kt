package com.saab.tv.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.saab.tv.ui.player.base.*
import com.saab.tv.ui.settings.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class SettingsAndOnboardingInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val intervals = listOf("10 Seconds" to 10, "20 Seconds" to 20, "30 Seconds" to 30)
    @Test fun intervalOptionsDispatchExactValueAndRecomposeSelection() {
        val selected = mutableStateOf(30)
        val changes = mutableListOf<Int>()
        compose.setContent { MaterialTheme { Column {
            SettingOptionRow("Seek Interval", intervals, selected.value, { selected.value = it; changes += it })
            Text("Selected ${selected.value}")
        } } }
        compose.onNodeWithText("20 Seconds").performClick()
        compose.onNodeWithText("Selected 20").assertExists()
        compose.onNodeWithText("10 Seconds").performClick()
        compose.onNodeWithText("Selected 10").assertExists()
        compose.runOnIdle { assertEquals(listOf(20, 10), changes) }
    }
    @Test fun firstOptionBlocksUpAndLeftReturnsOnceWithoutChangingValue() {
        var backs = 0; var selections = 0; var focuses = 0
        compose.setContent { MaterialTheme {
            SettingOptionRow("Seek Interval", intervals, 30, { selections++ }, { backs++ }, true, { focuses++ })
        } }
        val first = compose.onNodeWithText("10 Seconds")
        first.performSemanticsAction(SemanticsActions.RequestFocus)
        first.assertIsFocused().performKeyInput { pressKey(Key.DirectionUp); pressKey(Key.DirectionLeft) }
        first.assertIsFocused()
        compose.runOnIdle { assertEquals(1, backs); assertEquals(0, selections); assertTrue(focuses > 0) }
    }
    @Test fun optionRemoteEnterDoesNotDoubleDispatchOnKeyRelease() {
        var calls = 0
        compose.setContent { MaterialTheme { SettingOptionRow("Delay", intervals, 30, { calls++ }) } }
        val option = compose.onNodeWithText("20 Seconds")
        option.performSemanticsAction(SemanticsActions.RequestFocus)
        option.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, calls) }
    }
    @Test fun toggleChipRemoteEnterChangesStateAndLeftDoesNotToggle() {
        val checked = mutableStateOf(false); var backs = 0
        compose.setContent { MaterialTheme { SettingToggleChip("Hide Zero Seeders", checked.value, { checked.value = it }, { backs++ }) } }
        val chip = compose.onNodeWithText("Hide Zero Seeders")
        chip.performSemanticsAction(SemanticsActions.RequestFocus)
        chip.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertTrue(checked.value) }
        chip.performKeyInput { pressKey(Key.DirectionLeft) }
        compose.runOnIdle { assertTrue(checked.value); assertEquals(1, backs) }
    }
    @Test fun segmentedOptionsSelectLanguageAndConsumeBackAtFirstItem() {
        val selected = mutableStateOf("en"); var backs = 0
        compose.setContent { MaterialTheme { VoidSegmentedControl(
            listOf("English" to "en", "Malayalam" to "ml", "Kannada" to "kn"), selected.value,
            { selected.value = it }, onBack = { backs++ }, blockUp = true) } }
        compose.onNodeWithText("Kannada").performClick()
        compose.runOnIdle { assertEquals("kn", selected.value) }
        val first = compose.onNodeWithText("English")
        first.performSemanticsAction(SemanticsActions.RequestFocus)
        first.performKeyInput { pressKey(Key.DirectionUp); pressKey(Key.DirectionLeft) }
        compose.runOnIdle { assertEquals(1, backs); assertEquals("kn", selected.value) }
    }
    @Test fun onboardingShowsContentAndBackButtonInvokesOnlyBack() {
        var backs = 0; var continues = 0
        compose.setContent { MaterialTheme { ProfileSetupLayout("Device Setup", "Is this TV 4K?", "Step 1", { backs++ }) {
            SetupButton("Continue", { continues++ })
        } } }
        compose.onNodeWithText("Device Setup").assertExists()
        compose.onNodeWithText("Is this TV 4K?").assertExists()
        compose.onNodeWithText("Back").performClick()
        compose.runOnIdle { assertEquals(1, backs); assertEquals(0, continues) }
        compose.onNodeWithText("Continue").performClick()
        compose.runOnIdle { assertEquals(1, continues) }
    }
    @Test fun playbackSurfaceForwardsModifierToBackendRatherThanDroppingIt() {
        val surface = object : PlayerRenderSurface {
            override val backendType = PlayerBackendType.EXOPLAYER
            @androidx.compose.runtime.Composable override fun Content(modifier: Modifier) { Text("Video Surface", modifier) }
        }
        compose.setContent { ComposePlayerSurface(surface, Modifier.testTag("player-output")) }
        compose.onNodeWithTag("player-output").assertTextEquals("Video Surface")
    }
}
