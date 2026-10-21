package com.saab.tv.ui.player.base

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PlayerSubtitleColorChipAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun colorSwatchAnnouncesItsLocalizedNameSelectionAndAction() {
        var selectedColor: Color? = null
        compose.setContent {
            MaterialTheme {
                PlayerSubtitleColorChip(
                    color = Color.Cyan,
                    isSelected = true,
                    contentDescription = "Subtitle text color: Cyan",
                    onClick = { selectedColor = Color.Cyan }
                )
            }
        }

        compose.onNodeWithContentDescription("Subtitle text color: Cyan")
            .assertIsSelected()
            .performClick()

        compose.runOnIdle { assertEquals(Color.Cyan, selectedColor) }
    }
}
