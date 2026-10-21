package com.saab.tv.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.focusable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.CategoryRow
import com.saab.tv.domain.schema.CatalogCardProjection
import com.saab.tv.testfixture.ComposeTestActivity
import com.saab.tv.ui.components.ArtworkPrefetchDispatcher
import com.saab.tv.ui.components.LocalTitleCardShape
import com.saab.tv.ui.components.ShelfFocusIndicatorActive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A real Android D-pad journey for the shared browse-shelf focus pivot. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class BrowseShelfFocusTvJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestActivity>()

    @Test fun freshLeftPressAtFirstCardEntersNavigationWithoutWaitingForATimer() {
        val drawer = FocusRequester()
        val rowEntry = FocusRequester()
        val items = listOf(MetaItem(id = "boundary-0", type = "movie", name = "Boundary title"))

        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.size(1.dp).focusRequester(drawer).focusable()
                            .semantics { contentDescription = "Boundary menu" }
                    )
                    InfiniteLoopRow(
                        startPadding = 48.dp,
                        isTopNav = false,
                        rowIndex = 0,
                        title = "Boundary focus",
                        items = items,
                        onMovieClick = {},
                        onViewMore = {},
                        onFocused = { _, _ -> },
                        entryRequester = rowEntry,
                        drawerRequester = drawer,
                        locallyFocusedItemId = null,
                        isGlobalFocusPresent = true,
                        isFirstRow = true,
                        isInfiniteLoopEnabled = false,
                        repeatGate = remember { DpadRepeatGate() },
                        artworkPrefetchDispatcher = remember { ArtworkPrefetchDispatcher({}, {}, {}) }
                    )
                }
            }
        }

        compose.onNode(hasClickAction() and hasContentDescription("Boundary title"))
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNode(hasContentDescription("Boundary menu")).assertIsFocused()
    }

    @Test fun freshRightPressAfterEnteringViewMoreAdvancesImmediately() {
        val drawer = FocusRequester()
        val rowEntry = FocusRequester()
        val items = (0..4).map { index ->
            MetaItem(id = "view-more-$index", type = "movie", name = "View More shelf title $index")
        }

        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.size(1.dp).focusRequester(drawer).focusable())
                    InfiniteLoopRow(
                        startPadding = 48.dp,
                        isTopNav = false,
                        rowIndex = 0,
                        title = "View More focus",
                        items = items,
                        onMovieClick = {},
                        onViewMore = {},
                        onFocused = { _, _ -> },
                        entryRequester = rowEntry,
                        drawerRequester = drawer,
                        locallyFocusedItemId = null,
                        isGlobalFocusPresent = false,
                        isFirstRow = true,
                        isInfiniteLoopEnabled = true,
                        repeatGate = remember { DpadRepeatGate() },
                        artworkPrefetchDispatcher = remember { ArtworkPrefetchDispatcher({}, {}, {}) }
                    )
                }
            }
        }

        val root = compose.onRoot()
        // The infinite shelf renders the first logical item in multiple generations.
        val firstCard = compose.onAllNodes(
            hasClickAction() and hasContentDescription("View More shelf title 0")
        )[0]
        firstCard.performSemanticsAction(SemanticsActions.RequestFocus)
        firstCard.assertIsFocused()
        repeat(5) {
            root.performKeyInput { pressKey(Key.DirectionRight) }
            compose.waitForIdle()
        }
        val viewMore = hasClickAction() and hasText("View More")
        assertAnyMatchingNodeFocused(viewMore)

        root.performKeyInput { pressKey(Key.DirectionLeft) }
        assertAnyMatchingNodeFocused(hasClickAction() and hasContentDescription("View More shelf title 4"))
        root.performKeyInput { pressKey(Key.DirectionRight) }
        assertAnyMatchingNodeFocused(viewMore)

        root.performKeyInput { pressKey(Key.DirectionRight) }

        val nextLoopCard = hasClickAction() and hasContentDescription("View More shelf title 0")
        compose.waitUntil(5_000) {
            compose.onAllNodes(nextLoopCard).fetchSemanticsNodes().any { node ->
                node.config.contains(SemanticsProperties.Focused) && node.config[SemanticsProperties.Focused]
            }
        }
    }

    private fun assertAnyMatchingNodeFocused(matcher: SemanticsMatcher) {
        compose.waitUntil(5_000) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().any { node ->
                node.config.contains(SemanticsProperties.Focused) && node.config[SemanticsProperties.Focused]
            }
        }
    }

    @Test fun rightMovesTheShelfUnderTheFixedFocusFrameWithoutLosingSelection() {
        val items = (0..8).map { index ->
            MetaItem(id = "focus-$index", type = "movie", name = "Shelf title $index")
        }

        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    InfiniteLoopRow(
                        startPadding = 48.dp,
                        isTopNav = false,
                        rowIndex = 0,
                        title = "Focus journey",
                        items = items,
                        onMovieClick = {},
                        onViewMore = {},
                        onFocused = { _, _ -> },
                        entryRequester = remember { FocusRequester() },
                        drawerRequester = remember { FocusRequester() },
                        locallyFocusedItemId = null,
                        isGlobalFocusPresent = false,
                        isFirstRow = true,
                        isInfiniteLoopEnabled = false,
                        repeatGate = remember { DpadRepeatGate() },
                        artworkPrefetchDispatcher = remember { ArtworkPrefetchDispatcher({}, {}, {}) }
                    )
                }
            }
        }

        val focusFrame = compose.onNodeWithTag("shelf-focus-indicator")
        val firstCard = compose.onNode(hasClickAction() and hasContentDescription("Shelf title 0"))
        firstCard.performSemanticsAction(SemanticsActions.RequestFocus)
        firstCard.assertIsFocused()

        val root = compose.onRoot()
        repeat(3) { root.performKeyInput { pressKey(Key.DirectionRight) } }
        val thirdCardMatcher = hasClickAction() and hasContentDescription("Shelf title 3")
        compose.waitUntil(5_000) {
            compose.onAllNodes(thirdCardMatcher).fetchSemanticsNodes()
                .any { node ->
                    node.config.contains(SemanticsProperties.Focused) && node.config[SemanticsProperties.Focused]
                }
        }
        val thirdCard = compose.onNode(thirdCardMatcher)
        thirdCard.assertIsFocused()
        val cardLeft = thirdCard.fetchSemanticsNode().boundsInRoot.left
        val frameLeft = focusFrame.fetchSemanticsNode().boundsInRoot.left
        assertEquals("selected card remains aligned with the fixed shelf pivot", frameLeft, cardLeft, 1f)

        // Rapidly reverse direction while the shelf is moving. The latest key
        // direction should retarget the same scroll instead of leaving the card
        // or focus frame stranded between destinations.
        root.performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionLeft)
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionLeft)
        }
        compose.waitForIdle()
        thirdCard.assertIsFocused()
        assertEquals(
            "rapid reversals settle back on the selected card at the fixed pivot",
            thirdCard.fetchSemanticsNode().boundsInRoot.left,
            focusFrame.fetchSemanticsNode().boundsInRoot.left,
            1f
        )

        // At the finite rail edge there is no remaining content to slide beneath
        // the pivot, so the indicator must follow the selected final card instead.
        repeat(5) { root.performKeyInput { pressKey(Key.DirectionRight) } }
        val lastCardMatcher = hasClickAction() and hasContentDescription("Shelf title 8")
        compose.waitUntil(5_000) {
            compose.onAllNodes(lastCardMatcher).fetchSemanticsNodes()
                .any { node ->
                    node.config.contains(SemanticsProperties.Focused) && node.config[SemanticsProperties.Focused]
                }
        }
        compose.waitForIdle()
        val lastCard = compose.onNode(lastCardMatcher)
        lastCard.assertIsFocused()
        val lastCardLeft = lastCard.fetchSemanticsNode().boundsInRoot.left
        val edgeFrameLeft = focusFrame.fetchSemanticsNode().boundsInRoot.left
        assertEquals("focus frame follows the final card at the rail boundary", lastCardLeft, edgeFrameLeft, 1f)
    }

    @Test fun landscapeMovieAndSeriesCardsKeepEqualGeometryAndAlignedFocusFrame() {
        val items = listOf(
            MetaItem("movie-landscape", "movie", "Landscape Movie", background = "movie-backdrop"),
            MetaItem("series-landscape", "series", "Landscape Series", background = "series-backdrop")
        )

        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalTitleCardShape provides "landscape") {
                    Box(Modifier.fillMaxSize()) {
                        InfiniteLoopRow(
                            startPadding = 48.dp,
                            isTopNav = false,
                            rowIndex = 0,
                            title = "Landscape geometry",
                            items = items,
                            onMovieClick = {},
                            onViewMore = {},
                            onFocused = { _, _ -> },
                            entryRequester = remember { FocusRequester() },
                            drawerRequester = remember { FocusRequester() },
                            locallyFocusedItemId = null,
                            isGlobalFocusPresent = false,
                            isFirstRow = true,
                            isInfiniteLoopEnabled = false,
                            isLandscapeCards = true,
                            repeatGate = remember { DpadRepeatGate() },
                            artworkPrefetchDispatcher = remember { ArtworkPrefetchDispatcher({}, {}, {}) }
                        )
                    }
                }
            }
        }

        val movie = compose.onNode(hasClickAction() and hasContentDescription("Landscape Movie"))
        val series = compose.onNode(hasClickAction() and hasContentDescription("Landscape Series"))
        val focusFrame = compose.onNodeWithTag("shelf-focus-indicator")
        val movieBounds = movie.fetchSemanticsNode().boundsInRoot
        val seriesBounds = series.fetchSemanticsNode().boundsInRoot
        assertEquals("movie and series cards share the same height", movieBounds.height, seriesBounds.height, 1f)
        assertEquals("shared focus frame matches the card height", movieBounds.height,
            focusFrame.fetchSemanticsNode().boundsInRoot.height, 1f)

        movie.performSemanticsAction(SemanticsActions.RequestFocus)
        movie.assertIsFocused()
        compose.waitUntil(3_000) {
            focusFrame.fetchSemanticsNode().config[ShelfFocusIndicatorActive]
        }
        compose.waitForIdle()
        assertEquals(movie.fetchSemanticsNode().boundsInRoot.left, focusFrame.fetchSemanticsNode().boundsInRoot.left, 1f)
        assertEquals(movie.fetchSemanticsNode().boundsInRoot.top, focusFrame.fetchSemanticsNode().boundsInRoot.top, 1f)

        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitUntil(3_000) {
            runCatching { series.assertIsFocused(); true }.getOrDefault(false)
        }
        compose.waitForIdle()
        assertEquals(series.fetchSemanticsNode().boundsInRoot.left, focusFrame.fetchSemanticsNode().boundsInRoot.left, 1f)
        assertEquals(series.fetchSemanticsNode().boundsInRoot.top, focusFrame.fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test fun verticalRailChangesKeepFocusOnContentAndMoveTheFocusFrameWithTheActiveRail() {
        val drawerRequester = FocusRequester()
        val firstRowRequester = FocusRequester()
        val secondRowRequester = FocusRequester()
        val firstItems = (0..6).map { index ->
            MetaItem(id = "first-$index", type = "movie", name = "First shelf title $index")
        }
        val secondItems = (0..6).map { index ->
            MetaItem(id = "second-$index", type = "movie", name = "Second shelf title $index")
        }

        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.size(1.dp).focusRequester(drawerRequester).focusable()
                            .semantics { contentDescription = "Menu" }
                    )
                    InfiniteLoopRow(
                        startPadding = 48.dp,
                        isTopNav = false,
                        rowIndex = 0,
                        title = "First shelf",
                        items = firstItems,
                        onMovieClick = {},
                        onViewMore = {},
                        onFocused = { _, _ -> },
                        entryRequester = firstRowRequester,
                        drawerRequester = drawerRequester,
                        locallyFocusedItemId = null,
                        isGlobalFocusPresent = true,
                        isFirstRow = true,
                        isInfiniteLoopEnabled = false,
                        repeatGate = remember { DpadRepeatGate() },
                        artworkPrefetchDispatcher = remember { ArtworkPrefetchDispatcher({}, {}, {}) },
                        verticalEntryRequester = firstRowRequester,
                        downShelfRequester = secondRowRequester
                    )
                    InfiniteLoopRow(
                        startPadding = 48.dp,
                        isTopNav = false,
                        rowIndex = 1,
                        title = "Second shelf",
                        items = secondItems,
                        onMovieClick = {},
                        onViewMore = {},
                        onFocused = { _, _ -> },
                        entryRequester = secondRowRequester,
                        drawerRequester = drawerRequester,
                        locallyFocusedItemId = null,
                        isGlobalFocusPresent = true,
                        isFirstRow = false,
                        isInfiniteLoopEnabled = false,
                        repeatGate = remember { DpadRepeatGate() },
                        artworkPrefetchDispatcher = remember { ArtworkPrefetchDispatcher({}, {}, {}) },
                        verticalEntryRequester = secondRowRequester,
                        upShelfRequester = firstRowRequester
                    )
                }
            }
        }

        fun focusedCard(prefix: String) = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .firstOrNull { node ->
                node.config.contains(SemanticsProperties.ContentDescription) &&
                    node.config[SemanticsProperties.ContentDescription].any { it.startsWith(prefix) } &&
                    node.config.contains(SemanticsProperties.Focused) &&
                    node.config[SemanticsProperties.Focused]
            }

        val root = compose.onRoot()
        val firstCard = compose.onNode(hasClickAction() and hasContentDescription("First shelf title 0"))
        firstCard.performSemanticsAction(SemanticsActions.RequestFocus)
        firstCard.assertIsFocused()
        root.performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        root.performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.waitUntil(5_000) { focusedCard("First shelf title") != null }
        val selectedBeforeRailChange = requireNotNull(focusedCard("First shelf title"))
            .config[SemanticsProperties.ContentDescription].first()

        root.performKeyInput { pressKey(Key.DirectionDown) }
        try {
            compose.waitUntil(5_000) { focusedCard("Second shelf title") != null }
        } catch (failure: Throwable) {
            val currentFocus = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
                .filter { it.config.contains(SemanticsProperties.Focused) && it.config[SemanticsProperties.Focused] }
                .map { node ->
                    val description = if (node.config.contains(SemanticsProperties.ContentDescription)) {
                        node.config[SemanticsProperties.ContentDescription].joinToString()
                    } else "<no description>"
                    "$description @ ${node.boundsInRoot}"
                }
            throw AssertionError("D-pad Down did not enter next shelf; focused=$currentFocus", failure)
        }
        val secondFocused = requireNotNull(focusedCard("Second shelf title"))
        val activeFrame = compose.onAllNodesWithTag("shelf-focus-indicator").fetchSemanticsNodes()
            .single { it.config[ShelfFocusIndicatorActive] }
        assertEquals("active rail indicator stays aligned with its focused card", secondFocused.boundsInRoot.left, activeFrame.boundsInRoot.left, 1f)
        val menu = compose.onNode(hasContentDescription("Menu"))
        menu.assertIsNotFocused()

        root.performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitUntil(5_000) { focusedCard("First shelf title") != null }
        val firstFocused = requireNotNull(focusedCard("First shelf title"))
        assertEquals("vertical navigation restores the original card", selectedBeforeRailChange,
            firstFocused.config[SemanticsProperties.ContentDescription].first())
        val restoredFrame = compose.onAllNodesWithTag("shelf-focus-indicator").fetchSemanticsNodes()
            .single { it.config[ShelfFocusIndicatorActive] }
        assertEquals("returning to the previous rail restores its focus frame", firstFocused.boundsInRoot.left, restoredFrame.boundsInRoot.left, 1f)
        menu.assertIsNotFocused()
    }

    @Test fun lateRailInsertionKeepsTheFocusedTitleAndItsStableSelection() {
        val selectedTitle = MetaItem("tt-selected", "movie", "Selected title")
        val rows = mutableStateOf(listOf(category("catalog", selectedTitle)))
        val savedFocusAddress = mutableStateOf<String?>(null)
        val drawer = FocusRequester()

        compose.setContent {
            MaterialTheme {
                val restoredAddress = resolveEffectiveFocusKey(savedFocusAddress.value, emptyList(), rows.value)
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(rows.value, key = { _, row -> row.id }) { index, row ->
                        val entry = remember(row.id) { FocusRequester() }
                        InfiniteLoopRow(
                            startPadding = 48.dp,
                            isTopNav = false,
                            rowIndex = index,
                            focusRowId = row.id,
                            title = row.title,
                            items = row.items.map(CatalogCardProjection::asActionItem),
                            onMovieClick = {},
                            onViewMore = {},
                            onFocused = { _, address -> savedFocusAddress.value = address },
                            entryRequester = entry,
                            drawerRequester = drawer,
                            locallyFocusedItemId = restoredAddress?.takeIf { it.startsWith("${index}_") },
                            isGlobalFocusPresent = restoredAddress != null,
                            isFirstRow = index == 0 && restoredAddress == null,
                            repeatGate = remember(row.id) { DpadRepeatGate() },
                            artworkPrefetchDispatcher = remember {
                                ArtworkPrefetchDispatcher({}, {}, {})
                            }
                        )
                    }
                }
            }
        }

        val selected = compose.onNode(hasClickAction() and hasContentDescription("Selected title"))
        selected.performSemanticsAction(SemanticsActions.RequestFocus)
        selected.assertIsFocused()
        val originalAddress = compose.runOnIdle { savedFocusAddress.value }

        compose.runOnIdle {
            rows.value = listOf(
                category("late-recommendations", MetaItem("tt-late", "movie", "Late recommendation")),
                category("catalog", selectedTitle)
            )
        }

        compose.waitUntil(5_000) {
            runCatching { selected.assertIsFocused(); true }.getOrDefault(false)
        }
        compose.runOnIdle {
            assertEquals("0_tt-selected_0@catalog", originalAddress)
            assertEquals(
                "1_tt-selected_0@catalog",
                resolveEffectiveFocusKey(savedFocusAddress.value, emptyList(), rows.value)
            )
        }
    }

    private fun category(id: String, vararg items: MetaItem) = CategoryRow(
        id = id,
        title = id,
        order = 0,
        items = items.map(CatalogCardProjection::from)
    )
}
