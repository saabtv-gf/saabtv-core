package com.saab.tv.ui.profiles

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.saab.tv.data.model.ProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class ProfilePinInteractionP1Test {
    @get:Rule val compose = createComposeRule()
    private var setPin: String? = null

    private fun show() {
        compose.setContent {
            MaterialTheme {
                ProfileOptionsDialog(
                    profile = ProfileEntity(id = 42, name = "Family"),
                    onDismiss = {}, onEdit = {}, onDelete = {}, onSetPin = { setPin = it },
                    onRemovePin = {}, verifyPin = { it == "1234" }
                )
            }
        }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNodeWithText("Set 4-Digit PIN").performClick()
        compose.waitUntil(2_000) {
            compose.onAllNodesWithText("Set Profile PIN").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun enter(pin: String) {
        pin.forEach { digit -> compose.onNodeWithText(digit.toString()).performClick() }
    }

    @Test fun fourDigitsMoveFocusToContinueAndMatchingConfirmationSetsPin() {
        show()
        enter("1234")
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        compose.onNodeWithText("Continue").assertIsFocused()
        compose.onNodeWithText("Continue").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("Confirm Profile PIN").assertExists()
        enter("1234")
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        compose.onNodeWithText("Confirm").assertIsFocused()
        compose.onNodeWithText("Confirm").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("1234", setPin) }
    }

    @Test fun mismatchedConfirmationDoesNotSaveAndAllowsRetry() {
        show()
        enter("1234")
        compose.onNodeWithText("Continue").performClick()
        enter("1235")
        compose.onNodeWithText("Confirm").performClick()
        compose.onNodeWithText("PINs do not match").assertExists()
        compose.runOnIdle { assertEquals(null, setPin) }
        enter("1234")
        compose.onNodeWithText("Confirm").performClick()
        compose.runOnIdle { assertEquals("1234", setPin) }
    }
}
