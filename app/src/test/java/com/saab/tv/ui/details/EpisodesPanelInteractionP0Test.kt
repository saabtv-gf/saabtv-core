package com.saab.tv.ui.details

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.remember
import com.saab.tv.data.model.stremio.MetaVideo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class EpisodesPanelInteractionP0Test {
    @get:Rule val compose = createComposeRule()
    private var selected: Triple<String, Int, Int>? = null
    private var changedSeason: Int? = null
    private val episodes = listOf(
        MetaVideo(id = "show:1:1", season = 1, episode = 1, title = "First Season First"),
        MetaVideo(id = "show:1:2", season = 1, episode = 2, title = "First Season Second"),
        MetaVideo(id = "show:2:1", season = 2, episode = 1, title = "Second Season First"),
        MetaVideo(id = "show:2:2", season = 2, episode = 2, title = "Second Season Current"),
        MetaVideo(id = "show:2:3", season = 2, episode = 3, title = "Second Season Next")
    )

    private fun show() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    EpisodesContent(
                        videos = episodes, listState = rememberLazyListState(), savedSeason = 1, savedIndex = 0,
                        currentEpisodeId = "show:2:2", focusRequester = remember { FocusRequester() },
                        onEpisodeClick = { video, season, index -> selected = Triple(video.id, season, index) },
                        onSeasonChange = { changedSeason = it }, onDismiss = {}
                    )
                }
            }
        }
    }

    @Test fun currentEpisodeOverridesStaleSavedSeasonAndIsFocusedInEpisodePanel() {
        show()
        compose.waitUntil(3_000) { changedSeason == 2 }
        compose.onNodeWithText("Second Season Current").assertExists()
        compose.onNodeWithText("Playing").assertExists()
        compose.onNodeWithText("S2 : E2").assertIsFocused()
    }

    @Test fun choosingVisibleEpisodeReportsItsExactSeasonAndIndex() {
        show()
        compose.waitUntil(3_000) { changedSeason == 2 }
        compose.onNodeWithText("S2 : E2").performClick()
        compose.runOnIdle { assertEquals(Triple("show:2:2", 2, 1), selected) }
    }
}
