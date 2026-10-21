package com.saab.tv.ui.details

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.saab.tv.data.model.stremio.Stream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
class ProgressiveSourcesComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun policyApprovedPlayablePartialSourceCanBeSelectedWithoutSlowProvider() {
        var selected = false
        val requester = FocusRequester()
        val stream = Stream(name = "Fast provider · 1080p English", title = "Movie", url = "https://media.invalid/fast.mkv")

        compose.setContent {
            MaterialTheme {
                SourcesContent(
                    title = "Movie",
                    streams = listOf(stream),
                    isLoadingSources = true,
                    canSelectPartialSources = true,
                    focusRequester = requester,
                    onSourceClick = { selected = true },
                    onBack = {}
                )
            }
        }

        compose.onNodeWithText("Checking remaining sources…").assertExists()
        compose.runOnIdle { requester.requestFocus() }
        compose.onNode(hasText("Movie") and hasClickAction()).assertIsFocused()
        compose.onAllNodesWithText("Movie").get(1).performClick()
        assertTrue("An already-filtered playable source should not wait for a slower addon", selected)
    }

    @Test fun nonPlayablePartialSourceStaysDisabledWhileDiscoveryContinues() {
        var selected = false
        val stream = Stream(name = "Fast provider · 1080p English", title = "Movie")

        compose.setContent {
            MaterialTheme {
                SourcesContent(
                    title = "Movie",
                    streams = listOf(stream),
                    isLoadingSources = true,
                    canSelectPartialSources = true,
                    focusRequester = FocusRequester(),
                    onSourceClick = { selected = true },
                    onBack = {}
                )
            }
        }

        compose.onAllNodesWithText("Movie").get(1).performClick()
        assertFalse("Rows without a playback URL or infohash are never selectable", selected)
    }
}
