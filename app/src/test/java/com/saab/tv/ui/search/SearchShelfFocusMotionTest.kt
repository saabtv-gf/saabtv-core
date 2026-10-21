package com.saab.tv.ui.search

import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.schema.canonicalPresentationKey
import com.saab.tv.ui.components.ArtworkPrefetchDispatcher
import com.saab.tv.ui.components.ShelfFocusIndicatorActive
import com.saab.tv.ui.components.LocalTitleCardShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class SearchShelfFocusMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun scrollableResultsKeepTheFocusFrameFixedWhileCardsSlideUnderIt() {
        val items = (0..11).map { MetaItem("tt-scroll-$it", "movie", "Scroll $it") }
        val rowRequester = FocusRequester()
        val focusedId = java.util.concurrent.atomic.AtomicReference<String?>(null)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalTitleCardShape provides "poster") {
                    androidx.compose.foundation.layout.Box(Modifier.size(420.dp, 260.dp)) {
                        SearchResultRow(
                            title = "Scrollable Search",
                            items = items,
                            posterHeight = 180.dp,
                            targetKey = null,
                            targetRequester = FocusRequester(),
                            keyboardRequester = FocusRequester(),
                            drawerRequester = FocusRequester(),
                            moreRequester = FocusRequester(),
                            lastFocusedId = null,
                            watchedIds = emptySet(),
                            enrichedItems = emptyMap(),
                            onClick = {},
                            onLongClick = { _, _ -> },
                            onMore = { _, _ -> },
                            onFocused = { focusedId.set(it) },
                            modifier = Modifier,
                            rowRequester = rowRequester,
                            upRequester = FocusRequester(),
                            downRequester = FocusRequester(),
                            landscapeCards = false,
                            pivotOffset = 48.dp,
                            artworkPrefetchDispatcher = ArtworkPrefetchDispatcher({}, {}, {})
                        )
                    }
                }
            }
        }

        compose.runOnIdle { rowRequester.requestFocus() }
        val indicator = compose.onNodeWithTag("search-shelf-focus-indicator")
        indicator.assertLeftPositionInRootIsEqualTo(48.dp)
        repeat(5) {
            val currentId = requireNotNull(focusedId.get())
            val currentItem = requireNotNull(items.firstOrNull { it.canonicalPresentationKey() == currentId })
            compose.onNode(hasClickAction() and hasContentDescription(currentItem.name))
                .performKeyInput { pressKey(Key.DirectionRight) }
            compose.waitForIdle()
        }

        compose.mainClock.advanceTimeBy(500)
        val selected = requireNotNull(items.firstOrNull { it.canonicalPresentationKey() == focusedId.get() })
        assertEquals(items[5].id, selected.id)
        val focusedCard = compose.onNode(hasClickAction() and hasContentDescription(selected.name))
        indicator.assertLeftPositionInRootIsEqualTo(48.dp)
        focusedCard.assertLeftPositionInRootIsEqualTo(48.dp)
    }

    @Test fun trailingViewAllFocusFrameStaysAlignedWhenTheScrollableShelfReachesItsEnd() {
        val items = (0..11).map { MetaItem("tt-edge-$it", "movie", "Edge $it") }
        val rowRequester = FocusRequester()
        var focusedIndex = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalTitleCardShape provides "poster") {
                    androidx.compose.foundation.layout.Box(Modifier.size(420.dp, 260.dp)) {
                        SearchResultRow(
                            title = "Scrollable Edge",
                            items = items,
                            posterHeight = 180.dp,
                            targetKey = null,
                            targetRequester = FocusRequester(),
                            keyboardRequester = FocusRequester(),
                            drawerRequester = FocusRequester(),
                            moreRequester = FocusRequester(),
                            lastFocusedId = null,
                            watchedIds = emptySet(),
                            enrichedItems = emptyMap(),
                            onClick = {},
                            onLongClick = { _, _ -> },
                            onMore = { _, _ -> },
                            onFocused = { key ->
                                focusedIndex = items.indexOfFirst { it.canonicalPresentationKey() == key }
                                    .takeIf { it >= 0 } ?: focusedIndex
                            },
                            modifier = Modifier,
                            rowRequester = rowRequester,
                            upRequester = FocusRequester(),
                            downRequester = FocusRequester(),
                            landscapeCards = false,
                            pivotOffset = 48.dp,
                            artworkPrefetchDispatcher = ArtworkPrefetchDispatcher({}, {}, {})
                        )
                    }
                }
            }
        }

        compose.runOnIdle { rowRequester.requestFocus() }
        repeat(items.lastIndex) {
            val selected = items[focusedIndex]
            compose.onNode(hasClickAction() and hasContentDescription(selected.name))
                .performKeyInput { pressKey(Key.DirectionRight) }
            compose.waitForIdle()
        }
        compose.onNode(hasClickAction() and hasContentDescription(items.last().name))
            .performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()

        val viewAll = compose.onNode(hasClickAction() and hasText("View All"))
        viewAll.assertIsFocused()
        val indicator = compose.onNodeWithTag("search-shelf-focus-indicator")
        compose.runOnIdle {
            assertEquals(
                "the focus frame follows the final clamped action card",
                viewAll.fetchSemanticsNode().boundsInRoot.left,
                indicator.fetchSemanticsNode().boundsInRoot.left,
                0.5f
            )
        }
    }

    @Test fun portraitResultsUseTheHomeShelfPivotAndFollowTheCardAtTheContentEdge() = assertSearchShelfFrame(
        landscape = false,
        expectedWidth = 120.dp,
        expectedHeight = 180.dp
    )

    @Test fun landscapeResultsUseTheHomeShelfPivotAndFollowTheCardAtTheContentEdge() = assertSearchShelfFrame(
        landscape = true,
        expectedWidth = 190.dp,
        expectedHeight = 106.875.dp
    )

    @Test fun searchFocusRingClearsWhenFocusLeavesTheCardAndTheRow() = assertSearchShelfFrame(
        landscape = false,
        expectedWidth = 120.dp,
        expectedHeight = 180.dp,
        verifyFocusExit = true
    )

    @Test fun viewMoreIsTheLastShelfCardAndOpensTheWholeResultSet() {
        val moreRequester = FocusRequester()
        val items = listOf(
            MetaItem("tt-search-one", "movie", "One"),
            MetaItem("tt-search-two", "movie", "Two")
        )
        var openedTitle: String? = null
        var openedItems: List<MetaItem> = emptyList()
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalTitleCardShape provides "poster") {
                    SearchResultRow(
                        title = "Películas",
                        items = items,
                        posterHeight = 180.dp,
                        targetKey = null,
                        targetRequester = FocusRequester(),
                        keyboardRequester = FocusRequester(),
                        drawerRequester = FocusRequester(),
                        moreRequester = moreRequester,
                        lastFocusedId = null,
                        watchedIds = emptySet(),
                        enrichedItems = emptyMap(),
                        onClick = {},
                        onLongClick = { _, _ -> },
                        onMore = { title, results -> openedTitle = title; openedItems = results },
                        onFocused = {},
                        modifier = Modifier,
                        rowRequester = FocusRequester(),
                        upRequester = FocusRequester(),
                        downRequester = FocusRequester(),
                        landscapeCards = false,
                        pivotOffset = 48.dp,
                        viewMoreTitle = "Movies",
                        artworkPrefetchDispatcher = ArtworkPrefetchDispatcher({}, {}, {})
                    )
                }
            }
        }

        compose.onNode(hasText("View All")).assertExists()
        compose.runOnIdle { moreRequester.requestFocus() }
        compose.onNode(hasClickAction() and hasText("View All")).performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        compose.runOnIdle {
            assertEquals("Movies", openedTitle)
            assertEquals(items, openedItems)
        }
    }

    @Test fun focusFrameGlidesToASelectionInAShortNonScrollableShelf() = assertSearchShelfFrame(
        landscape = false,
        expectedWidth = 120.dp,
        expectedHeight = 180.dp,
        verifySmoothFocus = true
    )

    private fun assertSearchShelfFrame(
        landscape: Boolean,
        expectedWidth: androidx.compose.ui.unit.Dp,
        expectedHeight: androidx.compose.ui.unit.Dp,
        verifyFocusExit: Boolean = false,
        verifySmoothFocus: Boolean = false
    ) {
        val rowRequester = FocusRequester()
        val moreRequester = FocusRequester()
        val lastRequester = FocusRequester()
        val exitRequester = FocusRequester()
        val result = MetaItem(id = "tt-search-focus", type = "movie", name = "Search result")
        val middle = MetaItem(id = "tt-search-middle", type = "movie", name = "Middle result")
        val last = MetaItem(id = "tt-search-last", type = "movie", name = "Last result")
        var focusedId: String? = null
        compose.setContent {
            MaterialTheme {
                Column {
                    CompositionLocalProvider(LocalTitleCardShape provides if (landscape) "landscape" else "poster") {
                        SearchResultRow(
                            title = "Search",
                            items = listOf(result, middle, last),
                            posterHeight = 180.dp,
                            targetKey = last.canonicalPresentationKey(),
                            targetRequester = lastRequester,
                            keyboardRequester = FocusRequester(),
                            drawerRequester = FocusRequester(),
                            moreRequester = moreRequester,
                            lastFocusedId = null,
                            watchedIds = emptySet(),
                            enrichedItems = emptyMap(),
                            onClick = {},
                            onLongClick = { _, _ -> },
                            onMore = { _, _ -> },
                            onFocused = { focusedId = it },
                            modifier = Modifier,
                            rowRequester = rowRequester,
                            upRequester = FocusRequester(),
                            downRequester = FocusRequester(),
                            landscapeCards = landscape,
                            pivotOffset = 48.dp,
                            artworkPrefetchDispatcher = ArtworkPrefetchDispatcher({}, {}, {})
                        )
                    }
                    if (verifyFocusExit) {
                        Box(Modifier.size(1.dp).focusRequester(exitRequester).focusable())
                    }
                }
            }
        }

        val indicator = compose.onNodeWithTag("search-shelf-focus-indicator")
        val firstCard = compose.onNode(hasClickAction() and hasContentDescription("Search result"))
        indicator.assertWidthIsEqualTo(expectedWidth)
        indicator.assertHeightIsEqualTo(expectedHeight)
        firstCard.assertWidthIsEqualTo(expectedWidth)
        firstCard.assertHeightIsEqualTo(expectedHeight)
        indicator.assertLeftPositionInRootIsEqualTo(48.dp)
        firstCard.assertLeftPositionInRootIsEqualTo(48.dp)
        indicator.assert(SemanticsMatcher.expectValue(ShelfFocusIndicatorActive, false))

        compose.runOnIdle { rowRequester.requestFocus() }
        compose.waitForIdle()
        indicator.assert(SemanticsMatcher.expectValue(ShelfFocusIndicatorActive, true))
        compose.runOnIdle { assertEquals(result.canonicalPresentationKey(), focusedId) }

        val lastCard = compose.onNode(hasClickAction() and hasContentDescription("Last result"))
        if (verifySmoothFocus) {
            val startLeft = indicator.fetchSemanticsNode().boundsInRoot.left
            val targetLeft = lastCard.fetchSemanticsNode().boundsInRoot.left
            compose.mainClock.autoAdvance = false
            try {
                compose.runOnIdle { lastRequester.requestFocus() }
                compose.waitForIdle()
                compose.mainClock.advanceTimeBy(48)
                val inFlightLeft = indicator.fetchSemanticsNode().boundsInRoot.left
                assertTrue("focus frame should move toward, but not jump to, the card", inFlightLeft > startLeft + 1f)
                assertTrue("focus frame should still be travelling toward the card", inFlightLeft < targetLeft - 1f)
                compose.mainClock.advanceTimeBy(600)
            } finally {
                compose.mainClock.autoAdvance = true
            }
            compose.waitForIdle()
        } else {
            compose.runOnIdle { lastRequester.requestFocus() }
            compose.waitForIdle()
        }
        compose.runOnIdle {
            val cardLeft = lastCard.fetchSemanticsNode().boundsInRoot.left
            val frameLeft = indicator.fetchSemanticsNode().boundsInRoot.left
            assertEquals(cardLeft, frameLeft, 0.5f)
            assert(cardLeft > with(compose.density) { 48.dp.toPx() })
        }

        if (verifyFocusExit) {
            compose.runOnIdle { moreRequester.requestFocus() }
            compose.waitForIdle()
            indicator.assert(SemanticsMatcher.expectValue(ShelfFocusIndicatorActive, true))
            val viewMoreCard = compose.onNode(hasClickAction() and hasText("View All"))
            assertEquals(
                viewMoreCard.fetchSemanticsNode().boundsInRoot.left,
                indicator.fetchSemanticsNode().boundsInRoot.left,
                0.5f
            )

            compose.runOnIdle { rowRequester.requestFocus() }
            compose.waitForIdle()
            indicator.assert(SemanticsMatcher.expectValue(ShelfFocusIndicatorActive, true))

            compose.runOnIdle { exitRequester.requestFocus() }
            compose.waitForIdle()
            indicator.assert(SemanticsMatcher.expectValue(ShelfFocusIndicatorActive, false))
        }
    }
}
