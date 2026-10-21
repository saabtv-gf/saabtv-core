package com.saab.tv.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.saab.tv.data.model.HubRowItemEntity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
class HubItemManageDialogComposeInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun removeConfirmationShowsFullWidthActionsAndRunsRemove() {
        var removed = false
        val item = HubRowItemEntity("row", "movie:top", "Action")

        compose.setContent {
            MaterialTheme {
                HubItemManageDialog(
                    item = item,
                    onDismiss = {},
                    onRename = {},
                    onStartReorder = {},
                    onRemove = { removed = true },
                    onRemoveImage = {}
                )
            }
        }

        compose.onNodeWithText("Remove from Hub").performClick()
        compose.onNodeWithText("Cancel").assertIsDisplayed().assertWidthIsAtLeast(150.dp)
        compose.onNodeWithText("Remove").assertIsDisplayed().assertWidthIsAtLeast(150.dp).performClick()
        compose.runOnIdle { assertTrue(removed) }
    }
}
