package com.saab.tv.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import com.saab.tv.ui.addons.VoidButton
import com.saab.tv.ui.addons.VoidDialog
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
class VoidDialogFooterComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun actionFooterStaysVisibleWhileDialogBodyScrolls() {
        var saved = false
        compose.setContent {
            MaterialTheme {
                VoidDialog(
                    onDismissRequest = {},
                    title = "Long settings dialog",
                    footer = {
                        VoidButton("Pinned Save", { saved = true }, Modifier.fillMaxWidth(), isPrimary = true)
                    }
                ) {
                    repeat(30) { index ->
                        Text(
                            "Scrollable row $index",
                            modifier = Modifier.fillMaxWidth().height(36.dp).padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("Pinned Save").assertIsDisplayed()
        compose.onNodeWithText("Scrollable row 29").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Pinned Save").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(saved) }
    }

    @Test fun disabledFooterActionsKeepTheirLabelsVisible() {
        compose.setContent {
            MaterialTheme {
                VoidDialog(
                    onDismissRequest = {},
                    title = "Hero Carousel",
                    footer = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            VoidButton("Disable", {}, Modifier.weight(1f), enabled = false)
                            VoidButton("Save", {}, Modifier.weight(1f), enabled = false)
                        }
                    }
                ) {
                    repeat(30) { index ->
                        Text("Scrollable row $index", Modifier.fillMaxWidth().height(36.dp))
                    }
                }
            }
        }

        compose.onNodeWithText("Disable")
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .assertWidthIsAtLeast(150.dp)
        compose.onNodeWithText("Save")
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .assertWidthIsAtLeast(150.dp)
    }
}
