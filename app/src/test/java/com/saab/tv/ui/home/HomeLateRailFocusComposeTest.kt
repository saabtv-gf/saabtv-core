package com.saab.tv.ui.home

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.CategoryRow
import com.saab.tv.domain.schema.CatalogCardProjection
import com.saab.tv.ui.components.ArtworkPrefetchDispatcher
import com.saab.tv.ui.components.ShelfFocusIndicatorActive
import com.saab.tv.ui.utils.requestFocusSafely
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the real lazy-row focus nodes when asynchronous rails are inserted above them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class HomeLateRailFocusComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun repeatingTmdbShelfRestoresCanonicalTitleAndRemainsNavigable() =
        assertSavedTitleRestores(repeating = true, savedIndex = 1)

    @Test fun finiteTmdbShelfRestoresTitleBeyondTheSavedViewport() =
        assertSavedTitleRestores(repeating = false, savedIndex = 14)

    private fun assertSavedTitleRestores(repeating: Boolean, savedIndex: Int) {
        val titles = (0..14).map { MetaItem("tmdb:${100 + it}", "movie", "TMDB title $it") }
        val entry = FocusRequester()
        val listState = LazyListState(firstVisibleItemIndex = 0)
        val savedAddress = homeCardFocusKey(0, "tmdb-top-suggestions", titles[savedIndex].homeFocusIdentityKey(), savedIndex)
        compose.setContent {
            MaterialTheme {
                InfiniteLoopRow(
                    startPadding = 48.dp, isTopNav = false, rowIndex = 0,
                    focusRowId = "tmdb-top-suggestions", title = "Top Suggestions", items = titles,
                    onMovieClick = {}, onViewMore = {}, onFocused = { _, _ -> },
                    entryRequester = entry, drawerRequester = remember { FocusRequester() },
                    locallyFocusedItemId = savedAddress, isGlobalFocusPresent = true, isFirstRow = true,
                    isInfiniteLoopEnabled = true, isInfiniteScrollingEnabled = repeating,
                    visibleItemCount = 15, externalListState = listState,
                    repeatGate = remember { DpadRepeatGate() },
                    artworkPrefetchDispatcher = remember { ArtworkPrefetchDispatcher({}, {}, {}) }
                )
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("saved card must have an attached entry requester", entry.requestFocusSafely()) }
        val target = compose.onNode(hasClickAction() and hasContentDescription("TMDB title $savedIndex"))
        target.assertIsFocused()
        target.performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        compose.onNode(hasClickAction() and hasContentDescription("TMDB title ${savedIndex - 1}")).assertIsFocused()
    }

    @Test fun addingRecommendationRailAboveFocusedShelfKeepsFocusedTitleAndFocusNode() {
        val target = MetaItem("tt-target", "movie", "Focused title")
        val rows = mutableStateOf(listOf(row("existing", target)))
        val savedAddress = mutableStateOf<String?>(null)

        compose.setContent {
            MaterialTheme {
                val effectiveAddress = resolveEffectiveFocusKey(savedAddress.value, emptyList(), rows.value)
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(rows.value, key = { _, row -> row.id }) { index, row ->
                        val entryRequester = remember(row.id) { FocusRequester() }
                        InfiniteLoopRow(
                            startPadding = 48.dp,
                            isTopNav = false,
                            rowIndex = index,
                            focusRowId = row.id,
                            title = row.title,
                            items = row.items.map(CatalogCardProjection::asActionItem),
                            onMovieClick = {},
                            onViewMore = {},
                            onFocused = { _, address -> savedAddress.value = address },
                            entryRequester = entryRequester,
                            drawerRequester = remember { FocusRequester() },
                            locallyFocusedItemId = effectiveAddress?.takeIf { it.startsWith("${index}_") },
                            isGlobalFocusPresent = effectiveAddress != null,
                            isFirstRow = index == 0 && effectiveAddress == null,
                            repeatGate = remember { DpadRepeatGate() },
                            artworkPrefetchDispatcher = remember {
                                ArtworkPrefetchDispatcher({}, {}, {})
                            }
                        )
                    }
                }
            }
        }

        val focusedCard = compose.onNode(hasClickAction() and hasContentDescription("Focused title"))
        focusedCard.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        focusedCard.assertIsFocused()

        val addressBeforeInsertion = compose.runOnIdle { savedAddress.value }
        compose.onAllNodes(
            SemanticsMatcher.expectValue(ShelfFocusIndicatorActive, true)
        ).assertCountEquals(1)
        compose.runOnIdle {
            rows.value = listOf(
                row("late-recommendations", MetaItem("tt-late", "movie", "Late recommendation")),
                row("existing", target)
            )
        }
        compose.waitForIdle()

        focusedCard.assertIsFocused()
        compose.onAllNodes(
            SemanticsMatcher.expectValue(ShelfFocusIndicatorActive, true)
        ).assertCountEquals(1)
        compose.runOnIdle {
            assertEquals("0_tt-target_0@existing", addressBeforeInsertion)
            assertEquals("1_tt-target_0@existing", resolveEffectiveFocusKey(addressBeforeInsertion, emptyList(), rows.value))
        }
    }

    @Test fun restoredCatalogFocusKeepsSavedScrollUntilTheNextDpadMove() {
        val items = (0..15).map { index -> MetaItem("title-$index", "movie", "Title $index") }
        val listState = LazyListState(firstVisibleItemIndex = 2)
        val restoredFocus = mutableStateOf<String?>(homeCardFocusKey(0, "shelf", items[5].id, 5))
        val rowEntry = FocusRequester()
        val drawer = FocusRequester()
        val repeatGate = DpadRepeatGate()
        val artworkDispatcher = ArtworkPrefetchDispatcher({}, {}, {})
        compose.mainClock.autoAdvance = false

        compose.setContent {
            MaterialTheme {
                InfiniteLoopRow(
                    startPadding = 48.dp,
                    isTopNav = false,
                    rowIndex = 0,
                    focusRowId = "shelf",
                    title = "Restored shelf",
                    items = items,
                    onMovieClick = {},
                    onViewMore = {},
                    onFocused = { _, address -> restoredFocus.value = address },
                    entryRequester = rowEntry,
                    drawerRequester = drawer,
                    locallyFocusedItemId = restoredFocus.value,
                    isGlobalFocusPresent = restoredFocus.value != null,
                    isFirstRow = true,
                    isInfiniteLoopEnabled = false,
                    externalListState = listState,
                    repeatGate = repeatGate,
                    artworkPrefetchDispatcher = artworkDispatcher
                )
            }
        }

        compose.mainClock.advanceTimeByFrame()
        val restoredScrollPosition = compose.runOnIdle {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
        compose.runOnIdle {
            assertEquals("the injected saved scroll is present before focus restoration", 2, listState.firstVisibleItemIndex)
        }

        val restoredCard = compose.onNode(hasClickAction() and hasContentDescription("Title 5"))
        restoredCard.performSemanticsAction(SemanticsActions.RequestFocus)
        restoredCard.assertIsFocused()
        compose.runOnIdle {
            assertEquals("restore must not move the saved shelf position", 2, listState.firstVisibleItemIndex)
        }

        restoredCard.performKeyInput { pressKey(Key.DirectionRight) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNode(hasClickAction() and hasContentDescription("Title 6")).assertIsFocused()
        compose.runOnIdle {
            assertNotEquals(
                "the first user move resumes pivot scrolling",
                restoredScrollPosition,
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            )
        }
    }

    @Test fun posterShelfCanMoveFocusThroughItsLastCards() = assertFinalCardReachable(landscape = false)

    @Test fun landscapeShelfCanMoveFocusThroughItsLastCards() = assertFinalCardReachable(landscape = true)

    @Test fun viewMoreLeftMovesDirectlyToTheAdjacentLastTitle() {
        val items = (0..4).map { index -> MetaItem("view-more-$index", "movie", "View title $index") }
        compose.setContent {
            MaterialTheme {
                InfiniteLoopRow(
                    startPadding = 48.dp,
                    isTopNav = false,
                    rowIndex = 0,
                    focusRowId = "view-more-left",
                    title = "View More boundary",
                    items = items,
                    onMovieClick = {},
                    onViewMore = {},
                    onFocused = { _, _ -> },
                    entryRequester = remember { FocusRequester() },
                    drawerRequester = remember { FocusRequester() },
                    locallyFocusedItemId = null,
                    isGlobalFocusPresent = false,
                    isFirstRow = true,
                    isInfiniteLoopEnabled = true,
                    visibleItemCount = items.size,
                    isInfiniteScrollingEnabled = false,
                    repeatGate = remember { DpadRepeatGate() },
                    artworkPrefetchDispatcher = remember { ArtworkPrefetchDispatcher({}, {}, {}) }
                )
            }
        }

        val lastTitle = compose.onNode(hasClickAction() and hasContentDescription("View title 4"))
        lastTitle.performSemanticsAction(SemanticsActions.RequestFocus)
        lastTitle.performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        val viewMore = compose.onNode(hasClickAction() and hasText("View More"))
        viewMore.assertIsFocused()
        viewMore.performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        compose.onNode(hasClickAction() and hasContentDescription("View title 4")).assertIsFocused()
    }

    private fun assertFinalCardReachable(landscape: Boolean) {
        // Match recommendation/catalog rails: grid enabled, finite scroll, 15
        // visible cards followed by View All. The final five poster/final three
        // landscape slots are the region that was intermittently unreachable.
        val items = (0..17).map { index -> MetaItem("end-$index", "movie", "End title $index") }
        compose.setContent {
            MaterialTheme {
                InfiniteLoopRow(
                    startPadding = 48.dp,
                    isTopNav = false,
                    rowIndex = 0,
                    focusRowId = if (landscape) "landscape-end" else "poster-end",
                    title = "Late-row focus",
                    items = items,
                    onMovieClick = {},
                    onViewMore = {},
                    onFocused = { _, _ -> },
                    entryRequester = remember { FocusRequester() },
                    drawerRequester = remember { FocusRequester() },
                    locallyFocusedItemId = null,
                    isGlobalFocusPresent = false,
                    isFirstRow = true,
                    isInfiniteLoopEnabled = true,
                    visibleItemCount = 15,
                    isInfiniteScrollingEnabled = false,
                    repeatGate = remember { DpadRepeatGate() },
                    isLandscapeCards = landscape,
                    artworkPrefetchDispatcher = remember { ArtworkPrefetchDispatcher({}, {}, {}) }
                )
            }
        }

        compose.onNode(hasClickAction() and hasContentDescription("End title 0"))
            .performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        for (index in 0 until 14) {
            compose.onNode(hasClickAction() and hasContentDescription("End title $index"))
                .performKeyInput { pressKey(Key.DirectionRight) }
            compose.waitForIdle()
        }
        compose.onNode(hasClickAction() and hasContentDescription("End title 14")).assertIsFocused()
    }

    private fun row(id: String, vararg items: MetaItem) = CategoryRow(
        id = id,
        title = id,
        order = 0,
        items = items.map(CatalogCardProjection::from)
    )
}
