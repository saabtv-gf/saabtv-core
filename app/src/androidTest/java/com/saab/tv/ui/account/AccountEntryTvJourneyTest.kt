package com.saab.tv.ui.account

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-level TV journeys for the real launcher account gate. These tests do not submit
 * credentials or call Neon; they exercise D-pad focus and account-form mode/visibility UI.
 */
@RunWith(AndroidJUnit4::class)
class AccountEntryTvJourneyTest {
    @get:Rule
    val compose = createAndroidComposeRule<AccountEntryActivity>()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun coldLaunchStartsAtUsernameAndDpadMovesToPassword() {
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Username").assertIsFocused()
        compose.onNodeWithContentDescription("Username").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("Password").assertIsFocused()
    }

    @Test
    fun createAccountJourneyShowsConfirmationAndPasswordVisibilityControls() {
        compose.waitForIdle()
        compose.onAllNodesWithText("Create Account", substring = false, useUnmergedTree = true)
            .get(0).performClick()

        compose.onNodeWithContentDescription("Confirm Password").assertExists()
        compose.onNodeWithContentDescription("Show Password").assertExists()
        compose.onNodeWithContentDescription("Show Confirm Password").assertExists()

        compose.onNodeWithContentDescription("Show Password").performClick()
        compose.onNodeWithContentDescription("Hide Password").assertExists()
    }

    @Test
    fun returningToSignInHidesSignupOnlyFields() {
        compose.waitForIdle()
        compose.onAllNodesWithText("Create Account", substring = false, useUnmergedTree = true)
            .get(0).performClick()
        compose.onNodeWithContentDescription("Confirm Password").assertExists()

        compose.onNodeWithText("Sign In", useUnmergedTree = true).performClick()

        compose.onNodeWithContentDescription("Confirm Password").assertDoesNotExist()
        compose.onNodeWithContentDescription("Username").assertIsDisplayed()
        compose.onNodeWithContentDescription("Password").assertIsDisplayed()
    }
}
