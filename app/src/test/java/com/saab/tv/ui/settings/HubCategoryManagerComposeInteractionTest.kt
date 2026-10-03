package com.saab.tv.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.saab.tv.data.model.HubRowItemEntity
import com.saab.tv.domain.HubShape
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class HubCategoryManagerComposeInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun renameCategoryIsReturnedWhenSavingTheList() {
        val initial = HubRowItemEntity("row", "movie:top", "Action")
        var saved: List<HubRowItemEntity>? = null
        compose.setContent {
            MaterialTheme {
                HubCategoryManagerDialog(listOf(initial), HubShape.HORIZONTAL, onDismiss = {}, onSave = { saved = it })
            }
        }

        compose.onNodeWithText("Action").performClick()
        compose.onNodeWithText("Rename").performClick()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("Adventure")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Save Changes").performClick()

        assertEquals("Adventure", saved?.single()?.title)
    }

    @Test fun removeImageAndRemoveCategoryAreConfirmedBeforeSaving() {
        val initial = HubRowItemEntity("row", "movie:top", "Action", customImageUrl = "/tmp/custom.jpg")
        var saved: List<HubRowItemEntity>? = null
        compose.setContent {
            MaterialTheme {
                HubCategoryManagerDialog(listOf(initial), HubShape.SQUARE, onDismiss = {}, onSave = { saved = it })
            }
        }

        compose.onNodeWithText("Action").performClick()
        compose.onNodeWithText("Remove Image").performClick()
        compose.onNodeWithText("Remove").performClick()
        compose.onNodeWithText("Action").performClick()
        compose.onNodeWithText("Remove from Hub").performClick()
        compose.onNodeWithText("Remove").performClick()
        compose.onNodeWithText("Save Changes").performClick()

        assertEquals(emptyList<HubRowItemEntity>(), saved)
    }
}
