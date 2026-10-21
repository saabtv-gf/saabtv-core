package com.saab.tv.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.schema.canonicalPresentationKey
import com.saab.tv.ui.components.SaabTvCard
import com.saab.tv.ui.components.SaabMotion
import com.saab.tv.ui.components.LocalWatchedIds
import com.saab.tv.ui.components.LocalTitleCardShape
import com.saab.tv.ui.components.ArtworkPrefetchDispatcher
import com.saab.tv.ui.components.ArtworkPrefetchEffect
import com.saab.tv.ui.components.focusFrameSize
import com.saab.tv.ui.utils.requestFocusSafely
import com.saab.tv.ui.navigation.isInitialDpadPress
import com.saab.tv.ui.navigation.LocalNavigationFocusHandoff
import com.saab.tv.ui.navigation.handoffNavigationFocus
import com.saab.tv.ui.components.FocusPivotSpec
import com.saab.tv.ui.components.artworkPrefetchItemIndices
import com.saab.tv.ui.components.firstItemIndexByCanonicalKey
import com.saab.tv.ui.components.FocusShelfFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * ============================================================================
 * CONTENT ROW - THREE MODE SUPPORT
 * ============================================================================
 *
 * This composable supports three distinct modes:
 *
 * CASE A: Grid View OFF (isInfiniteLoopEnabled = false)
 * - Shows the FULL original list, no truncation
 * - Standard linear LazyRow with itemsIndexed
 * - No ViewMore card, no infinite logic, no modulo math
 *
 * CASE B: Grid View ON + Infinite Loop ON
 * - Truncates list to `visibleItemCount` items + ViewMore card
 * - Repeats a bounded section of items with modulo for practical infinite scrolling
 * - Unique keys include the repeat generation to prevent duplicate-key crashes
 * - One-way loop: LEFT at loop start exits to Navbar
 * - ViewMore card has directional alpha fade
 *
 * CASE C: Grid View ON + Infinite Loop OFF
 * - Truncates list to `visibleItemCount` items + ViewMore card
 * - Finite list: count = visibleItemCount + 1
 * - Focus stops at ViewMore card, no looping
 * - No modulo math needed
 *
 * SAFETY: When switching modes while scrolled far out,
 * the list state is reset to prevent crashes from invalid indices.
 * ============================================================================
 */

private val ITEM_WIDTH = 120.dp
private val ITEM_SPACING = 20.dp

sealed class GridRowItem {
    data class MovieItem(val movie: MetaItem) : GridRowItem()
    data object ViewMoreItem : GridRowItem()
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun InfiniteLoopRow(
    startPadding: Dp,
    isTopNav: Boolean,
    rowIndex: Int,
    showWatchedBadge: Boolean = rowIndex != -1,
    focusRowId: String = rowIndex.toString(),
    title: String,
    items: List<MetaItem>,
    onMovieClick: (MetaItem) -> Unit,
    onMovieLongClick: (MetaItem, Boolean, androidx.compose.ui.geometry.Rect) -> Unit = { _, _, _ -> },
    onViewMore: () -> Unit,
    onFocused: (MetaItem?, String) -> Unit,
    entryRequester: FocusRequester,
    drawerRequester: FocusRequester,
    locallyFocusedItemId: String?,  // Logical saved address for this row/card, or null if focus is elsewhere
    isGlobalFocusPresent: Boolean, // True if the app has a focused item tracked (lastFocusedKey != null)
    isFirstRow: Boolean,
    isInfiniteLoopEnabled: Boolean = false,
    visibleItemCount: Int = 15,
    isInfiniteScrollingEnabled: Boolean = true,
    externalListState: androidx.compose.foundation.lazy.LazyListState? = null,
    rowHeight: Dp = 210.dp,
    repeatGate: DpadRepeatGate,
    pivotFocusRequester: FocusRequester? = null,
    isLandscapeCards: Boolean = false,
    enrichedItems: Map<String, MetaItem> = emptyMap(),
    onItemShown: (MetaItem) -> Unit = {},
    onPrepareArtworkItem: (MetaItem) -> Unit = {},
    onWatchedItemShown: (MetaItem) -> Unit = {},
    artworkPrefetchDispatcher: ArtworkPrefetchDispatcher? = null,
    verticalEntryRequester: FocusRequester? = null,
    upShelfRequester: FocusRequester? = null,
    downShelfRequester: FocusRequester? = null
) {
    val density = LocalDensity.current
    val paddingPx = remember(density, startPadding) { with(density) { startPadding.toPx() } }
    val useLandscapeCards = isLandscapeCards || LocalTitleCardShape.current == "landscape"
    val effectiveItemWidth = if (useLandscapeCards) 190.dp else ITEM_WIDTH
    val effectiveRowHeight = if (useLandscapeCards) 140.dp else rowHeight
    val itemIndexByCanonicalKey = remember(items) { firstItemIndexByCanonicalKey(items) }
    
    // Detect if this is a restoration (coming back from details screen)
    // We check if we have a local focus target AND a saved scroll position
    val isRestoration = remember(locallyFocusedItemId, rowIndex, externalListState) {
        locallyFocusedItemId != null && externalListState != null
    }
    
    // Keep restored focus where it was saved. Once the user navigates, Compose's
    // bring-into-view animation owns the single scroll path and keeps the focused
    // card at the same pivot while the shelf glides underneath it.
    var skipInitialPivotScroll by remember(isRestoration) { mutableStateOf(isRestoration) }
    val pivotSpec = remember(paddingPx, isRestoration) {
        FocusPivotSpec(
            customOffset = paddingPx,
            skipScrollProvider = { skipInitialPivotScroll }
        ) 
    }

    // Bounded shelves stop at real content; the focus frame follows the selected
    // card when the list cannot scroll far enough to keep it at the leading pivot.
    val endPadding = 0.dp

    // Use external state if provided, otherwise create local state
    val internalListState = rememberLazyListState()
    val listState = externalListState ?: internalListState
    val context = LocalContext.current
    val artworkDispatcher = remember(context, artworkPrefetchDispatcher) {
        artworkPrefetchDispatcher ?: ArtworkPrefetchDispatcher.forCoil(context)
    }
    var focusedArtworkKey by remember(listState) { mutableStateOf<String?>(null) }
    val prefetchSnapshots = remember(listState, items, visibleItemCount, isInfiniteLoopEnabled, isInfiniteScrollingEnabled) {
        snapshotFlow {
            artworkPrefetchItemIndices(
                visibleIndices = listState.layoutInfo.visibleItemsInfo.map { it.index },
                itemCount = items.size,
                visibleItemCount = visibleItemCount,
                isInfiniteLoopEnabled = isInfiniteLoopEnabled,
                isInfiniteScrollingEnabled = isInfiniteScrollingEnabled
            ) to focusedArtworkKey
        }.distinctUntilChanged()
    }
    ArtworkPrefetchEffect(
        items = items,
        enrichedItems = enrichedItems,
        listState = listState,
        focusedItemKey = focusedArtworkKey,
        landscapeCards = useLandscapeCards,
        dispatcher = artworkDispatcher,
        requestSnapshots = prefetchSnapshots,
        posterSize = if (useLandscapeCards) null else DpSize(effectiveItemWidth, effectiveItemWidth * 1.5f),
        landscapeSize = if (useLandscapeCards) DpSize(effectiveItemWidth, effectiveItemWidth * (9f / 16f)) else null,
        onPrepareItem = { if (useLandscapeCards) onPrepareArtworkItem(it) }
    )
    val currentOnWatchedItemShown by rememberUpdatedState(onWatchedItemShown)
    val visibleWatchedIds = LocalWatchedIds.current

    // Warm visible cards and their immediate neighbors as soon as the row is laid out,
    // not only after the user focuses a card. This prevents artwork/logo swaps on focus.
    LaunchedEffect(listState, items, visibleItemCount, isInfiniteLoopEnabled,
        isInfiniteScrollingEnabled, useLandscapeCards, visibleWatchedIds) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .collect { visibleIndices ->
                val loopSectionSize = items.size.coerceAtMost(visibleItemCount.coerceIn(5, 50)) + 1
                val logicalIndices = visibleIndices.map { index ->
                    if (isInfiniteLoopEnabled && isInfiniteScrollingEnabled && loopSectionSize > 0) {
                        index % loopSectionSize
                    } else index
                }.distinct()
                val shownItems = logicalIndices.mapNotNull(items::getOrNull).distinctBy { it.canonicalPresentationKey() }
                shownItems.forEach(currentOnWatchedItemShown)
            }
    }
    val rowHasCardFocus = remember { mutableStateOf(false) }
    var focusedItemIndex by remember(listState) { mutableIntStateOf(0) }
    // Calculate the max valid index based on current mode
    val maxValidIndex = when {
        !isInfiniteLoopEnabled -> items.size - 1
        !isInfiniteScrollingEnabled -> visibleItemCount // visibleItemCount items + 1 ViewMore
        else -> Int.MAX_VALUE
    }

    // SAFETY: Reset scroll position when switching modes
    LaunchedEffect(isInfiniteLoopEnabled, isInfiniteScrollingEnabled) {
        if (maxValidIndex >= 0 && listState.firstVisibleItemIndex > maxValidIndex) {
            listState.scrollToItem(0)
        }
    }

    // Match the constrained card slot exactly. Poster cards are 120x180dp here;
    // using SaabTvCard's unconstrained 140x210dp design size made the shared ring
    // spill into adjacent shelves on TV.
    val focusFrame = focusFrameSize(effectiveItemWidth, useLandscapeCards)
    Column(
        modifier = Modifier.graphicsLayer { clip = false }
            .onFocusChanged { if (!it.hasFocus) rowHasCardFocus.value = false }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    com.saab.tv.ui.trailer.InlineTrailerAnchor.session == null
                ) {
                    val destination = when (event.key) {
                        Key.DirectionUp -> upShelfRequester
                        Key.DirectionDown -> downShelfRequester
                        else -> null
                    }
                    if (destination != null) {
                        return@onPreviewKeyEvent runCatching { destination.requestFocus() }.isSuccess
                    }
                }
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionLeft || event.key == Key.DirectionRight ||
                        event.key == Key.DirectionUp || event.key == Key.DirectionDown)
                ) {
                    // A D-pad press means this is user navigation, not saved-focus
                    // restoration. Let the single bring-into-view spring move the
                    // shelf to the pivot instead of leaving the card at a stale offset.
                    skipInitialPivotScroll = false
                }
                false
            }
    ) {
        Text(
            text = title,
            color = Color.White.copy(0.9f),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(start = startPadding, bottom = 12.dp)
        )

        // Stable reference to avoid recomposition from lambda re-allocation
        val wrappedOnFocused: (MetaItem?, String) -> Unit = { focused, key ->
            rowHasCardFocus.value = focused != null
            focusedArtworkKey = focused?.canonicalPresentationKey()
            focused?.canonicalPresentationKey()?.let { focusedKey ->
                itemIndexByCanonicalKey[focusedKey]?.let { focusedItemIndex = it }
            }
            onFocused(focused, key)
        }
        
        FocusShelfFrame(
            state = listState,
            focused = rowHasCardFocus,
            width = focusFrame.width,
            height = focusFrame.height,
            startPadding = startPadding,
            focusedItemIndex = focusedItemIndex,
            tag = "shelf-focus-indicator",
            repeatedSectionSize = if (isInfiniteLoopEnabled && isInfiniteScrollingEnabled) {
                items.size.coerceAtMost(visibleItemCount.coerceIn(5, 50)) + 1
            } else 0,
            viewportHeight = effectiveRowHeight,
            indicatorAlignment = Alignment.TopStart,
            pivotSpec = pivotSpec
        ) {
        when {
            // CASE A: Grid View OFF - Standard linear list
            // Uses BringIntoViewSpec for automatic pivot-aligned scrolling
            !isInfiniteLoopEnabled -> {
                LinearContent(
                        listState = listState,
                        startPadding = startPadding,
                        endPadding = endPadding,
                        isTopNav = isTopNav,
                        rowIndex = rowIndex,
                        showWatchedBadge = showWatchedBadge,
                        focusRowId = focusRowId,
                        items = items,
                        onMovieClick = onMovieClick,
                        onMovieLongClick = onMovieLongClick,
                        onFocused = wrappedOnFocused,
                        entryRequester = entryRequester,
                        drawerRequester = drawerRequester,
                        locallyFocusedItemId = locallyFocusedItemId,
                        isGlobalFocusPresent = isGlobalFocusPresent,
                        isFirstRow = isFirstRow,
                        rowHeight = effectiveRowHeight,
                        repeatGate = repeatGate,
                        pivotFocusRequester = pivotFocusRequester,
                        isLandscapeCards = useLandscapeCards,
                        enrichedItems = enrichedItems,
                        effectiveItemWidth = effectiveItemWidth,
                        verticalEntryRequester = verticalEntryRequester,
                        verticalFocusedIndex = focusedItemIndex
                    )
            }
            // CASE B: Grid View ON + Infinite Loop ON
            // A single BringIntoViewSpec path aligns focus and animates the shelf
            // together, avoiding a competing manual scroll target.
            isInfiniteScrollingEnabled -> {
                InfiniteGridContent(
                        listState = listState,
                        startPadding = startPadding,
                        endPadding = endPadding,
                        isTopNav = isTopNav,
                        rowIndex = rowIndex,
                        showWatchedBadge = showWatchedBadge,
                        focusRowId = focusRowId,
                        items = items,
                        visibleItemCount = visibleItemCount,
                        onMovieClick = onMovieClick,
                        onMovieLongClick = onMovieLongClick,
                        onViewMore = onViewMore,
                        onFocused = wrappedOnFocused,
                        entryRequester = entryRequester,
                        drawerRequester = drawerRequester,
                        locallyFocusedItemId = locallyFocusedItemId,
                        isGlobalFocusPresent = isGlobalFocusPresent,
                        isFirstRow = isFirstRow,
                        isRestoredState = externalListState != null,
                        rowHeight = effectiveRowHeight,
                        effectiveItemWidth = effectiveItemWidth,
                        isLandscapeCards = useLandscapeCards,
                        enrichedItems = enrichedItems,
                        repeatGate = repeatGate,
                        pivotFocusRequester = pivotFocusRequester,
                        verticalEntryRequester = verticalEntryRequester
                    )
            }
            // CASE C: Grid View ON + Infinite Loop OFF
            // Uses BringIntoViewSpec for automatic pivot-aligned scrolling
            else -> {
                FiniteGridContent(
                        listState = listState,
                        startPadding = startPadding,
                        endPadding = endPadding,
                        isTopNav = isTopNav,
                        rowIndex = rowIndex,
                        showWatchedBadge = showWatchedBadge,
                        focusRowId = focusRowId,
                        items = items,
                        visibleItemCount = visibleItemCount,
                        onMovieClick = onMovieClick,
                        onMovieLongClick = onMovieLongClick,
                        onViewMore = onViewMore,
                        onFocused = wrappedOnFocused,
                        entryRequester = entryRequester,
                        drawerRequester = drawerRequester,
                        locallyFocusedItemId = locallyFocusedItemId,
                        isGlobalFocusPresent = isGlobalFocusPresent,
                        isFirstRow = isFirstRow,
                        rowHeight = effectiveRowHeight,
                        effectiveItemWidth = effectiveItemWidth,
                        isLandscapeCards = useLandscapeCards,
                        enrichedItems = enrichedItems,
                        repeatGate = repeatGate,
                        pivotFocusRequester = pivotFocusRequester,
                        verticalEntryRequester = verticalEntryRequester,
                        verticalFocusedIndex = focusedItemIndex
                    )
            }
        }
        }
    }
}

/**
 * CASE A: LINEAR SCROLLING (Grid View OFF - DEFAULT)
 * Full list, no truncation, no infinite logic, no ViewMore
 * 
 * Netflix-grade optimizations:
 * - Image prefetching ahead of focus
 * - beyondBoundsItemCount for precomposition
 * - Stable keys for proper recycling
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinearContent(
    listState: androidx.compose.foundation.lazy.LazyListState,
    startPadding: Dp,
    endPadding: Dp,
    isTopNav: Boolean,
    rowIndex: Int,
    showWatchedBadge: Boolean,
    focusRowId: String,
    items: List<MetaItem>,
    onMovieClick: (MetaItem) -> Unit,
    onMovieLongClick: (MetaItem, Boolean, androidx.compose.ui.geometry.Rect) -> Unit,
    onFocused: (MetaItem?, String) -> Unit,
    entryRequester: FocusRequester,
    drawerRequester: FocusRequester,
    locallyFocusedItemId: String?,
    isGlobalFocusPresent: Boolean,
    isFirstRow: Boolean,
    rowHeight: Dp,
    repeatGate: DpadRepeatGate,
    pivotFocusRequester: FocusRequester? = null,
    isLandscapeCards: Boolean = false,
    enrichedItems: Map<String, MetaItem> = emptyMap(),
    effectiveItemWidth: Dp = ITEM_WIDTH,
    verticalEntryRequester: FocusRequester? = null,
    verticalFocusedIndex: Int = 0
) {
    val authorizeNavigationHandoff = LocalNavigationFocusHandoff.current
    val itemLazyKeys = remember(items, focusRowId) {
        homeRowItemLazyKeys(focusRowId, items.map { "movie:${it.canonicalPresentationKey()}" })
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .height(rowHeight)
            .focusGroup()
            .graphicsLayer { clip = false },
        contentPadding = PaddingValues(start = startPadding, end = endPadding),
        horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING)
    ) {
        itemsIndexed(
            items = items,
            key = { index, _ -> itemLazyKeys[index] },
            contentType = { _, _ -> if (isLandscapeCards) "landscape" else "movie" }
        ) { index, item ->
            val isFirstItem = index == 0
            val isLastItem = index == items.lastIndex
            val uniqueKey = homeCardFocusKey(rowIndex, focusRowId, item.homeFocusIdentityKey(), index)

            val shouldRequestFocus = when {
                uniqueKey == locallyFocusedItemId -> true
                !isGlobalFocusPresent && isFirstRow && isFirstItem -> true
                else -> false
            }

            Box(
                modifier = Modifier
                    .width(effectiveItemWidth)
                    .graphicsLayer { clip = false }
                    .onPreviewKeyEvent { keyEvent ->
                        // Inline player handles its own action-row navigation.
                        if (com.saab.tv.ui.trailer.InlineTrailerAnchor.session != null) return@onPreviewKeyEvent false
                        if (repeatGate.shouldConsume(keyEvent)) return@onPreviewKeyEvent true
                        if (keyEvent.type == KeyEventType.KeyDown) {
                            when {
                                keyEvent.key == Key.DirectionRight -> {
                                    if (isTopNav && isLastItem) {
                                        true // Block escape to top navbar from end of row
                                    } else {
                                        false
                                    }
                                }
                                keyEvent.key == Key.DirectionLeft -> {
                                    if (isFirstItem) {
                                        if (!isTopNav) handoffNavigationFocus(keyEvent, authorizeNavigationHandoff, drawerRequester)
                                        else true // Top navigation is entered from above, not from the left.
                                    } else {
                                        false // Let normal navigation happen
                                    }
                                }
                                keyEvent.key == Key.DirectionUp -> {
                                    if (isTopNav) {
                                        if (isFirstRow) {
                                            handoffNavigationFocus(keyEvent, authorizeNavigationHandoff, drawerRequester)
                                        } else {
                                            false // Allow up nav
                                        }
                                    } else false
                                }
                                else -> false
                            }
                        } else false
                    }
            ) {
                val watchedIds = LocalWatchedIds.current
                val cardItem = enrichedItems[item.canonicalPresentationKey()] ?: item
                if (isLandscapeCards) {
                    SaabTvCard(
                        previewItem = cardItem,
                        title = item.name,
                        posterUrl = item.poster,
                        forceLandscape = true,
                        onClick = { onMovieClick(item) },
                        onLongClick = { bounds -> onMovieLongClick(item, rowIndex == -1, bounds) },
                        progress = item.progress,
                        isWatched = showWatchedBadge && item.id in watchedIds,
                        enableWatchedBadge = showWatchedBadge,
                        showFocusOutline = false,
                        hasNewEpisode = item.hasNewEpisode,
                        landscapeWidth = effectiveItemWidth,
                        onFocused = { onFocused(item, uniqueKey) },
                        modifier = Modifier.then(
                            if (shouldRequestFocus) Modifier.focusRequester(entryRequester)
                            else if (pivotFocusRequester != null && index == listState.firstVisibleItemIndex) Modifier.focusRequester(pivotFocusRequester)
                            else Modifier
                        ).then(if (verticalEntryRequester != null && index == verticalFocusedIndex) Modifier.focusRequester(verticalEntryRequester) else Modifier)
                    )
                } else {
                    SaabTvCard(
                        previewItem = cardItem,
                        title = item.name,
                        posterUrl = item.poster,
                        normalWidth = effectiveItemWidth,
                        normalHeight = effectiveItemWidth * 1.5f,
                        landscapeWidth = effectiveItemWidth,
                        onClick = { onMovieClick(item) },
                        onLongClick = { bounds -> onMovieLongClick(item, rowIndex == -1, bounds) },
                        progress = item.progress,
                        isWatched = showWatchedBadge && item.id in watchedIds,
                        enableWatchedBadge = showWatchedBadge,
                        showFocusOutline = false,
                        hasNewEpisode = item.hasNewEpisode,
                        onFocused = { onFocused(item, uniqueKey) },
                        modifier = Modifier.then(
                            if (shouldRequestFocus) Modifier.focusRequester(entryRequester)
                            else if (pivotFocusRequester != null && index == listState.firstVisibleItemIndex) Modifier.focusRequester(pivotFocusRequester)
                            else Modifier
                        ).then(if (verticalEntryRequester != null && index == verticalFocusedIndex) Modifier.focusRequester(verticalEntryRequester) else Modifier)
                    )
                }
            }
        }
    }
}

/**
 * ============================================================================
 * CASE B: INFINITE GRID (Grid View ON + Infinite Loop ON)
 * ============================================================================
 * 
 * Bounded repeated scrolling implementation:
 * 
 * KEY INSIGHT: A bounded number of repeated sections is enough for TV browsing,
 * without asking Compose to track millions of virtual item positions.
 * 
 * - At most 1,000 repetitions = 51,000 virtual items
 * - Stable keys with generation suffix
 * - No recentering needed, so there are no visible jumps
 * - Image prefetching for smooth loading
 * ============================================================================
 */
internal const val INFINITE_LOOP_GENERATIONS = 1_000

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InfiniteGridContent(
    listState: androidx.compose.foundation.lazy.LazyListState,
    startPadding: Dp,
    endPadding: Dp,
    isTopNav: Boolean,
    rowIndex: Int,
    showWatchedBadge: Boolean,
    focusRowId: String,
    items: List<MetaItem>,
    visibleItemCount: Int,
    onMovieClick: (MetaItem) -> Unit,
    onMovieLongClick: (MetaItem, Boolean, androidx.compose.ui.geometry.Rect) -> Unit,
    onViewMore: () -> Unit,
    onFocused: (MetaItem?, String) -> Unit,
    entryRequester: FocusRequester,
    drawerRequester: FocusRequester,
    locallyFocusedItemId: String?,
    isGlobalFocusPresent: Boolean,
    isFirstRow: Boolean,
    isRestoredState: Boolean = false,
    rowHeight: Dp,
    effectiveItemWidth: Dp,
    isLandscapeCards: Boolean,
    enrichedItems: Map<String, MetaItem>,
    repeatGate: DpadRepeatGate,
    pivotFocusRequester: FocusRequester? = null,
    verticalEntryRequester: FocusRequester? = null
) {
    val authorizeNavigationHandoff = LocalNavigationFocusHandoff.current
    val truncatedMovies = remember(items, visibleItemCount) { 
        items.take(visibleItemCount.coerceIn(5, 50)) 
    }

    // Build base data list (one section) - movies + ViewMore card
    val baseDataList: List<GridRowItem> = remember(truncatedMovies) {
        truncatedMovies.map { GridRowItem.MovieItem(it) } + GridRowItem.ViewMoreItem
    }
    val itemLazyKeys = remember(baseDataList, focusRowId) {
        homeRowItemLazyKeys(focusRowId, baseDataList.map { item ->
            when (item) {
                is GridRowItem.MovieItem -> "movie:${item.movie.canonicalPresentationKey()}"
                is GridRowItem.ViewMoreItem -> "action:viewmore"
            }
        })
    }
    val sectionSize = baseDataList.size
    val cardFocusRequesters = remember(listState, sectionSize) { mutableStateMapOf<Int, FocusRequester>() }
    
    // Calculate total items and start position
    val totalItems = sectionSize * INFINITE_LOOP_GENERATIONS

    // Track focused index for ViewMore card's directional alpha
    var currentFocusedIndex by remember(listState) { 
        mutableIntStateOf(listState.firstVisibleItemIndex) 
    }
    
    // Parse last focused key to find the item ID (not scroll index)
    // Focus address includes the stable row suffix; loop generation is kept only in the lazy item key.
    val lastFocusedItemId = remember(locallyFocusedItemId) {
        locallyFocusedItemId?.let { rawKey ->
            val key = focusKeyWithoutRowId(rawKey)
            // It is already filtered to start with "${rowIndex}_" by parent
             val withoutPrefix = key.removePrefix("${rowIndex}_")
             if (withoutPrefix.startsWith("viewmore")) {
                 "viewmore"
             } else {
                 decodeFocusToken(withoutPrefix.substringBeforeLast("_"))
             }
        }
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .height(rowHeight)
            .focusGroup()
            .graphicsLayer { clip = false },
        contentPadding = PaddingValues(start = startPadding, end = endPadding),
        horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING)
    ) {
        items(
            count = totalItems,
            // Keys use generation (scrollIndex / sectionSize) for uniqueness
            // This bounds memory to INFINITE_LOOP_GENERATIONS unique keys per item
            key = { scrollIndex ->
                val logicalIndex = scrollIndex % sectionSize
                val generation = scrollIndex / sectionSize
                "${itemLazyKeys[logicalIndex]}_g$generation"
            },
            contentType = { scrollIndex ->
                when (baseDataList[scrollIndex % sectionSize]) {
                    is GridRowItem.MovieItem -> "movie"
                    is GridRowItem.ViewMoreItem -> "viewmore"
                }
            }
        ) { scrollIndex ->
            val logicalIndex = scrollIndex % sectionSize
            val item = baseDataList[logicalIndex]
            val isEntryItem = scrollIndex == 0
            val isLoopStart = logicalIndex == 0

            // For focus restoration: match by item ID, only in the current visible section
            // to avoid multiple items requesting focus across different generations
            val currentSectionStart = (listState.firstVisibleItemIndex / sectionSize) * sectionSize
            val isInCurrentSection = scrollIndex >= currentSectionStart && scrollIndex < currentSectionStart + sectionSize
            
            val shouldRequestFocus = when {
                // First row, no previous focus - focus the entry item
                !isGlobalFocusPresent && isFirstRow && isEntryItem -> true
                // Match by item ID in the current visible section only
                isInCurrentSection && item is GridRowItem.MovieItem && lastFocusedItemId == item.movie.id -> true
                isInCurrentSection && item is GridRowItem.ViewMoreItem && lastFocusedItemId == "viewmore" -> true
                else -> false
            }

            when (item) {
                is GridRowItem.MovieItem -> {
                    val cardFocusRequester = remember(scrollIndex) { FocusRequester() }
                    DisposableEffect(scrollIndex, cardFocusRequester) {
                        cardFocusRequesters[scrollIndex] = cardFocusRequester
                        onDispose {
                            if (cardFocusRequesters[scrollIndex] === cardFocusRequester) {
                                cardFocusRequesters.remove(scrollIndex)
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .width(effectiveItemWidth)
                            .graphicsLayer { clip = false }
                            .onPreviewKeyEvent { keyEvent ->
                        // Inline player handles its own action-row navigation.
                                if (com.saab.tv.ui.trailer.InlineTrailerAnchor.session != null) return@onPreviewKeyEvent false
                                if (repeatGate.shouldConsume(keyEvent)) return@onPreviewKeyEvent true
                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    when {
                                        keyEvent.key == Key.DirectionRight -> {
                                            false
                                        }
                                        keyEvent.key == Key.DirectionLeft -> {
                                            if (isLoopStart) {
                                                if (!isTopNav) handoffNavigationFocus(keyEvent, authorizeNavigationHandoff, drawerRequester)
                                                else true // Top navigation is entered from above, not from the left.
                                            } else {
                                                false // Let normal navigation happen
                                            }
                                        }
                                        keyEvent.key == Key.DirectionUp -> {
                                            if (isTopNav) {
                                                if (isFirstRow) {
                                                    handoffNavigationFocus(keyEvent, authorizeNavigationHandoff, drawerRequester)
                                                } else {
                                                    false
                                                }
                                            } else false
                                        }
                                        else -> false
                                    }
                                } else false
                            }
                    ) {
                        val uniqueKey = homeCardFocusKey(rowIndex, focusRowId, item.movie.homeFocusIdentityKey(), scrollIndex)
                        val watchedIds = LocalWatchedIds.current
                        val cardItem = enrichedItems[item.movie.canonicalPresentationKey()] ?: item.movie
                        SaabTvCard(
                            previewItem = cardItem,
                            title = item.movie.name,
                            posterUrl = item.movie.poster,
                            normalWidth = effectiveItemWidth,
                            normalHeight = effectiveItemWidth * 1.5f,
                            onClick = { onMovieClick(item.movie) },
                            onLongClick = { bounds -> onMovieLongClick(item.movie, rowIndex == -1, bounds) },
                            progress = item.movie.progress,
                            isWatched = showWatchedBadge && item.movie.id in watchedIds,
                            enableWatchedBadge = showWatchedBadge,
                            landscapeWidth = effectiveItemWidth,
                            forceLandscape = isLandscapeCards,
                            showFocusOutline = false,
                            onFocused = {
                                currentFocusedIndex = scrollIndex
                                onFocused(item.movie, uniqueKey)
                            },
                            modifier = Modifier.then(
                                if (shouldRequestFocus) Modifier.focusRequester(entryRequester)
                                else if (pivotFocusRequester != null && scrollIndex == listState.firstVisibleItemIndex) Modifier.focusRequester(pivotFocusRequester)
                                else Modifier
                            ).focusRequester(cardFocusRequester)
                                .then(if (verticalEntryRequester != null && scrollIndex == currentFocusedIndex) Modifier.focusRequester(verticalEntryRequester) else Modifier)
                        )
                    }
                }

                is GridRowItem.ViewMoreItem -> {
                    val uniqueKey = homeCardFocusKey(rowIndex, focusRowId, "viewmore", scrollIndex)
                    val previousCardFocusRequester = cardFocusRequesters[scrollIndex - 1]
                    
                    InfiniteViewMoreCard(
                        isTopNav = isTopNav,
                        isFirstRow = isFirstRow,
                        drawerRequester = drawerRequester,
                        repeatGate = repeatGate,
                        scrollIndex = scrollIndex,
                        currentFocusedIndex = currentFocusedIndex,
                        previousCardFocusRequester = previousCardFocusRequester,
                        onClick = onViewMore,
                        onFocused = {
                            currentFocusedIndex = scrollIndex
                            onFocused(null, uniqueKey)
                        },
                        cardWidth = effectiveItemWidth,
                        isLandscape = isLandscapeCards,
                        modifier = Modifier
                            .then(if (shouldRequestFocus) Modifier.focusRequester(entryRequester) else Modifier)
                            .then(if (verticalEntryRequester != null && scrollIndex == currentFocusedIndex) Modifier.focusRequester(verticalEntryRequester) else Modifier)
                            .then(
                                if (pivotFocusRequester != null && scrollIndex == listState.firstVisibleItemIndex) {
                                    Modifier.focusRequester(pivotFocusRequester)
                                } else Modifier
                            )
                    )
                }
            }
        }
    }
    
    // Initialize scroll position to middle on first composition
    // Skip initialization if this is a restored state (coming back to this row)
    LaunchedEffect(Unit) {
        if (!isRestoredState && listState.firstVisibleItemIndex == 0) {
            listState.scrollToItem(0)
        }
    }
}

/**
 * CASE C: FINITE GRID (Grid View ON + Infinite Loop OFF)
 * Truncated list that ends at ViewMore card, no looping
 * 
 * Netflix-grade optimizations:
 * - Image prefetching ahead of focus
 * - beyondBoundsItemCount for precomposition
 * - Stable keys for proper recycling
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FiniteGridContent(
    listState: androidx.compose.foundation.lazy.LazyListState,
    startPadding: Dp,
    endPadding: Dp,
    isTopNav: Boolean,
    rowIndex: Int,
    showWatchedBadge: Boolean,
    focusRowId: String,
    items: List<MetaItem>,
    visibleItemCount: Int,
    onMovieClick: (MetaItem) -> Unit,
    onMovieLongClick: (MetaItem, Boolean, androidx.compose.ui.geometry.Rect) -> Unit,
    onViewMore: () -> Unit,
    onFocused: (MetaItem?, String) -> Unit,
    entryRequester: FocusRequester,
    drawerRequester: FocusRequester,
    locallyFocusedItemId: String?,
    isGlobalFocusPresent: Boolean,
    isFirstRow: Boolean,
    rowHeight: Dp,
    effectiveItemWidth: Dp,
    isLandscapeCards: Boolean,
    enrichedItems: Map<String, MetaItem>,
    repeatGate: DpadRepeatGate,
    pivotFocusRequester: FocusRequester? = null,
    verticalEntryRequester: FocusRequester? = null,
    verticalFocusedIndex: Int = 0
) {
    val authorizeNavigationHandoff = LocalNavigationFocusHandoff.current
    val truncatedMovies = remember(items, visibleItemCount) { 
        items.take(visibleItemCount.coerceIn(5, 50)) 
    }
    
    
    // Build finite list: [Movie 0 ... Movie N, ViewMoreItem]
    val dataList: List<GridRowItem> = remember(truncatedMovies) {
        truncatedMovies.map { GridRowItem.MovieItem(it) } + GridRowItem.ViewMoreItem
    }
    val cardFocusRequesters = remember(dataList) { List(dataList.size) { FocusRequester() } }
    val itemLazyKeys = remember(dataList, focusRowId) {
        homeRowItemLazyKeys(focusRowId, dataList.map { item ->
            when (item) {
                is GridRowItem.MovieItem -> "movie:${item.movie.canonicalPresentationKey()}"
                is GridRowItem.ViewMoreItem -> "action:viewmore"
            }
        })
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .height(rowHeight)
            .focusGroup()
            .graphicsLayer { clip = false },
        contentPadding = PaddingValues(start = startPadding, end = endPadding),
        horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING)
    ) {
        itemsIndexed(
            items = dataList,
            key = { index, _ -> itemLazyKeys[index] },
            contentType = { _, item ->
                when (item) {
                    is GridRowItem.MovieItem -> "movie"
                    is GridRowItem.ViewMoreItem -> "viewmore"
                }
            }
        ) { index, item ->
            val isFirstItem = index == 0
            val isLastItem = index == dataList.size - 1

            val uniqueKey = when (item) {
                is GridRowItem.MovieItem -> homeCardFocusKey(rowIndex, focusRowId, item.movie.homeFocusIdentityKey(), index)
                is GridRowItem.ViewMoreItem -> homeCardFocusKey(rowIndex, focusRowId, "viewmore", index)
            }

            val shouldRequestFocus = when {
                uniqueKey == locallyFocusedItemId -> true
                !isGlobalFocusPresent && isFirstRow && isFirstItem -> true
                else -> false
            }

            when (item) {
                is GridRowItem.MovieItem -> {
                    Box(
                        modifier = Modifier
                            .width(effectiveItemWidth)
                            .graphicsLayer { clip = false }
                            .onPreviewKeyEvent { keyEvent ->
                        // Inline player handles its own action-row navigation.
                                if (com.saab.tv.ui.trailer.InlineTrailerAnchor.session != null) return@onPreviewKeyEvent false
                                if (repeatGate.shouldConsume(keyEvent)) return@onPreviewKeyEvent true
                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    when {
                                        keyEvent.key == Key.DirectionLeft -> {
                                            if (isFirstItem) {
                                                if (!isTopNav) handoffNavigationFocus(keyEvent, authorizeNavigationHandoff, drawerRequester)
                                                else true // Top navigation is entered from above, not from the left.
                                            } else {
                                                false // Let normal navigation happen
                                            }
                                        }
                                        keyEvent.key == Key.DirectionUp -> {
                                            if (isTopNav) {
                                                if (isFirstRow) {
                                                    handoffNavigationFocus(keyEvent, authorizeNavigationHandoff, drawerRequester)
                                                } else {
                                                    false
                                                }
                                            } else false
                                        }
                                        else -> false
                                    }
                                } else false
                            }
                    ) {
                        val watchedIds = LocalWatchedIds.current
                        val cardItem = enrichedItems[item.movie.canonicalPresentationKey()] ?: item.movie
                        SaabTvCard(
                            previewItem = cardItem,
                            title = item.movie.name,
                            posterUrl = item.movie.poster,
                            normalWidth = effectiveItemWidth,
                            normalHeight = effectiveItemWidth * 1.5f,
                            onClick = { onMovieClick(item.movie) },
                            onLongClick = { bounds -> onMovieLongClick(item.movie, rowIndex == -1, bounds) },
                            progress = item.movie.progress,
                            isWatched = showWatchedBadge && item.movie.id in watchedIds,
                            enableWatchedBadge = showWatchedBadge,
                            landscapeWidth = effectiveItemWidth,
                            forceLandscape = isLandscapeCards,
                            showFocusOutline = false,
                            onFocused = { onFocused(item.movie, uniqueKey) },
                            modifier = Modifier.then(
                                if (shouldRequestFocus) Modifier.focusRequester(entryRequester)
                                else if (pivotFocusRequester != null && index == listState.firstVisibleItemIndex) Modifier.focusRequester(pivotFocusRequester)
                                else Modifier
                            ).focusRequester(cardFocusRequesters[index])
                                .then(if (verticalEntryRequester != null && index == verticalFocusedIndex) Modifier.focusRequester(verticalEntryRequester) else Modifier)
                        )
                    }
                }

                is GridRowItem.ViewMoreItem -> {
                    val previousCardFocusRequester = cardFocusRequesters.getOrNull(index - 1)
                    Box(
                        modifier = Modifier
                        .width(effectiveItemWidth)
                            .graphicsLayer { clip = false }
                            .then(
                                previousCardFocusRequester?.let { previous ->
                                    Modifier.focusProperties { left = previous }
                                } ?: Modifier
                            )
                            .onPreviewKeyEvent { keyEvent ->
                        // Inline player handles its own action-row navigation.
                                if (com.saab.tv.ui.trailer.InlineTrailerAnchor.session != null) return@onPreviewKeyEvent false
                                if (repeatGate.shouldConsume(keyEvent)) return@onPreviewKeyEvent true
                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    when (keyEvent.key) {
                                        Key.DirectionRight -> true // Block RIGHT at ViewMore (end of list)
                                        Key.DirectionLeft -> {
                                            previousCardFocusRequester?.requestFocusSafely() ?: false
                                        }
                                        Key.DirectionUp -> {
                                            if (isTopNav) {
                                                if (isFirstRow) {
                                                    handoffNavigationFocus(keyEvent, authorizeNavigationHandoff, drawerRequester)
                                                } else {
                                                    false
                                                }
                                            } else false
                                        }
                                        else -> false
                                    }
                                } else false
                            }
                    ) {
                        ViewMoreCard(
                            onClick = onViewMore,
                            onFocused = { 
                                onFocused(null, uniqueKey) 
                            },
                            cardWidth = effectiveItemWidth,
                            isLandscape = isLandscapeCards,
                            modifier = Modifier
                                .then(if (shouldRequestFocus) Modifier.focusRequester(entryRequester) else Modifier)
                                .then(
                                    if (pivotFocusRequester != null && index == listState.firstVisibleItemIndex) {
                                        Modifier.focusRequester(pivotFocusRequester)
                                    } else Modifier
                                )
                        )
                    }
                }
            }
        }
    }
}

/**
 * ViewMore card with directional alpha animation (Infinite mode only)
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun InfiniteViewMoreCard(
    isTopNav: Boolean,
    isFirstRow: Boolean,
    drawerRequester: FocusRequester,
    repeatGate: DpadRepeatGate,
    scrollIndex: Int,
    currentFocusedIndex: Int,
    previousCardFocusRequester: FocusRequester?,
    cardWidth: Dp,
    isLandscape: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier
) {
    val authorizeNavigationHandoff = LocalNavigationFocusHandoff.current
    var isFocused by remember { mutableStateOf(false) }

    val isToTheLeft = scrollIndex < currentFocusedIndex
    val targetAlpha = if (isToTheLeft) 0f else 1f

    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = SaabMotion.focusRetargetSpec(),
        label = "viewMoreAlpha"
    )

    Box(
        modifier = modifier
            .width(cardWidth)
            .then(
                previousCardFocusRequester?.let { previous ->
                    Modifier.focusProperties { left = previous }
                } ?: Modifier
            )
            .graphicsLayer {
                clip = false
                alpha = animatedAlpha
            }
            .onPreviewKeyEvent { keyEvent ->
                        // Inline player handles its own action-row navigation.
                        if (com.saab.tv.ui.trailer.InlineTrailerAnchor.session != null) return@onPreviewKeyEvent false
                if (repeatGate.shouldConsume(keyEvent)) return@onPreviewKeyEvent true
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.key) {
                        Key.DirectionLeft -> {
                            previousCardFocusRequester?.requestFocusSafely() ?: false
                        }
                        Key.DirectionRight -> {
                            // Let a fresh press advance into the loop; only held-key repeats stay at View More.
                            !keyEvent.isInitialDpadPress()
                        }
                        Key.DirectionUp -> {
                            if (isTopNav) {
                                if (isFirstRow) {
                                    handoffNavigationFocus(keyEvent, authorizeNavigationHandoff, drawerRequester)
                                } else {
                                    false
                                }
                            } else false
                        }
                        else -> false
                    }
                } else false
            }
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
                if (focusState.isFocused) {
                    onFocused()
                }
            }
    ) {
        ViewMoreCard(
            onClick = onClick,
            onFocused = null,
            cardWidth = cardWidth,
            isLandscape = isLandscape
        )
    }
}
