package com.saab.tv.ui.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.ui.components.SaabTvCard
import com.saab.tv.ui.components.LocalWatchedIds
import com.saab.tv.ui.components.LocalTitleCardShape
import com.saab.tv.ui.components.SaabTvLandscapeCard
import com.saab.tv.ui.utils.ImagePrefetcher
import com.saab.tv.ui.theme.LocalRoundCorners
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
 * - Uses items(count = Int.MAX_VALUE) with modulo for infinite looping
 * - Unique keys: "${movie.id}_$scrollIndex" to prevent duplicate key crashes
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

private fun FocusRequester.requestFocusSafely() {
    runCatching { requestFocus() }
}

sealed class GridRowItem {
    data class MovieItem(val movie: MetaItem) : GridRowItem()
    data object ViewMoreItem : GridRowItem()
}

class UpKeyDebouncer {
    var lastTime: Long = 0L
}

internal data class FocusFrameSize(val width: Dp, val height: Dp)

internal fun focusFrameSize(itemWidth: Dp, isLandscape: Boolean): FocusFrameSize =
    FocusFrameSize(
        width = itemWidth,
        height = itemWidth * if (isLandscape) (9f / 16f) else (3f / 2f)
    )

private class RowKeyRepeatDebouncer {
    var lastTime: Long = 0L
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun InfiniteLoopRow(
    startPadding: Dp,
    isTopNav: Boolean,
    rowIndex: Int,
    title: String,
    items: List<MetaItem>,
    onMovieClick: (MetaItem) -> Unit,
    onMovieLongClick: (MetaItem, Boolean, androidx.compose.ui.geometry.Rect) -> Unit = { _, _, _ -> },
    onViewMore: () -> Unit,
    onFocused: (MetaItem?, String) -> Unit,
    entryRequester: FocusRequester,
    drawerRequester: FocusRequester,
    locallyFocusedItemId: String?,  // Specific item ID to focus in THIS row (e.g. "movie123_g0"), or null if focus is elsewhere
    isGlobalFocusPresent: Boolean, // True if the app has a focused item tracked (lastFocusedKey != null)
    isFirstRow: Boolean,
    isInfiniteLoopEnabled: Boolean = false,
    visibleItemCount: Int = 15,
    isInfiniteScrollingEnabled: Boolean = true,
    externalListState: androidx.compose.foundation.lazy.LazyListState? = null,
    rowHeight: Dp = 210.dp,
    upKeyDebouncer: UpKeyDebouncer,
    repeatGate: DpadRepeatGate,
    pivotFocusRequester: FocusRequester? = null,
    isLandscapeCards: Boolean = false,
    enrichedItems: Map<String, MetaItem> = emptyMap(),
    onItemShown: (MetaItem) -> Unit = {},
    onWatchedItemShown: (MetaItem) -> Unit = {}
) {
    val density = LocalDensity.current
    val paddingPx = remember(density, startPadding) { with(density) { startPadding.toPx() } }
    val configuration = LocalConfiguration.current
    val useLandscapeCards = isLandscapeCards || LocalTitleCardShape.current == "landscape"
    val effectiveItemWidth = if (useLandscapeCards) 190.dp else ITEM_WIDTH
    val effectiveRowHeight = if (useLandscapeCards) 140.dp else rowHeight
    val screenWidth = configuration.screenWidthDp.dp
    
    // Detect if this is a restoration (coming back from details screen)
    // We check if we have a local focus target AND a saved scroll position
    val isRestoration = remember(locallyFocusedItemId, rowIndex, externalListState) {
        locallyFocusedItemId != null && externalListState != null
    }
    
    // Keep restored focus where it was saved. Once the user navigates, Compose's
    // bring-into-view animation owns the single scroll path and keeps the focused
    // card at the same pivot while the shelf glides underneath it.
    var skipInitialPivotScroll by remember(isRestoration) { mutableStateOf(isRestoration) }
    val pivotSpec = remember(paddingPx) {
        FocusPivotSpec(
            customOffset = paddingPx,
            skipScrollProvider = { skipInitialPivotScroll },
            stiffnessProvider = { Spring.StiffnessMediumLow }
        ) 
    }

    // Calculate end padding to allow last item to align to left (pivot position)
    // End padding = Screen Width - Start Padding - Item Width
    val endPadding = remember(screenWidth, startPadding, effectiveItemWidth) {
        (screenWidth - startPadding - effectiveItemWidth).coerceAtLeast(120.dp)
    }

    // Use external state if provided, otherwise create local state
    val internalListState = rememberLazyListState()
    val listState = externalListState ?: internalListState
    val context = LocalContext.current
    val currentOnItemShown by rememberUpdatedState(onItemShown)
    val currentOnWatchedItemShown by rememberUpdatedState(onWatchedItemShown)
    val artworkUrls = remember(items, enrichedItems, useLandscapeCards) {
        items.map { item ->
            val enriched = enrichedItems["${item.type}:${item.id}"]
            if (useLandscapeCards) enriched?.background ?: item.background else item.poster
        }
    }
    val logoUrls = remember(items, enrichedItems) {
        items.map { enrichedItems["${it.type}:${it.id}"]?.logo ?: it.logo }
    }
    val visibleWatchedIds = LocalWatchedIds.current

    // Warm visible cards and their immediate neighbors as soon as the row is laid out,
    // not only after the user focuses a card. This prevents artwork/logo swaps on focus.
    LaunchedEffect(listState, items, enrichedItems, visibleItemCount, isInfiniteLoopEnabled,
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
                val shownItems = logicalIndices.mapNotNull(items::getOrNull).distinctBy { "${it.type}:${it.id}" }
                shownItems.forEach(currentOnWatchedItemShown)
                // Enrich every visible item so landscape logos and watched aliases are ready
                // before focus lands; portrait rows use the same hook for watched badges.
                if (useLandscapeCards && shownItems.isNotEmpty()) {
                    shownItems.forEach(currentOnItemShown)
                    shownItems.forEach { item ->
                        val index = items.indexOfFirst { it.type == item.type && it.id == item.id }
                        if (index >= 0) {
                            ImagePrefetcher.prefetchLandscape(context, artworkUrls.getOrNull(index))
                            ImagePrefetcher.prefetchLogo(context, logoUrls.getOrNull(index))
                        }
                    }
                    logicalIndices.firstOrNull { it in items.indices }?.let { index ->
                        ImagePrefetcher.prefetchAroundLandscape(context, artworkUrls, index, count = 2, logos = logoUrls)
                    }
                }
            }
    }
    var rowHasCardFocus by remember { mutableStateOf(false) }
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
    val focusFrameShape = if (LocalRoundCorners.current) RoundedCornerShape(12.dp) else RectangleShape

    Column(
        modifier = Modifier.graphicsLayer { clip = false }
            .onFocusChanged { if (!it.hasFocus) rowHasCardFocus = false }
            .onPreviewKeyEvent { event ->
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
        val context = LocalContext.current
        val wrappedOnFocused: (MetaItem?, String) -> Unit = { focused, key ->
            rowHasCardFocus = focused != null
            val focusedIndex = items.indexOfFirst { it.id == focused?.id && it.type == focused?.type }
            if (focusedIndex >= 0) {
                for (nearby in (focusedIndex - 1).coerceAtLeast(0)..(focusedIndex + 1).coerceAtMost(items.lastIndex)) {
                    val nearbyItem = items[nearby]
                    val enriched = enrichedItems["${nearbyItem.type}:${nearbyItem.id}"]
                    if (useLandscapeCards) {
                        ImagePrefetcher.prefetchLandscape(context, enriched?.background ?: nearbyItem.background)
                        ImagePrefetcher.prefetchLogo(context, enriched?.logo ?: nearbyItem.logo)
                    } else {
                        ImagePrefetcher.prefetchBackdrop(context, enriched?.background ?: nearbyItem.background)
                    }
                }
            }
            onFocused(focused, key)
        }
        
        Box(Modifier.fillMaxWidth().height(effectiveRowHeight)) {
        when {
            // CASE A: Grid View OFF - Standard linear list
            // Uses BringIntoViewSpec for automatic pivot-aligned scrolling
            !isInfiniteLoopEnabled -> {
                CompositionLocalProvider(LocalBringIntoViewSpec provides pivotSpec) {
                    LinearContent(
                        listState = listState,
                        startPadding = startPadding,
                        endPadding = endPadding,
                        isTopNav = isTopNav,
                        rowIndex = rowIndex,
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
                        upKeyDebouncer = upKeyDebouncer,
                        repeatGate = repeatGate,
                        pivotFocusRequester = pivotFocusRequester,
                        isLandscapeCards = useLandscapeCards,
                        enrichedItems = enrichedItems,
                        effectiveItemWidth = effectiveItemWidth
                    )
                }
            }
            // CASE B: Grid View ON + Infinite Loop ON
            // A single BringIntoViewSpec path aligns focus and animates the shelf
            // together, avoiding a competing manual scroll target.
            isInfiniteScrollingEnabled -> {
                CompositionLocalProvider(LocalBringIntoViewSpec provides pivotSpec) {
                    InfiniteGridContent(
                        listState = listState,
                        startPadding = startPadding,
                        endPadding = endPadding,
                        isTopNav = isTopNav,
                        rowIndex = rowIndex,
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
                        upKeyDebouncer = upKeyDebouncer,
                        repeatGate = repeatGate,
                        pivotFocusRequester = pivotFocusRequester
                    )
                }
            }
            // CASE C: Grid View ON + Infinite Loop OFF
            // Uses BringIntoViewSpec for automatic pivot-aligned scrolling
            else -> {
                CompositionLocalProvider(LocalBringIntoViewSpec provides pivotSpec) {
                    FiniteGridContent(
                        listState = listState,
                        startPadding = startPadding,
                        endPadding = endPadding,
                        isTopNav = isTopNav,
                        rowIndex = rowIndex,
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
                        upKeyDebouncer = upKeyDebouncer,
                        repeatGate = repeatGate,
                        pivotFocusRequester = pivotFocusRequester
                    )
                }
            }
        }
            if (rowHasCardFocus) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = startPadding)
                        .width(focusFrame.width)
                        .height(focusFrame.height)
                        .border(2.dp, Color.White, focusFrameShape)
                        .zIndex(20f)
                )
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
    upKeyDebouncer: UpKeyDebouncer,
    repeatGate: DpadRepeatGate,
    pivotFocusRequester: FocusRequester? = null,
    isLandscapeCards: Boolean = false,
    enrichedItems: Map<String, MetaItem> = emptyMap(),
    effectiveItemWidth: Dp = ITEM_WIDTH
) {
    val context = LocalContext.current
    // Prefetch image URLs list (cached to avoid allocation during scroll)
    val imageUrls = remember(items, isLandscapeCards, enrichedItems) {
        if (isLandscapeCards) {
            items.map { item ->
                val enriched = enrichedItems["${item.type}:${item.id}"]
                enriched?.background ?: item.background
            }
        } else {
            items.map { it.poster }
        }
    }
    val logoUrls = remember(items, enrichedItems) {
        items.map { enrichedItems["${it.type}:${it.id}"]?.logo ?: it.logo }
    }

    // Debounce navbar escape: track last LEFT key time to prevent escape during long-press
    val leftKeyDebouncer = remember { RowKeyRepeatDebouncer() }
    val navbarEscapeDebounceMs = 300L // Only allow escape if 300ms since last LEFT press

    LazyRow(
        state = listState,
        modifier = Modifier
            .height(rowHeight)
            .graphicsLayer { clip = false },
        contentPadding = PaddingValues(start = startPadding, end = endPadding),
        horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING)
    ) {
        itemsIndexed(
            items = items,
            key = { index, item -> "${rowIndex}_${item.id}_$index" },
            contentType = { _, _ -> if (isLandscapeCards) "landscape" else "movie" }
        ) { index, item ->
            val isFirstItem = index == 0
            val isLastItem = index == items.lastIndex
            val uniqueKey = "${rowIndex}_${item.id}_$index"

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
                                    // Always update time on ANY left press for debounce tracking
                                    val now = System.currentTimeMillis()
                                    val timeSinceLastLeft = now - leftKeyDebouncer.lastTime
                                    leftKeyDebouncer.lastTime = now

                                    if (isFirstItem) {
                                        // Only escape to navbar if this is a deliberate press (not rapid long-press repeat)
                                        if (!isTopNav && timeSinceLastLeft > navbarEscapeDebounceMs) {
                                            drawerRequester.requestFocusSafely()
                                        }
                                        true // Consume at first item to prevent focus escaping
                                    } else {
                                        false // Let normal navigation happen
                                    }
                                }
                                keyEvent.key == Key.DirectionUp -> {
                                    if (isTopNav) {
                                        // TRACK UP KEY on ALL rows to handle rapid scrolling from bottom
                                        val now = System.currentTimeMillis()
                                        val timeSinceLastUp = now - upKeyDebouncer.lastTime
                                        upKeyDebouncer.lastTime = now

                                        if (isFirstRow) {
                                            // Only escape to navbar if slow press
                                            if (timeSinceLastUp > 300L) {
                                                drawerRequester.requestFocusSafely()
                                            }
                                            true // Block default
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
                if (isLandscapeCards) {
                    val enriched = enrichedItems["${item.type}:${item.id}"]
                    SaabTvLandscapeCard(
                        previewItem = enriched ?: item,
                        title = item.name,
                        backdropUrl = enriched?.background ?: item.background,
                        logoUrl = enriched?.logo ?: item.logo,
                        onClick = { onMovieClick(item) },
                        onLongClick = { bounds -> onMovieLongClick(item, rowIndex == -1, bounds) },
                        progress = item.progress,
                        isWatched = rowIndex != -1 && item.id in watchedIds,
                        enableWatchedBadge = rowIndex != -1,
                        showFocusOutline = false,
                        hasNewEpisode = item.hasNewEpisode,
                        onFocused = {
                            ImagePrefetcher.prefetchAroundLandscape(context, imageUrls, index, logos = logoUrls)
                            onFocused(item, uniqueKey)
                        },
                        modifier = Modifier.then(
                            if (shouldRequestFocus) Modifier.focusRequester(entryRequester)
                            else if (pivotFocusRequester != null && index == listState.firstVisibleItemIndex) Modifier.focusRequester(pivotFocusRequester)
                            else Modifier
                        )
                    )
                } else {
                    SaabTvCard(
                        previewItem = item,
                        title = item.name,
                        posterUrl = item.poster,
                        onClick = { onMovieClick(item) },
                        onLongClick = { bounds -> onMovieLongClick(item, rowIndex == -1, bounds) },
                        progress = item.progress,
                        isWatched = rowIndex != -1 && item.id in watchedIds,
                        enableWatchedBadge = rowIndex != -1,
                        showFocusOutline = false,
                        hasNewEpisode = item.hasNewEpisode,
                        onFocused = {
                            ImagePrefetcher.prefetchAround(context, imageUrls, index)
                            onFocused(item, uniqueKey)
                        },
                        modifier = Modifier.then(
                            if (shouldRequestFocus) Modifier.focusRequester(entryRequester)
                            else if (pivotFocusRequester != null && index == listState.firstVisibleItemIndex) Modifier.focusRequester(pivotFocusRequester)
                            else Modifier
                        )
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
 * Netflix-grade truly infinite scrolling implementation:
 * 
 * KEY INSIGHT: Use a VERY large bounded list (100+ repetitions) starting from
 * the middle. Users will NEVER reach the end in practice, making it feel
 * truly infinite without any visible jumps or recentering.
 * 
 * Optimizations:
 * 1. 100 repetitions = 1500+ items - practically infinite
 * 2. Start at section 50 (middle) - can scroll 50 sections in either direction
 * 3. Stable keys with generation suffix - bounded memory, proper recycling
 * 4. No recentering needed - eliminates all visible jumps
 * 5. Image prefetching for smooth loading
 * ============================================================================
 */
private const val INFINITE_LOOP_GENERATIONS = 100000 // 100,000 repetitions = practically infinite

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InfiniteGridContent(
    listState: androidx.compose.foundation.lazy.LazyListState,
    startPadding: Dp,
    endPadding: Dp,
    isTopNav: Boolean,
    rowIndex: Int,
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
    upKeyDebouncer: UpKeyDebouncer,
    repeatGate: DpadRepeatGate,
    pivotFocusRequester: FocusRequester? = null
) {
    val context = LocalContext.current

    val truncatedMovies = remember(items, visibleItemCount) { 
        items.take(visibleItemCount.coerceIn(5, 50)) 
    }

    // Build base data list (one section) - movies + ViewMore card
    val baseDataList: List<GridRowItem> = remember(truncatedMovies) {
        truncatedMovies.map { GridRowItem.MovieItem(it) } + GridRowItem.ViewMoreItem
    }
    val sectionSize = baseDataList.size
    
    // Calculate total items and start position
    val totalItems = sectionSize * INFINITE_LOOP_GENERATIONS

    // Prefetch image URLs list
    val imageUrls = remember(truncatedMovies, enrichedItems, isLandscapeCards) {
        truncatedMovies.map { item ->
            if (isLandscapeCards) {
                enrichedItems["${item.type}:${item.id}"]?.background ?: item.background
            } else item.poster
        }
    }
    val logoUrls = remember(truncatedMovies, enrichedItems) {
        truncatedMovies.map { enrichedItems["${it.type}:${it.id}"]?.logo ?: it.logo }
    }
    
    // Track focused index for ViewMore card's directional alpha
    var currentFocusedIndex by remember(listState) { 
        mutableIntStateOf(listState.firstVisibleItemIndex) 
    }
    
    // Debounce navbar escape: track last LEFT key time to prevent escape during long-press
    val leftKeyDebouncer = remember { RowKeyRepeatDebouncer() }
    val rightKeyDebouncer = remember { RowKeyRepeatDebouncer() }
    val navbarEscapeDebounceMs = 300L

    // Parse last focused key to find the item ID (not scroll index)
    // Key format: "rowIndex_movieId_scrollIndex" or "rowIndex_viewmore_scrollIndex"
    val lastFocusedItemId = remember(locallyFocusedItemId) {
        locallyFocusedItemId?.let { key ->
            // It is already filtered to start with "${rowIndex}_" by parent
             val withoutPrefix = key.removePrefix("${rowIndex}_")
             if (withoutPrefix.startsWith("viewmore")) {
                 "viewmore"
             } else {
                 withoutPrefix.substringBeforeLast("_")
             }
        }
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .height(rowHeight)
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
                val item = baseDataList[logicalIndex]
                when (item) {
                    is GridRowItem.MovieItem -> "${rowIndex}_${item.movie.id}_g$generation"
                    is GridRowItem.ViewMoreItem -> "${rowIndex}_viewmore_g$generation"
                }
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
                                            // Prime RIGHT debounce while moving through posters so ViewMore can
                                            // block long-press escape on arrival.
                                            rightKeyDebouncer.lastTime = System.currentTimeMillis()
                                            false
                                        }
                                        keyEvent.key == Key.DirectionLeft -> {
                                            // Always update time on ANY left press for debounce tracking
                                            val now = System.currentTimeMillis()
                                            val timeSinceLastLeft = now - leftKeyDebouncer.lastTime
                                            leftKeyDebouncer.lastTime = now
                                            
                                            if (isLoopStart) {
                                                // Only escape to navbar if this is a deliberate press (not rapid long-press repeat)
                                                if (!isTopNav && timeSinceLastLeft > navbarEscapeDebounceMs) {
                                                    drawerRequester.requestFocusSafely()
                                                }
                                                true // Consume at loop start to prevent focus escaping
                                            } else {
                                                false // Let normal navigation happen
                                            }
                                        }
                                        keyEvent.key == Key.DirectionUp -> {
                                            if (isTopNav) {
                                                // TRACK UP KEY on ALL rows
                                                val now = System.currentTimeMillis()
                                                val timeSinceLastUp = now - upKeyDebouncer.lastTime
                                                upKeyDebouncer.lastTime = now

                                                if (isFirstRow) {
                                                    if (timeSinceLastUp > 300L) {
                                                        drawerRequester.requestFocusSafely()
                                                    }
                                                    true
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
                        val uniqueKey = "${rowIndex}_${item.movie.id}_$scrollIndex"
                        val watchedIds = LocalWatchedIds.current
                        val cardItem = enrichedItems["${item.movie.type}:${item.movie.id}"] ?: item.movie
                        SaabTvCard(
                            previewItem = cardItem,
                            title = item.movie.name,
                            posterUrl = item.movie.poster,
                            onClick = { onMovieClick(item.movie) },
                            onLongClick = { bounds -> onMovieLongClick(item.movie, rowIndex == -1, bounds) },
                            progress = item.movie.progress,
                            isWatched = rowIndex != -1 && item.movie.id in watchedIds,
                            landscapeWidth = effectiveItemWidth,
                            forceLandscape = isLandscapeCards,
                            showFocusOutline = false,
                            onFocused = {
                                currentFocusedIndex = scrollIndex
                                val logicalIndex = scrollIndex % sectionSize
                                if (logicalIndex < imageUrls.size) {
                                    if (isLandscapeCards) {
                                        ImagePrefetcher.prefetchAroundLandscape(context, imageUrls, logicalIndex, logos = logoUrls)
                                    } else {
                                        ImagePrefetcher.prefetchAround(context, imageUrls, logicalIndex)
                                    }
                                }
                                onFocused(item.movie, uniqueKey)
                            },
                            modifier = Modifier.then(
                                if (shouldRequestFocus) Modifier.focusRequester(entryRequester)
                                else if (pivotFocusRequester != null && scrollIndex == listState.firstVisibleItemIndex) Modifier.focusRequester(pivotFocusRequester)
                                else Modifier
                            )
                        )
                    }
                }

                is GridRowItem.ViewMoreItem -> {
                    val uniqueKey = "${rowIndex}_viewmore_$scrollIndex"
                    
                    InfiniteViewMoreCard(
                        isTopNav = isTopNav,
                        isFirstRow = isFirstRow,
                        drawerRequester = drawerRequester,
                        upKeyDebouncer = upKeyDebouncer,
                        rightKeyDebouncer = rightKeyDebouncer,
                        navbarEscapeDebounceMs = navbarEscapeDebounceMs,
                        repeatGate = repeatGate,
                        scrollIndex = scrollIndex,
                        currentFocusedIndex = currentFocusedIndex,
                        onClick = onViewMore,
                        onFocused = {
                            currentFocusedIndex = scrollIndex
                            onFocused(null, uniqueKey)
                        },
                        cardWidth = effectiveItemWidth,
                        isLandscape = isLandscapeCards,
                        modifier = Modifier
                            .then(if (shouldRequestFocus) Modifier.focusRequester(entryRequester) else Modifier)
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
    upKeyDebouncer: UpKeyDebouncer,
    repeatGate: DpadRepeatGate,
    pivotFocusRequester: FocusRequester? = null
) {
    val context = LocalContext.current
    val truncatedMovies = remember(items, visibleItemCount) { 
        items.take(visibleItemCount.coerceIn(5, 50)) 
    }
    
    // Prefetch image URLs list
    val imageUrls = remember(truncatedMovies, enrichedItems, isLandscapeCards) {
        truncatedMovies.map { item ->
            if (isLandscapeCards) {
                enrichedItems["${item.type}:${item.id}"]?.background ?: item.background
            } else item.poster
        }
    }
    val logoUrls = remember(truncatedMovies, enrichedItems) {
        truncatedMovies.map { enrichedItems["${it.type}:${it.id}"]?.logo ?: it.logo }
    }
    
    // Debounce navbar escape: track last LEFT key time to prevent escape during long-press
    val leftKeyDebouncer = remember { RowKeyRepeatDebouncer() }
    val navbarEscapeDebounceMs = 300L

    // Build finite list: [Movie 0 ... Movie N, ViewMoreItem]
    val dataList: List<GridRowItem> = remember(truncatedMovies) {
        truncatedMovies.map { GridRowItem.MovieItem(it) } + GridRowItem.ViewMoreItem
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .height(rowHeight)
            .graphicsLayer { clip = false },
        contentPadding = PaddingValues(start = startPadding, end = endPadding),
        horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING)
    ) {
        itemsIndexed(
            items = dataList,
            key = { index, item ->
                when (item) {
                    is GridRowItem.MovieItem -> "${rowIndex}_${item.movie.id}_$index"
                    is GridRowItem.ViewMoreItem -> "${rowIndex}_viewmore"
                }
            },
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
                is GridRowItem.MovieItem -> "${rowIndex}_${item.movie.id}_$index"
                is GridRowItem.ViewMoreItem -> "${rowIndex}_viewmore_$index"
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
                                            // Always update time on ANY left press for debounce tracking
                                            val now = System.currentTimeMillis()
                                            val timeSinceLastLeft = now - leftKeyDebouncer.lastTime
                                            leftKeyDebouncer.lastTime = now
                                            
                                            if (isFirstItem) {
                                                // Only escape to navbar if this is a deliberate press (not rapid long-press repeat)
                                                if (!isTopNav && timeSinceLastLeft > navbarEscapeDebounceMs) {
                                                    drawerRequester.requestFocusSafely()
                                                }
                                                true // Consume at first item to prevent focus escaping
                                            } else {
                                                false // Let normal navigation happen
                                            }
                                        }
                                        keyEvent.key == Key.DirectionUp -> {
                                            if (isTopNav) {
                                                // TRACK UP KEY on ALL rows
                                                val now = System.currentTimeMillis()
                                                val timeSinceLastUp = now - upKeyDebouncer.lastTime
                                                upKeyDebouncer.lastTime = now

                                                if (isFirstRow) {
                                                    if (timeSinceLastUp > 300L) {
                                                        drawerRequester.requestFocusSafely()
                                                    }
                                                    true
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
                        val cardItem = enrichedItems["${item.movie.type}:${item.movie.id}"] ?: item.movie
                        SaabTvCard(
                            previewItem = cardItem,
                            title = item.movie.name,
                            posterUrl = item.movie.poster,
                            onClick = { onMovieClick(item.movie) },
                            onLongClick = { bounds -> onMovieLongClick(item.movie, rowIndex == -1, bounds) },
                            progress = item.movie.progress,
                            isWatched = rowIndex != -1 && item.movie.id in watchedIds,
                            landscapeWidth = effectiveItemWidth,
                            forceLandscape = isLandscapeCards,
                            showFocusOutline = false,
                            onFocused = {
                                if (isLandscapeCards) {
                                    ImagePrefetcher.prefetchAroundLandscape(context, imageUrls, index, logos = logoUrls)
                                } else {
                                    ImagePrefetcher.prefetchAround(context, imageUrls, index)
                                }
                                onFocused(item.movie, uniqueKey)
                            },
                            modifier = Modifier.then(
                                if (shouldRequestFocus) Modifier.focusRequester(entryRequester)
                                else if (pivotFocusRequester != null && index == listState.firstVisibleItemIndex) Modifier.focusRequester(pivotFocusRequester)
                                else Modifier
                            )
                        )
                    }
                }

                is GridRowItem.ViewMoreItem -> {
                    Box(
                        modifier = Modifier
                        .width(effectiveItemWidth)
                            .graphicsLayer { clip = false }
                            .onPreviewKeyEvent { keyEvent ->
                        // Inline player handles its own action-row navigation.
                                if (com.saab.tv.ui.trailer.InlineTrailerAnchor.session != null) return@onPreviewKeyEvent false
                                if (repeatGate.shouldConsume(keyEvent)) return@onPreviewKeyEvent true
                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    when (keyEvent.key) {
                                        Key.DirectionRight -> true // Block RIGHT at ViewMore (end of list)
                                        Key.DirectionUp -> {
                                            if (isTopNav) {
                                                val now = System.currentTimeMillis()
                                                val timeSinceLastUp = now - upKeyDebouncer.lastTime
                                                upKeyDebouncer.lastTime = now

                                                if (isFirstRow) {
                                                    if (timeSinceLastUp > 300L) {
                                                        drawerRequester.requestFocusSafely()
                                                    }
                                                    true
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
    upKeyDebouncer: UpKeyDebouncer,
    rightKeyDebouncer: RowKeyRepeatDebouncer,
    navbarEscapeDebounceMs: Long,
    repeatGate: DpadRepeatGate,
    scrollIndex: Int,
    currentFocusedIndex: Int,
    cardWidth: Dp,
    isLandscape: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    val isToTheLeft = scrollIndex < currentFocusedIndex
    val targetAlpha = if (isToTheLeft) 0f else 1f

    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioNoBouncy
        ),
        label = "viewMoreAlpha"
    )

    Box(
        modifier = modifier
            .width(cardWidth)
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
                        Key.DirectionRight -> {
                            // Debounce RIGHT long-press repeats so focus does not escape unexpectedly.
                            val now = System.currentTimeMillis()
                            val timeSinceLastRight = now - rightKeyDebouncer.lastTime
                            rightKeyDebouncer.lastTime = now
                            timeSinceLastRight <= navbarEscapeDebounceMs
                        }
                        Key.DirectionUp -> {
                            if (isTopNav) {
                                val now = System.currentTimeMillis()
                                val timeSinceLastUp = now - upKeyDebouncer.lastTime
                                upKeyDebouncer.lastTime = now

                                if (isFirstRow) {
                                    if (timeSinceLastUp > 300L) {
                                        drawerRequester.requestFocusSafely()
                                    }
                                    true
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
