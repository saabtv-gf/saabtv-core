package com.saab.tv.ui.details

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import com.saab.tv.data.model.stremio.Stream
import com.saab.tv.data.model.stremio.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
class SourcePickerComposeInteractionTest {
    @get:Rule val compose = createComposeRule()
    private var selectedUrl: String? = null
    private var languageSelectedUrl: String? = null

    @Test fun sourceCardSelectionReturnsTheChosenStream() {
        val streams = listOf(
            stream("https://fixture.invalid/five", 5, "Movie 1080p English"),
            stream("https://fixture.invalid/one", 1, "Movie 720p English")
        )
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    SourcesContent(
                        title = "Movie",
                        streams = streams,
                        focusRequester = remember { FocusRequester() },
                        onSourceClick = { selectedUrl = it.url },
                        onBack = {}
                    )
                }
            }
        }

        compose.onNode(hasText("1 TorBox", substring = true) and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals("https://fixture.invalid/one", selectedUrl) }
    }

    @Test fun selectingPreferredLanguageChoosesItsBestMatchingStream() {
        val english = stream("https://fixture.invalid/en", 3, "Movie 1080p English Audio")
        val telugu = stream("https://fixture.invalid/te", 1, "Movie 720p Telugu Audio")
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    SourcesContent(
                        title = "Movie",
                        streams = listOf(english, telugu),
                        showBestLanguageOptions = true,
                        focusRequester = remember { FocusRequester() },
                        onSourceClick = { selectedUrl = it.url },
                        onLanguageSourceClick = { languageSelectedUrl = it.url },
                        onBack = {}
                    )
                }
            }
        }

        compose.onNode(
            hasText("Telugu") and androidx.compose.ui.test.SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        ).performClick()
        compose.runOnIdle { assertEquals("https://fixture.invalid/te", languageSelectedUrl) }
    }

    private fun stream(url: String, seeders: Int, title: String) = Stream(
        name = "[Torrentio]",
        title = title,
        url = url,
        seeders = seeders,
        torBoxChecked = true,
        torBoxCached = true,
        torBoxSeeders = seeders,
        behaviorHints = StreamBehaviorHints(filename = title)
    )
}
