package com.saab.tv.ui.components

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
class RetainedArtworkComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun drawableFrameRendersImageAndExposesAccessibleLabel() {
        compose.setContent {
            RetainedArtworkFrame(
                painter = ColorPainter(Color.Red),
                contentDescription = "Loaded poster",
                modifier = Modifier.size(64.dp),
                contentScale = ContentScale.Crop,
                showFallback = true,
                unavailable = { Text("Poster unavailable") }
            )
        }

        compose.onNodeWithContentDescription("Loaded poster").assertExists()
        compose.onNodeWithText("Poster unavailable").assertDoesNotExist()
    }

    @Test fun pendingFrameKeepsItsAccessibleLabelWithoutFlashingFallbackText() {
        compose.setContent {
            RetainedArtworkFrame(
                painter = null,
                contentDescription = "Pending poster",
                modifier = Modifier.size(64.dp),
                contentScale = ContentScale.Crop,
                showFallback = shouldShowArtworkFallback(
                    hasDisplayedArtwork = false,
                    hasArtworkModel = true
                ),
                unavailable = { Text("Poster unavailable") }
            )
        }

        compose.onNodeWithContentDescription("Pending poster").assertExists()
        compose.onNodeWithText("Poster unavailable").assertDoesNotExist()
    }

    @Test fun failedFrameShowsFallbackAndKeepsItsLabel() {
        compose.setContent {
            RetainedArtworkFrame(
                painter = null,
                contentDescription = "Failed poster",
                modifier = Modifier.size(64.dp),
                contentScale = ContentScale.Crop,
                showFallback = shouldShowArtworkFallback(
                    hasDisplayedArtwork = false,
                    hasArtworkModel = true,
                    requestFailed = true
                ),
                unavailable = { Text("Poster unavailable") }
            )
        }

        compose.onNodeWithContentDescription("Failed poster").assertExists()
        compose.onNodeWithText("Poster unavailable").assertExists()
    }

    @Test fun absentArtworkShowsFallbackAndKeepsItsAccessibleLabel() {
        compose.setContent {
            RetainedArtworkFrame(
                painter = null,
                contentDescription = "Missing poster",
                modifier = Modifier.size(64.dp),
                contentScale = ContentScale.Crop,
                showFallback = shouldShowArtworkFallback(
                    hasDisplayedArtwork = false,
                    hasArtworkModel = false
                ),
                unavailable = { Text("Poster unavailable") }
            )
        }

        compose.onNodeWithContentDescription("Missing poster").assertExists()
        compose.onNodeWithText("Poster unavailable").assertExists()
    }
}
