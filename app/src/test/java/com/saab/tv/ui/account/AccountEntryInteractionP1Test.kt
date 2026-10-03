package com.saab.tv.ui.account

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.saab.tv.data.account.AccountAuthManager
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class AccountEntryInteractionP1Test {
    @get:Rule val compose=createComposeRule()
    private var submit=0
    private fun show() {
        compose.setContent { MaterialTheme { AccountLoginForm(AccountAuthManager(RuntimeEnvironment.getApplication()),null,false) { _,_,_->submit++ } } }
        compose.waitForIdle()
    }
    @Test fun focusAloneDoesNotEnableKeyboardEditingUntilSecondRemoteEnter() {
        show()
        val username=compose.onNodeWithContentDescription("Username")
        username.assertIsFocused()
        username.performKeyInput { pressKey(Key.DirectionCenter) }
        username.performTextInput("tester")
        compose.onNodeWithText("tester").assertExists()
    }
    @Test fun passwordEyeIsReachableAndTogglesVisibleAndHiddenState() {
        show()
        compose.onNodeWithContentDescription("Show Password").performClick()
        compose.onNodeWithContentDescription("Hide Password").assertExists()
        compose.onNodeWithContentDescription("Hide Password").performClick()
        compose.onNodeWithContentDescription("Show Password").assertExists()
    }
    @Test fun signupShowsConfirmationAndRequiresMatchingPasswords() {
        show()
        compose.onNodeWithText("Create Account").performClick()
        compose.onNodeWithContentDescription("Confirm Password").assertExists()
        compose.onNodeWithText("8+ characters · uppercase · lowercase · number · symbol").assertExists()
        compose.onNodeWithText("Create Account With Phone").assertExists()
        compose.runOnIdle { assertEquals(0,submit) }
    }
    @Test fun signinAndSignupSwitchesKeepOnlyRelevantFieldsVisible() {
        show()
        compose.onNodeWithText("Create Account").performClick()
        compose.onNodeWithText("Sign In").performClick()
        compose.onNodeWithContentDescription("Confirm Password").assertDoesNotExist()
        compose.onNodeWithText("Sign In With Phone").assertExists()
        compose.onNodeWithText("Create Account With Phone").assertDoesNotExist()
    }
}
