package com.saab.tv.ui.addons

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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

        compose.onNodeWithText("Select All").performClick()
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
}
