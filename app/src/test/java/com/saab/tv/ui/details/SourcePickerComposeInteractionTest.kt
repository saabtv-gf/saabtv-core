package com.saab.tv.ui.details

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import com.saab.tv.data.localization.AppLanguage
import com.saab.tv.data.model.stremio.MetaVideo
import com.saab.tv.data.model.stremio.Stream
import com.saab.tv.data.model.stremio.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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

    @Test fun addonFilterUsesSharedTvButtonAndOpensItsOptions() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    SourcesContent(
                        title = "Movie",
                        streams = listOf(stream("https://fixture.invalid/en", 3, "Movie 1080p English Audio")),
                        focusRequester = remember { FocusRequester() },
                        onSourceClick = {},
                        onBack = {}
                    )
                }
            }
        }

        compose.onNodeWithText("All Add-ons")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onAllNodesWithText("Torrentio", substring = true).assertCountEquals(2)
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
        ).assertHeightIsAtLeast(40.dp).performClick()
        compose.runOnIdle { assertEquals("https://fixture.invalid/te", languageSelectedUrl) }
    }

    @Test fun sourcePickerShowsLocalizedPickerAndAddonFilterLabels() {
        val localized = AppLanguage.localizedContext(
            RuntimeEnvironment.getApplication(),
            "kn"
        )
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        SourcesContent(
                            title = "Movie",
                            streams = emptyList(),
                            showBestLanguageOptions = true,
                            focusRequester = remember { FocusRequester() },
                            onSourceClick = {},
                            onBack = {}
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("ಮೂಲವನ್ನು ಆಯ್ಕೆಮಾಡಿ").assertExists()
        compose.onNodeWithText("ಮೂಲದ ಭಾಷೆ").assertExists()
        compose.onNodeWithText("ಎಲ್ಲಾ ಆಡ್-ಆನ್‌ಗಳು").assertExists()
    }

    @Test fun episodeWatchedToggleHasAccessibleLabelWhenCollapsed() {
        val watched = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    EpisodeItem(
                        episode = MetaVideo(id = "episode-1", season = 1, episode = 1),
                        isWatched = watched.value,
                        onToggleWatched = { watched.value = !watched.value },
                        onClick = {}
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Mark as watched")
            .assertExists()
            .performClick()
        compose.runOnIdle { assertEquals(true, watched.value) }
        compose.onNodeWithContentDescription("Watched").assertExists()
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
