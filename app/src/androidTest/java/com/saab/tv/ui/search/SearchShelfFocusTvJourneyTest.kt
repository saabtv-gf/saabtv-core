package com.saab.tv.ui.search

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.testfixture.ComposeTestActivity
import com.saab.tv.ui.components.ArtworkPrefetchDispatcher
import com.saab.tv.ui.components.LocalTitleCardShape
import com.saab.tv.ui.components.ShelfFocusIndicatorActive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** D-pad regression journey for Search shelf focus and selection restoration. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class SearchShelfFocusTvJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestActivity>()

    @Test fun staleResultsRemainFocusableButCannotOpenTitleOrViewAll() {
        var openedTitle = false
        var quickActionOpens = 0
        var openedViewAll = false
        val interactive = mutableStateOf(true)
        val rowEntry = FocusRequester()
        val moreEntry = FocusRequester()
        val menuEntry = FocusRequester()

        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.size(1.dp).focusRequester(menuEntry).focusable()
                        .semantics { contentDescription = "Menu" })
                    SearchResultRow(
                        title = "Search results",
                        items = listOf(MetaItem(id = "previous", type = "movie", name = "Previous query result")),
                        posterHeight = 120.dp,
                        targetKey = null,
                        targetRequester = remember { FocusRequester() },
                        keyboardRequester = remember { FocusRequester() },
                        drawerRequester = menuEntry,
                        moreRequester = moreEntry,
                        lastFocusedId = null,
                        watchedIds = emptySet(),
                        enrichedItems = emptyMap(),
                        onClick = { openedTitle = true },
                        onLongClick = { _, _ -> quickActionOpens++ },
                        onMore = { _, _ -> openedViewAll = true },
                        onFocused = {},
                        modifier = Modifier,
                        rowRequester = rowEntry,
                        upRequester = menuEntry,
                        downRequester = menuEntry,
                        landscapeCards = false,
                        pivotOffset = 48.dp,
                        artworkPrefetchDispatcher = remember { ArtworkPrefetchDispatcher({}, {}, {}) },
                        isInteractive = interactive.value
                    )
                }
            }
        }

        val previousResult = compose.onNode(hasClickAction() and hasContentDescription("Previous query result"))
        previousResult.performSemanticsAction(SemanticsActions.RequestFocus)
        previousResult.assertIsFocused()
        dispatchRepeatedCenterPress()
        compose.runOnIdle {
            assertEquals("current results still support the existing quick action", 1, quickActionOpens)
            assertEquals("a long press must not also activate the title", false, openedTitle)
        }
        compose.runOnIdle { interactive.value = false }
        compose.waitForIdle()
        dispatchRepeatedCenterPress()
        compose.runOnIdle {
            assertEquals("stale results cannot open quick actions", 1, quickActionOpens)
        }
        previousResult.performKeyInput { pressKey(Key.DirectionCenter) }

        val viewAll = compose.onNode(hasContentDescription("View All"))
        viewAll.performSemanticsAction(SemanticsActions.RequestFocus)
        viewAll.assertIsFocused()
        viewAll.performKeyInput { pressKey(Key.DirectionCenter) }

        compose.runOnIdle {
            assertEquals(false, openedTitle)
            assertEquals("stale results cannot open quick actions", 1, quickActionOpens)
            assertEquals(false, openedViewAll)
        }
    }

    private fun dispatchRepeatedCenterPress() {
        compose.runOnUiThread {
            val downTime = SystemClock.uptimeMillis()
            compose.activity.dispatchKeyEvent(
                AndroidKeyEvent(downTime, downTime, AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_CENTER, 0)
            )
            compose.activity.dispatchKeyEvent(
                AndroidKeyEvent(downTime, downTime + 550, AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_CENTER, 1)
            )
            compose.activity.dispatchKeyEvent(
                AndroidKeyEvent(downTime, downTime + 551, AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DPAD_CENTER, 0)
            )
        }
        compose.waitForIdle()
    }

    @Test fun freshLeftPressAtFirstResultEntersNavigation() {
        val menuEntry = FocusRequester()
        val rowEntry = FocusRequester()
        val items = listOf(MetaItem(id = "search-boundary", type = "movie", name = "Boundary result"))

        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.size(1.dp).focusRequester(menuEntry).focusable()
                        .semantics { contentDescription = "Search menu" })
                    SearchResultRow(
                        title = "Search results",
                        items = items,
                        posterHeight = 120.dp,
                        targetKey = null,
                        targetRequester = remember { FocusRequester() },
                        keyboardRequester = remember { FocusRequester() },
                        drawerRequester = menuEntry,
                        moreRequester = remember { FocusRequester() },
                        lastFocusedId = null,
                        watchedIds = emptySet(),
                        enrichedItems = emptyMap(),
                        onClick = {},
                        onLongClick = { _, _ -> },
                        onMore = { _, _ -> },
                        onFocused = {},
                        modifier = Modifier,
                        rowRequester = rowEntry,
                        upRequester = menuEntry,
                        downRequester = menuEntry,
                        landscapeCards = false,
                        pivotOffset = 48.dp,
                        artworkPrefetchDispatcher = remember { ArtworkPrefetchDispatcher({}, {}, {}) }
                    )
                }
            }
        }

        val firstResult = compose.onNode(hasClickAction() and hasContentDescription("Boundary result"))
        firstResult.performSemanticsAction(SemanticsActions.RequestFocus)
        firstResult.performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        compose.onNode(hasContentDescription("Search menu")).assertIsFocused()
    }

    @Test fun dpadMovementKeepsFocusFrameAlignedAndReturnsToThePreviousShelfSelection() {
        val firstRowEntry = FocusRequester()
        val secondRowEntry = FocusRequester()
        val menuEntry = FocusRequester()
        val bottomEntry = FocusRequester()
        val firstItems = (0..11).map { index ->
            MetaItem(id = "search-first-$index", type = "movie", name = "First result $index")
        }
        val secondItems = (0..5).map { index ->
            MetaItem(id = "search-second-$index", type = "series", name = "Second result $index")
        }

        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalTitleCardShape provides "poster") {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(1.dp).focusRequester(menuEntry).focusable()
                            .semantics { contentDescription = "Menu" })
                        SearchResultRow(
                            title = "First shelf",
                            items = firstItems,
                            posterHeight = 120.dp,
                            targetKey = null,
                            targetRequester = remember { FocusRequester() },
                            keyboardRequester = remember { FocusRequester() },
                            drawerRequester = menuEntry,
                            moreRequester = remember { FocusRequester() },
                            lastFocusedId = null,
                            watchedIds = emptySet(),
                            enrichedItems = emptyMap(),
                            onClick = {},
                            onLongClick = { _, _ -> },
                            onMore = { _, _ -> },
                            onFocused = {},
                            modifier = Modifier,
                            rowRequester = firstRowEntry,
                            upRequester = menuEntry,
                            downRequester = secondRowEntry,
                            landscapeCards = false,
                            pivotOffset = 48.dp,
                            artworkPrefetchDispatcher = remember { ArtworkPrefetchDispatcher({}, {}, {}) }
                        )
                        SearchResultRow(
                            title = "Second shelf",
                            items = secondItems,
                            posterHeight = 120.dp,
                            targetKey = null,
                            targetRequester = remember { FocusRequester() },
                            keyboardRequester = remember { FocusRequester() },
                            drawerRequester = menuEntry,
                            moreRequester = remember { FocusRequester() },
                            lastFocusedId = null,
                            watchedIds = emptySet(),
                            enrichedItems = emptyMap(),
                            onClick = {},
                            onLongClick = { _, _ -> },
                            onMore = { _, _ -> },
                            onFocused = {},
                            modifier = Modifier,
                            rowRequester = secondRowEntry,
                            upRequester = firstRowEntry,
                            downRequester = bottomEntry,
                            landscapeCards = false,
                            pivotOffset = 48.dp,
                            artworkPrefetchDispatcher = remember { ArtworkPrefetchDispatcher({}, {}, {}) }
                        )
                        Box(Modifier.size(1.dp).focusRequester(bottomEntry).focusable())
                    }
                }
            }
        }

        val root = compose.onRoot()
        compose.runOnIdle { firstRowEntry.requestFocus() }
        val selected = compose.onNode(hasClickAction() and hasContentDescription("First result 0"))
        selected.assertIsFocused()
        assertIndicatorOn(0)

        repeat(4) {
            root.performKeyInput { pressKey(Key.DirectionRight) }
            compose.waitForIdle()
        }
        val movedSelection = compose.onNode(hasClickAction() and hasContentDescription("First result 4"))
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasClickAction() and hasContentDescription("First result 4"))
                .fetchSemanticsNodes().any { node ->
                    node.config.contains(SemanticsProperties.Focused) && node.config[SemanticsProperties.Focused]
                }
        }
        movedSelection.assertIsFocused()
        assertIndicatorOn(0)
        assertFrameTracks(movedSelection, 0)
        assertEquals(
            with(compose.density) { 48.dp.toPx() },
            movedSelection.fetchSemanticsNode().boundsInRoot.left,
            1f
        )

        root.performKeyInput { pressKey(Key.DirectionDown) }
        val secondSelection = compose.onNode(hasClickAction() and hasContentDescription("Second result 0"))
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasClickAction() and hasContentDescription("Second result 0"))
                .fetchSemanticsNodes().any { node ->
                    node.config.contains(SemanticsProperties.Focused) && node.config[SemanticsProperties.Focused]
                }
        }
        secondSelection.assertIsFocused()
        assertIndicatorOn(1)

        // Search cards route Up to the previously selected item in the preceding shelf.
        root.performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()
        movedSelection.assertIsFocused()
        assertIndicatorOn(0)
        assertFrameTracks(movedSelection, 0)
    }

    private fun assertIndicatorOn(activeIndex: Int) {
        val indicators = compose.onAllNodesWithTag("search-shelf-focus-indicator").fetchSemanticsNodes()
        assertEquals("one focus indicator per Search shelf", 2, indicators.size)
        indicators.forEachIndexed { index, node ->
            assertEquals(index == activeIndex, node.config[ShelfFocusIndicatorActive])
        }
    }

    private fun assertFrameTracks(card: androidx.compose.ui.test.SemanticsNodeInteraction, rowIndex: Int) {
        val indicators = compose.onAllNodesWithTag("search-shelf-focus-indicator").fetchSemanticsNodes()
        val cardLeft = card.fetchSemanticsNode().boundsInRoot.left
        assertEquals(cardLeft, indicators[rowIndex].boundsInRoot.left, 1f)
    }
}
