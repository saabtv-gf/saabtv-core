package com.saab.tv.ui.home

import android.app.Application
import androidx.compose.material3.MaterialTheme as Material3Theme
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.saab.tv.data.model.stremio.MetaItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class, androidx.tv.material3.ExperimentalTvMaterial3Api::class)
class HeroCarouselComposeInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val items = listOf(
        MetaItem("first", "movie", "First Film", description = "First synopsis", releaseInfo = "2024-02-01", imdbRating = "7.8", runtime = "125 min", genres = listOf("Drama")),
        MetaItem("second", "series", "Second Show", description = "Second synopsis", releaseInfo = "2023", imdbRating = "8.1", runtime = "45m", genres = listOf("Comedy"))
    )

    @Test fun remoteMovesHeroAndActivatesSelectedItemAndContentNavigation() {
        val focus = FocusRequester()
        val focusedIds = mutableListOf<String>()
        val openedIds = mutableListOf<String>()
        var downCalls = 0
        compose.setContent {
            Material3Theme {
                MaterialTheme {
                    HeroCarousel(
                        items = items,
                        autoScrollSeconds = 0,
                        onItemClick = { openedIds += it.id },
                        startPadding = 24.dp,
                        onFocusChange = { focusedIds += it },
                        entryRequester = focus,
                        isFirstItem = true,
                        onNavigateDown = { downCalls++ }
                    )
                }
            }
        }

        compose.runOnIdle { focus.requestFocus() }
        compose.onNodeWithText("First Film").performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitUntil(2_000) { focusedIds.lastOrNull() == "second" }
        compose.onNodeWithText("Second Show").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("Second Show").performKeyInput { pressKey(Key.DirectionDown) }

        assertEquals(listOf("second"), openedIds)
        assertEquals(1, downCalls)
    }

    @Test fun leftFromFirstItemWrapsToLastWhenThereIsNoSideMenu() {
        val focus = FocusRequester()
        val focusedIds = mutableListOf<String>()
        compose.setContent {
            Material3Theme {
                MaterialTheme {
                    HeroCarousel(
                        items = items,
                        autoScrollSeconds = 0,
                        onItemClick = {},
                        startPadding = 24.dp,
                        onFocusChange = { focusedIds += it },
                        entryRequester = focus,
                        isTopNav = false
                    )
                }
            }
        }

        compose.runOnIdle { focus.requestFocus() }
        compose.onNodeWithText("First Film").performKeyInput { pressKey(Key.DirectionLeft) }

        compose.waitUntil(2_000) { focusedIds.lastOrNull() == "second" }
        compose.onNodeWithText("Second Show").assertExists()
    }
}
