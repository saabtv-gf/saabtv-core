package com.saab.tv.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saab.tv.domain.HubShape
import com.saab.tv.testfixture.ComposeTestActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Device-level D-pad checks for the shared settings dialog controls. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class SettingsControlsTvJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestActivity>()

    @Test fun hubEditorKeepsFooterActionsVisibleAndDpadSelectable() {
        val selectedShape = mutableStateOf(HubShape.HORIZONTAL)
        var saved = false

        compose.setContent {
            MaterialTheme {
                HubEditorContent(
                    title = "Edit hub",
                    name = "Browse",
                    onNameChange = {},
                    shape = selectedShape.value,
                    onShapeChange = { selectedShape.value = it },
                    categoryCount = 1,
                    onAddCategory = {},
                    onManageCategories = {},
                    onCancel = {},
                    onSave = { saved = true }
                )
            }
        }

        compose.onNodeWithText("Add Category").assertExists().performScrollTo().assertIsDisplayed()
        val manageCategories = compose.onNodeWithText("Manage Categories")
        manageCategories.assertExists().performScrollTo().assertIsDisplayed()
        val horizontal = compose.onNodeWithText("Landscape")
        horizontal.performScrollTo()
        val vertical = compose.onNodeWithText("Portrait")
        val square = compose.onNodeWithText("Square")
        horizontal.performSemanticsAction(SemanticsActions.RequestFocus)
        horizontal.performKeyInput { pressKey(Key.DirectionRight) }
        vertical.assertIsFocused()
        vertical.performKeyInput { pressKey(Key.DirectionRight) }
        square.assertIsFocused()
        square.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(HubShape.SQUARE, selectedShape.value) }

        val save = compose.onNodeWithText("Save")
        save.assertIsDisplayed().performSemanticsAction(SemanticsActions.RequestFocus)
        save.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertTrue(saved) }
    }

    @Test fun backFromDialogBodyMovesFocusToCancelBeforeInvokingIt() {
        var cancelled = false
        compose.setContent {
            com.saab.tv.ui.navigation.BackNavigationHost(onUnhandledBack = {}) {
                MaterialTheme {
                    HubEditorContent(
                        title = "Edit hub",
                        name = "Browse",
                        onNameChange = {},
                        shape = HubShape.HORIZONTAL,
                        onShapeChange = {},
                        categoryCount = 0,
                        onAddCategory = {},
                        onManageCategories = {},
                        onCancel = { cancelled = true },
                        onSave = {}
                    )
                }
            }
        }

        val addCategory = compose.onNodeWithText("Add Category")
        addCategory.performSemanticsAction(SemanticsActions.RequestFocus)
        addCategory.performKeyInput { pressKey(Key.Back) }

        val cancel = compose.onNodeWithText("Cancel")
        cancel.assertIsFocused()
        cancel.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertTrue(cancelled) }
    }
}
