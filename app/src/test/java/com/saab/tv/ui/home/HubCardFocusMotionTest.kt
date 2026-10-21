package com.saab.tv.ui.home

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.saab.tv.domain.HubGroupRow
import com.saab.tv.domain.HubItem
import com.saab.tv.domain.HubShape
import com.saab.tv.ui.components.ShelfFocusIndicatorActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class HubCardFocusMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun blankCustomArtworkUsesTheSameReadableFallbackAsAnUnconfiguredCard() {
        assertNull(hubCardArtworkUrl("  \n"))
        assertEquals("https://image.test/hub.jpg", hubCardArtworkUrl("https://image.test/hub.jpg"))

        compose.setContent {
            MaterialTheme {
                HubCard(
                    hubItem = HubItem(
                        id = "library",
                        title = "Library",
                        categoryId = "library",
                        customImageUrl = "  \n"
                    ),
                    shape = HubShape.HORIZONTAL,
                    onClick = {}
                )
            }
        }

        compose.onNodeWithText("Library").assertExists()
    }

    @Test fun focusingHubCardDoesNotChangeItsBounds() {
        compose.setContent {
            MaterialTheme {
                HubCard(
                    hubItem = HubItem(id = "library", title = "Library", categoryId = "library"),
                    shape = HubShape.HORIZONTAL,
                    onClick = {}
                )
            }
        }

        val card = compose.onNode(hasText("Library") and hasClickAction())
        card.assertExists()
        val unfocusedBounds = card.fetchSemanticsNode().boundsInRoot

        card.performSemanticsAction(SemanticsActions.RequestFocus)
        card.assertIsFocused()

        assertEquals(unfocusedBounds, card.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun hubRowUsesTheSharedFixedPivotFocusFrame() {
        val requester = FocusRequester()
        val hubGroup = HubGroupRow(
            id = "home-hubs",
            title = "Browse",
            order = 0,
            shape = HubShape.HORIZONTAL,
            items = (0 until 8).map { index ->
                HubItem("hub-$index", "Hub $index", "hub-$index")
            }
        )
        compose.setContent {
            MaterialTheme {
                HubRow(
                    hubGroup = hubGroup,
                    startPadding = 48.dp,
                    onHubClick = {},
                    entryRequester = requester,
                    drawerRequester = FocusRequester(),
                    locallyFocusedItemId = null,
                    isTopNav = false,
                    rowIndex = 0,
                    isFirstRow = true,
                    repeatGate = DpadRepeatGate()
                )
            }
        }

        val indicator = compose.onNodeWithTag("hub-row-focus-indicator")
        val targetCard = compose.onNode(hasClickAction() and hasText("Hub 2"))
        indicator.assertWidthIsEqualTo(190.dp)
        indicator.assertHeightIsEqualTo(106.875.dp)
        indicator.assertLeftPositionInRootIsEqualTo(48.dp)
        indicator.assert(SemanticsMatcher.expectValue(ShelfFocusIndicatorActive, false))

        targetCard.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()

        indicator.assert(SemanticsMatcher.expectValue(ShelfFocusIndicatorActive, true))
        targetCard.assertLeftPositionInRootIsEqualTo(48.dp)
        assertEquals(
            targetCard.fetchSemanticsNode().boundsInRoot.left,
            indicator.fetchSemanticsNode().boundsInRoot.left,
            0.5f
        )
    }

    @Test fun restoredHubSelectionStaysPutUntilDpadNavigationResumesTheSharedPivot() {
        val hubGroup = HubGroupRow(
            id = "home-hubs",
            title = "Browse",
            order = 0,
            shape = HubShape.HORIZONTAL,
            items = (0 until 8).map { index ->
                HubItem("hub-$index", "Hub $index", "hub-$index")
            }
        )
        val listState = LazyListState(firstVisibleItemIndex = 2)
        val restoredFocus = mutableStateOf<String?>(
            homeHubFocusKey(0, hubGroup.id, hubGroup.items[5].id, 5)
        )
        val entryRequester = FocusRequester()
        val drawerRequester = FocusRequester()
        val repeatGate = DpadRepeatGate()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                HubRow(
                    hubGroup = hubGroup,
                    startPadding = 48.dp,
                    onHubClick = {},
                    entryRequester = entryRequester,
                    drawerRequester = drawerRequester,
                    locallyFocusedItemId = restoredFocus.value,
                    isTopNav = false,
                    rowIndex = 0,
                    externalListState = listState,
                    repeatGate = repeatGate,
                    isGlobalFocusPresent = restoredFocus.value != null
                )
            }
        }
        compose.mainClock.advanceTimeByFrame()

        val restoredCard = compose.onNode(hasClickAction() and hasText("Hub 5"))
        restoredCard.performSemanticsAction(SemanticsActions.RequestFocus)
        restoredCard.assertIsFocused()
        val restoredScrollPosition = compose.runOnIdle {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
        compose.runOnIdle {
            assertEquals("restoration must not jump the saved shelf position", 2, listState.firstVisibleItemIndex)
        }

        restoredCard.performKeyInput { pressKey(Key.DirectionRight) }
        compose.mainClock.advanceTimeBy(500)

        compose.onNode(hasClickAction() and hasText("Hub 6")).assertIsFocused()
        compose.runOnIdle {
            assertNotEquals(
                "D-pad navigation resumes bring-into-view scrolling",
                restoredScrollPosition,
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            )
        }
    }
}
