package com.saab.tv.ui.player.base

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
class PlaybackLoadingOverlayTest {
    @get:Rule val compose = createComposeRule()

    @Before fun pauseInfiniteLoaderAnimation() {
        compose.mainClock.autoAdvance = false
    }

    @Test fun missingLogoShowsSpinnerInsteadOfPlainTitle() {
        compose.setContent {
            MaterialTheme {
                TvMaterialTheme {
                    LoadingOverlay(logoUrl = null, primaryText = "Loading title")
                }
            }
        }

        compose.onNodeWithTag("playback-loading-overlay", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("playback-loading-spinner", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Loading title").assertDoesNotExist()
    }

    @Test fun availableLogoIsUsedAsLoadingArtwork() {
        compose.setContent {
            MaterialTheme {
                TvMaterialTheme {
                    LoadingOverlay(
                        logoUrl = "https://example.invalid/title-logo.png",
                        primaryText = "Loading title"
                    )
                }
            }
        }

        compose.onNodeWithTag("playback-loading-logo", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Loading title").assertDoesNotExist()
    }
}
