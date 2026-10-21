package com.saab.tv.ui.addons

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.saab.tv.data.model.AddonEntity
import com.saab.tv.data.model.StremioAddonItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class AddonImportDialogComposeInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectAllImportsOnlyNewAddons() {
        val available = StremioAddonItem("Available", "https://available.invalid/manifest.json", "New", false, false)
        val installed = StremioAddonItem("Installed", "https://installed.invalid/manifest.json", "Existing", true, true)
        var imported: List<StremioAddonItem>? = null
        compose.setContent {
            MaterialTheme {
                AddonImportDialog(listOf(available, installed), onDismissRequest = {}, onConfirmImport = { imported = it })
            }
        }

        compose.onNodeWithText("Import Stremio Add-ons").assertExists()
        compose.onNodeWithText("Found 2 add-ons • 0 selected").assertExists()
        compose.onNodeWithContentDescription("Not selected").assertExists()
        compose.onAllNodesWithText("Installed", substring = true).assertCountEquals(2)
        compose.onNodeWithText("Select All").performClick()
        compose.onNodeWithText("Found 2 add-ons • 1 selected").assertExists()
        compose.onNodeWithText("Import (1)").assertExists().performClick()

        assertEquals(listOf(available.copy(isSelected = true)), imported)
    }

    @Test fun deselectAllLeavesImportDisabledAndCancelDismisses() {
        val selected = StremioAddonItem("Available", "https://available.invalid/manifest.json", "New", true, false)
        var dismissed = false
        var imported = false
        compose.setContent {
            MaterialTheme {
                AddonImportDialog(listOf(selected), onDismissRequest = { dismissed = true }, onConfirmImport = { imported = true })
            }
        }

        compose.onNodeWithText("Deselect All").performClick()
        compose.onNodeWithText("Import (0)").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()

        assertTrue(dismissed)
        assertEquals(false, imported)
    }

    @Test fun importRowKeepsItsBoundsWhenFocused() {
        val available = StremioAddonItem("Available", "https://available.invalid/manifest.json", "New", false, false)
        compose.setContent {
            MaterialTheme {
                AddonImportDialog(listOf(available), onDismissRequest = {}, onConfirmImport = {})
            }
        }
        val row = compose.onNodeWithText("Available")
        val initialRowBounds = row.fetchSemanticsNode().boundsInRoot
        row.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        row.assertIsFocused()
        assertEquals(initialRowBounds, row.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun addonRowKeepsItsBoundsWhenFocused() {
        val addon = AddonEntity("https://addon.invalid/manifest.json", "addon", "Fixture Addon", "1", null, null)
        val addonFocus = FocusRequester()
        compose.setContent { MaterialTheme { VoidAddonItem(addon, {}, focusRequester = addonFocus) } }
        val addonNode = compose.onNodeWithText("Fixture Addon")
        val initialAddonBounds = addonNode.fetchSemanticsNode().boundsInRoot
        addonNode.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        addonNode.assertIsFocused()
        assertEquals(initialAddonBounds, addonNode.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun addonIconButtonKeepsItsBoundsWhenFocused() {
        compose.setContent { MaterialTheme { VoidIconButton(Icons.Default.Add, "Add", {}) } }
        val iconButton = compose.onNodeWithContentDescription("Add")
        val initialIconBounds = iconButton.fetchSemanticsNode().boundsInRoot
        iconButton.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        iconButton.assertIsFocused()
        assertEquals(initialIconBounds, iconButton.fetchSemanticsNode().boundsInRoot)
    }
}
