package com.saab.tv.ui.account

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class AccountDataUnavailablePanelComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun retryIsReadableAndFirstFocusThenOfflineEscapePrecedesSignOut() {
        var retries = 0
        var offlineEntries = 0
        var signOuts = 0
        compose.setContent {
            MaterialTheme {
                AccountDataUnavailablePanel(
                    error = "Account data could not be unlocked. Local data is preserved.",
                    offlineAllowed = true,
                    onRetry = { retries++ },
                    onContinueOffline = { offlineEntries++ },
                    onSignOut = { signOuts++ }
                )
            }
        }

        compose.onNodeWithTag("account-unavailable-title").assertIsDisplayed()
        val retry = compose.onNodeWithText("Try Again")
        retry.assertIsDisplayed().assertIsFocused()
        retry.performKeyInput { pressKey(Key.DirectionDown) }
        val offline = compose.onNodeWithText("Continue Offline")
        offline.assertIsFocused().performClick()
        compose.runOnIdle { assertEquals(0, retries); assertEquals(1, offlineEntries); assertEquals(0, signOuts) }

        offline.performKeyInput { pressKey(Key.DirectionDown) }
        val signOut = compose.onNodeWithText("Sign Out")
        signOut.assertIsFocused().performClick()
        compose.runOnIdle { assertEquals(1, signOuts) }
    }

    @Test fun offlineActionIsAbsentWhenLocalAccountCannotSafelyContinue() {
        var retries = 0
        compose.setContent {
            MaterialTheme {
                AccountDataUnavailablePanel(
                    error = "Account data could not be unlocked.",
                    offlineAllowed = false,
                    onRetry = { retries++ },
                    onContinueOffline = {},
                    onSignOut = {}
                )
            }
        }

        assertEquals(0, compose.onAllNodesWithText("Continue Offline").fetchSemanticsNodes().size)
        val retry = compose.onNodeWithText("Try Again")
        retry.assertIsFocused().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
