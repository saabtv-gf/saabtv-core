package com.saab.tv.ui.components

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalFoundationApi::class)
class FocusShelfEdgeAlignmentComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun finiteTrailingCardAndFocusFrameShareBoundsWithLeadingContentPadding() {
        val listState = LazyListState(firstVisibleItemIndex = 8)
        val focused = mutableStateOf(true)

        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    FocusShelf(
                        state = listState,
                        focused = focused,
                        width = 120.dp,
                        height = 180.dp,
                        startPadding = 48.dp,
                        focusedItemIndex = 9,
                        tag = "edge-focus-frame",
                        modifier = Modifier.size(width = 400.dp, height = 192.dp),
                        viewportHeight = 192.dp
                    ) {
                        items(10, key = { it }) { index ->
                            Box(Modifier.size(120.dp, 180.dp).testTag("edge-card-$index"))
                        }
                    }
                }
            }
        }

        compose.waitUntil(5_000) {
            listState.layoutInfo.visibleItemsInfo.any { it.index == 9 } && !listState.canScrollForward
        }

        val card = compose.onNodeWithTag("edge-card-9").assertExists().fetchSemanticsNode().boundsInRoot
        val frame = compose.onNodeWithTag("edge-focus-frame").assertExists().fetchSemanticsNode().boundsInRoot
        assertEquals("frame follows the selected card at the finite rail edge", card.left, frame.left, 1f)
        assertEquals(card.top, frame.top, 1f)
    }
}
