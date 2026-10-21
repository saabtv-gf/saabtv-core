package com.saab.tv.ui.home

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.data.model.WatchlistEntity
import com.saab.tv.domain.schema.canonicalPresentationKey
import com.saab.tv.ui.watchlist.buildMyLibraryItems
import com.saab.tv.ui.components.ArtworkPrefetchDispatcher
import com.saab.tv.ui.components.ArtworkPrefetchRequest
import com.saab.tv.ui.components.ArtworkPrefetchRole
import com.saab.tv.ui.components.LocalWatchedIds
import com.saab.tv.ui.components.ShelfFocusIndicatorActive
import com.saab.tv.ui.components.LocalTitleCardShape
import com.saab.tv.ui.theme.LocalRoundCorners
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class InfiniteLoopRowArtworkPrefetchTest {
    @get:Rule val compose = createComposeRule()

    @Test fun visiblePosterCardUsesSharedPrefetchPolicy() {
        val requests = CopyOnWriteArrayList<ArtworkPrefetchRequest>()
        render(
            item = MetaItem("tt-row-poster", "movie", "Poster", poster = "row-poster"),
            landscapeCards = false,
            dispatcher = recordingDispatcher(requests)
        )

        compose.waitUntil(5_000) {
            requests.contains(ArtworkPrefetchRequest(
                ArtworkPrefetchRole.Poster, "row-poster", posterSize = DpSize(120.dp, 180.dp)
            ))
        }
        compose.onNodeWithContentDescription("Poster")
            .assertWidthIsEqualTo(120.dp)
            .assertHeightIsEqualTo(180.dp)
    }

    @Test fun standardPosterCardRendersTheSameEnrichedItemThatPrefetchUses() {
        val source = MetaItem("tt-row-enriched", "movie", "Provider title", poster = "provider-poster")
        val enriched = source.copy(name = "TMDB title", poster = "tmdb-poster")
        val requests = CopyOnWriteArrayList<ArtworkPrefetchRequest>()
        render(
            item = source,
            landscapeCards = false,
            dispatcher = recordingDispatcher(requests),
            enrichedItems = mapOf(source.canonicalPresentationKey() to enriched)
        )

        compose.onNodeWithContentDescription("TMDB title").assertExists()
        compose.waitUntil(5_000) {
            requests.contains(ArtworkPrefetchRequest(
                ArtworkPrefetchRole.Poster, "tmdb-poster", posterSize = DpSize(120.dp, 180.dp)
            ))
        }
    }

    @Test fun visibleLandscapeCardUsesSharedBackdropAndLogoPrefetch() {
        val requests = CopyOnWriteArrayList<ArtworkPrefetchRequest>()
        render(
            item = MetaItem(
                "tt-row-landscape", "series", "Landscape",
                background = "row-backdrop", logo = "row-logo"
            ),
            landscapeCards = true,
            dispatcher = recordingDispatcher(requests)
        )

        compose.waitUntil(5_000) {
            requests.contains(ArtworkPrefetchRequest(
                ArtworkPrefetchRole.Landscape, "row-backdrop", landscapeSize = DpSize(190.dp, 106.875.dp)
            )) &&
                requests.contains(ArtworkPrefetchRequest(ArtworkPrefetchRole.Logo, "row-logo"))
        }
    }

    @Test fun myLibraryLandscapeProjectionPrefetchesItsPersistedBackdropAndLogo() {
        val libraryItem = buildMyLibraryItems(
            history = emptyList(),
            watchlist = listOf(
                WatchlistEntity(
                    profileId = 81,
                    id = "tt-library-landscape",
                    type = "series",
                    title = "Library Landscape",
                    poster = "library-poster",
                    addedAt = 1L,
                    background = "library-backdrop",
                    logo = "library-logo"
                )
            ),
            nextUp = emptyList()
        ).watchlist.single().asActionItem()
        val requests = CopyOnWriteArrayList<ArtworkPrefetchRequest>()

        render(
            item = libraryItem,
            landscapeCards = true,
            dispatcher = recordingDispatcher(requests),
            rowIndex = 1
        )

        compose.waitUntil(5_000) {
            requests.contains(
                ArtworkPrefetchRequest(
                    ArtworkPrefetchRole.Landscape,
                    "library-backdrop",
                    landscapeSize = DpSize(190.dp, 106.875.dp)
                )
            ) && requests.contains(ArtworkPrefetchRequest(ArtworkPrefetchRole.Logo, "library-logo"))
        }
        assertEquals(
            setOf(ArtworkPrefetchRole.Landscape, ArtworkPrefetchRole.Logo),
            requests.map(ArtworkPrefetchRequest::role).toSet()
        )
    }

    @Test fun landscapePreferenceAppliesWhenTheRowHasNoExplicitLandscapeOverride() {
        val requests = CopyOnWriteArrayList<ArtworkPrefetchRequest>()
        render(
            item = MetaItem("tt-row-global-landscape", "series", "Landscape Preference", background = "backdrop"),
            landscapeCards = false,
            titleCardShape = "landscape",
            dispatcher = recordingDispatcher(requests)
        )

        compose.onNodeWithContentDescription("Landscape Preference")
            .assertWidthIsEqualTo(190.dp)
            .assertHeightIsEqualTo(106.875.dp)
        compose.waitUntil(5_000) {
            requests.contains(ArtworkPrefetchRequest(
                ArtworkPrefetchRole.Landscape, "backdrop", landscapeSize = DpSize(190.dp, 106.875.dp)
            ))
        }
    }

    @Test fun landscapeCardCenterPressDispatchesItsOwnTitle() {
        val item = MetaItem("tt-row-landscape-click", "series", "Landscape Series")
        var selectedId: String? = null
        render(
            item = item,
            landscapeCards = true,
            dispatcher = recordingDispatcher(CopyOnWriteArrayList()),
            onMovieClick = { selectedId = it.id }
        )

        compose.onNodeWithContentDescription(item.name)
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(item.id, selectedId) }
    }

    @Test fun continueWatchingSuppressesWatchedBadgeInEveryRowMode() {
        val item = MetaItem("watched-continue-title", "series", "Continue title")
        val rowMode = mutableStateOf(false to true)
        compose.setContent {
            val (infiniteLoop, infiniteScrolling) = rowMode.value
            MaterialTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalWatchedIds provides setOf(item.id)
                ) {
                    InfiniteLoopRow(
                        startPadding = 0.dp,
                        isTopNav = false,
                        rowIndex = -1,
                        title = "Continue Watching",
                        items = listOf(item),
                        onMovieClick = {},
                        onViewMore = {},
                        onFocused = { _, _ -> },
                        entryRequester = FocusRequester(),
                        drawerRequester = FocusRequester(),
                        locallyFocusedItemId = null,
                        isGlobalFocusPresent = false,
                        isFirstRow = true,
                        isInfiniteLoopEnabled = infiniteLoop,
                        visibleItemCount = 5,
                        isInfiniteScrollingEnabled = infiniteScrolling,
                        repeatGate = DpadRepeatGate(),
                        artworkPrefetchDispatcher = recordingDispatcher(CopyOnWriteArrayList())
                    )
                }
            }
        }

        listOf(
            false to true, // linear row
            true to true,  // infinite grid row
            true to false  // finite grid row
        ).forEach { (infiniteLoop, infiniteScrolling) ->
            compose.runOnIdle { rowMode.value = infiniteLoop to infiniteScrolling }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Watched").assertDoesNotExist()
        }
    }

    @Test fun libraryContinueWatchingHidesWatchedBadgeAtItsStableSectionIndex() {
        render(
            item = MetaItem("watched-library-continue", "series", "Continue title"),
            landscapeCards = false,
            dispatcher = recordingDispatcher(CopyOnWriteArrayList()),
            rowIndex = 0,
            showWatchedBadge = false,
            watchedIds = setOf("watched-library-continue")
        )

        compose.onNodeWithContentDescription("Watched").assertDoesNotExist()
    }

    @Test fun focusIndicatorAppearsOnTheFixedCardBoundsWithoutScalingTheShelf() {
        assertFocusIndicator(rounded = true)
    }

    @Test fun focusIndicatorSupportsSquareCardCorners() {
        assertFocusIndicator(rounded = false)
    }

    @Test fun infiniteGridKeepsVirtualItemCountBoundedAtMaximumRailSize() {
        val listState = LazyListState()
        val items = (0 until 50).map { index ->
            MetaItem("tt-infinite-$index", "movie", "Infinite $index")
        }
        compose.setContent {
            MaterialTheme {
                InfiniteLoopRow(
                    startPadding = 0.dp,
                    isTopNav = false,
                    rowIndex = 0,
                    title = "Infinite Rail",
                    items = items,
                    onMovieClick = {},
                    onViewMore = {},
                    onFocused = { _, _ -> },
                    entryRequester = FocusRequester(),
                    drawerRequester = FocusRequester(),
                    locallyFocusedItemId = null,
                    isGlobalFocusPresent = true,
                    isFirstRow = false,
                    isInfiniteLoopEnabled = true,
                    visibleItemCount = 50,
                    isInfiniteScrollingEnabled = true,
                    externalListState = listState,
                    repeatGate = DpadRepeatGate(),
                    artworkPrefetchDispatcher = recordingDispatcher(CopyOnWriteArrayList())
                )
            }
        }

        compose.waitUntil(3_000) { listState.layoutInfo.totalItemsCount > 0 }
        compose.runOnIdle {
            assertEquals(51 * INFINITE_LOOP_GENERATIONS, listState.layoutInfo.totalItemsCount)
            assertEquals(51_000, listState.layoutInfo.totalItemsCount)
        }
    }

    private fun assertFocusIndicator(rounded: Boolean) {
        val entryRequester = FocusRequester()
        render(
            item = MetaItem("tt-focus-ring", "movie", "Focus ring", poster = "poster"),
            landscapeCards = false,
            dispatcher = recordingDispatcher(CopyOnWriteArrayList()),
            entryRequester = entryRequester,
            roundedCorners = rounded
        )

        val indicator = compose.onNodeWithTag("shelf-focus-indicator")
        indicator.assertWidthIsEqualTo(120.dp)
        indicator.assertHeightIsEqualTo(180.dp)
        indicator.assert(SemanticsMatcher.expectValue(ShelfFocusIndicatorActive, false))

        compose.runOnIdle { entryRequester.requestFocus() }
        compose.waitForIdle()
        indicator.assert(SemanticsMatcher.expectValue(ShelfFocusIndicatorActive, true))
    }

    private fun render(
        item: MetaItem,
        landscapeCards: Boolean,
        dispatcher: ArtworkPrefetchDispatcher,
        entryRequester: FocusRequester = FocusRequester(),
        roundedCorners: Boolean = true,
        titleCardShape: String = "poster",
        enrichedItems: Map<String, MetaItem> = emptyMap(),
        rowIndex: Int = 0,
        showWatchedBadge: Boolean = rowIndex != -1,
        watchedIds: Set<String> = emptySet(),
        onMovieClick: (MetaItem) -> Unit = {}
    ) {
        compose.setContent {
            MaterialTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalRoundCorners provides roundedCorners,
                    LocalTitleCardShape provides titleCardShape,
                    LocalWatchedIds provides watchedIds
                ) {
                    InfiniteLoopRow(
                        startPadding = 0.dp,
                        isTopNav = false,
                        rowIndex = rowIndex,
                        showWatchedBadge = showWatchedBadge,
                        title = "Fixture Row",
                        items = listOf(item),
                        onMovieClick = onMovieClick,
                        onViewMore = {},
                        onFocused = { _, _ -> },
                        entryRequester = entryRequester,
                        drawerRequester = FocusRequester(),
                        locallyFocusedItemId = null,
                        isGlobalFocusPresent = false,
                        isFirstRow = true,
                        repeatGate = DpadRepeatGate(),
                        isLandscapeCards = landscapeCards,
                        enrichedItems = enrichedItems,
                        artworkPrefetchDispatcher = dispatcher
                    )
                }
            }
        }
    }

    private fun recordingDispatcher(requests: MutableList<ArtworkPrefetchRequest>) =
        ArtworkPrefetchDispatcher(
            poster = { requests += ArtworkPrefetchRequest(ArtworkPrefetchRole.Poster, it) },
            landscape = { requests += ArtworkPrefetchRequest(ArtworkPrefetchRole.Landscape, it) },
            logo = { requests += ArtworkPrefetchRequest(ArtworkPrefetchRole.Logo, it) },
            posterSized = { url, size -> requests += ArtworkPrefetchRequest(ArtworkPrefetchRole.Poster, url, size) },
            landscapeSized = { url, size ->
                requests += ArtworkPrefetchRequest(ArtworkPrefetchRole.Landscape, url, landscapeSize = size)
            }
        )
}
