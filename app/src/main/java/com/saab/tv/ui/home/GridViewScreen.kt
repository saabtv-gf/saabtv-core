package com.saab.tv.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.saab.tv.R
import com.saab.tv.ui.components.ArtworkPrefetchDispatcher
import com.saab.tv.ui.components.GridArtworkPrefetchEffect
import com.saab.tv.ui.components.FocusPivotSpec
import com.saab.tv.ui.components.LocalTitleCardShape
import com.saab.tv.ui.components.scrollToItemUsingSaabMotion
import com.saab.tv.ui.navigation.isBackOrEscape
import com.saab.tv.ui.navigation.isInitialBackPress
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.schema.canonicalPresentationKey
import com.saab.tv.ui.components.SaabTvCard
import com.saab.tv.ui.utils.requestFocusOrFallback
import com.saab.tv.ui.utils.requestFocusSafely
import com.saab.tv.ui.utils.requestFocusWhenAttached

private const val COLUMNS = 6
private const val DPAD_REPEAT_INTERVAL_HORIZONTAL_MS = 150L
private const val DPAD_REPEAT_INTERVAL_VERTICAL_MS = 200L

internal class GridPaginationGate(private val prefetchDistance: Int = 12) {
    private var requestedSourceCount = -1

    fun shouldLoadMore(itemCount: Int, lastVisibleIndex: Int, sourceItemCount: Int = itemCount): Boolean {
        if (itemCount <= 0 || lastVisibleIndex < itemCount - prefetchDistance) {
            requestedSourceCount = -1
            return false
        }
        if (requestedSourceCount == sourceItemCount) return false
        requestedSourceCount = sourceItemCount
        return true
    }

    fun reset() { requestedSourceCount = -1 }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun GridViewScreen(
    title: String,
    items: List<MetaItem>,
    gridStateKey: String = title,
    initialFilter: CatalogMediaFilter = when (title.trim().lowercase()) {
        "movies" -> CatalogMediaFilter.MOVIES
        "series" -> CatalogMediaFilter.SERIES
        else -> CatalogMediaFilter.ALL
    },
    lastFocusedIndex: Int?,
    lastFocusedItemKey: String? = null,
    onFocusChange: (Int, String) -> Unit,
    onMovieClick: (MetaItem) -> Unit,
    profileId: Int = 1,
    onTrailerClick: (String, String) -> Unit = { _, _ -> },
    onBack: () -> Unit,
    onLoadMore: () -> Unit = {},
    hasMoreItems: Boolean = false,
    isLoadingMore: Boolean = false,
    hasLoadMoreError: Boolean = false,
    onRetryLoadMore: () -> Unit = onLoadMore,
    isInitialLoading: Boolean = false,
    hasInitialLoadError: Boolean = false,
    onRetryInitialLoad: () -> Unit = {},
    // Scroll position persistence for instant restoration
    initialScrollIndex: Int = 0,
    initialScrollOffset: Int = 0,
    onScrollPositionChange: (Int, Int) -> Unit = { _, _ -> },
    watchedIds: Set<String> = emptySet(),
    allowTrailerAutoplay: Boolean = true,
    previewViewModel: HomeViewModel = hiltViewModel(),
    artworkPrefetchDispatcher: ArtworkPrefetchDispatcher? = null
) {
    var filterKey by rememberSaveable(gridStateKey) { mutableStateOf(initialFilter.name) }
    var sortKey by rememberSaveable(gridStateKey) { mutableStateOf(CatalogGridSort.FEATURED.name) }
    val selectedFilter = CatalogMediaFilter.entries.firstOrNull { it.name == filterKey } ?: initialFilter
    val selectedSort = CatalogGridSort.entries.firstOrNull { it.name == sortKey } ?: CatalogGridSort.FEATURED
    val visibleItems = remember(items, selectedFilter, selectedSort) {
        applyCatalogGridOptions(items, selectedFilter, selectedSort)
    }
    var filterMenuExpanded by remember { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val liveWatchedIds by remember(previewViewModel, profileId) {
        previewViewModel.watchedIdsForProfile(profileId)
    }.collectAsStateWithLifecycle(initialValue = emptySet())
    val enrichedItems by remember(previewViewModel) {
        previewViewModel.state.map { it.enrichedMeta }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = emptyMap())
    var actionItem by remember { mutableStateOf<MetaItem?>(null) }
    var previewItem by remember { mutableStateOf<MetaItem?>(null) }
    var previewActive by remember { mutableStateOf(false) }
    var gridHasFocus by remember { mutableStateOf(false) }
    var originalPosterFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val actionScope = rememberCoroutineScope()
    var actionBounds by remember { mutableStateOf(Rect.Zero) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val landscapeCards = LocalTitleCardShape.current == "landscape"
    val columnCount = if (landscapeCards) 4 else COLUMNS
    
    val startPadding = 50.dp
    val gridCardWidth = ((LocalConfiguration.current.screenWidthDp.dp - startPadding - 50.dp -
        20.dp * (columnCount - 1).toFloat()) / columnCount).coerceAtLeast(120.dp)
    
    // Header height for pivot calculation
    val headerHeight = 48.dp
    val headerHeightPx = with(density) { headerHeight.toPx() }
    
    val dpadRepeatGate = remember {
        DpadRepeatGate(
            horizontalRepeatIntervalMs = DPAD_REPEAT_INTERVAL_HORIZONTAL_MS,
            verticalRepeatIntervalMs = DPAD_REPEAT_INTERVAL_VERTICAL_MS
        )
    }
    
    // Create pivot spec - items scroll to just below the fixed header
    val pivotSpec = remember(headerHeightPx) {
        FocusPivotSpec(
            customOffset = headerHeightPx + 16f,
        )
    }

    val itemKeys = remember(visibleItems) { visibleItems.map(MetaItem::canonicalPresentationKey) }
    val lazyItemKeys = remember(title, itemKeys) { homeRowItemLazyKeys("grid:$title", itemKeys) }
    val itemIndexByKey = remember(itemKeys) { firstIndexByKey(itemKeys) }
    val restoredFocusIndex = resolveFocusedItemIndex(itemKeys, lastFocusedItemKey, lastFocusedIndex, itemIndexByKey)
    val requestedFocusIndex = restoredFocusIndex ?: 0
    
    // Restore the saved viewport independently from the focused card. If that card
    // is still visible, keep the user's scroll position; only move when it is not.
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = initialScrollIndex.coerceAtLeast(0),
        initialFirstVisibleItemScrollOffset = initialScrollOffset
    )
    val artworkDispatcher = remember(context, artworkPrefetchDispatcher) {
        artworkPrefetchDispatcher ?: ArtworkPrefetchDispatcher.forCoil(context)
    }
    var focusedArtworkKey by remember(gridState) { mutableStateOf<String?>(null) }
    GridArtworkPrefetchEffect(
        items = visibleItems,
        enrichedItems = enrichedItems,
        gridState = gridState,
        focusedItemKey = focusedArtworkKey,
        landscapeCards = landscapeCards,
        dispatcher = artworkDispatcher,
        posterSize = if (landscapeCards) null else androidx.compose.ui.unit.DpSize(
            gridCardWidth, gridCardWidth * 1.5f
        ),
        landscapeSize = if (landscapeCards) androidx.compose.ui.unit.DpSize(
            gridCardWidth, gridCardWidth * (9f / 16f)
        ) else null,
        onPrepareItem = { if (landscapeCards) previewViewModel.ensureTmdbEnrichment(it) }
    )
    var lastSavedScrollIndex by remember { mutableIntStateOf(Int.MIN_VALUE) }
    var lastSavedScrollOffset by remember { mutableIntStateOf(Int.MIN_VALUE) }

    // Persist scroll state with throttling, then flush exact final position on dispose.
    LaunchedEffect(gridState) {
        snapshotFlow { Pair(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset) }
            .collect { (index, offset) ->
                val shouldPersist =
                    lastSavedScrollIndex == Int.MIN_VALUE ||
                        index != lastSavedScrollIndex ||
                        kotlin.math.abs(offset - lastSavedScrollOffset) >= 36

                if (shouldPersist) {
                    lastSavedScrollIndex = index
                    lastSavedScrollOffset = offset
                    onScrollPositionChange(index, offset)
                }
            }
    }

    DisposableEffect(gridState) {
        onDispose {
            val finalIndex = gridState.firstVisibleItemIndex
            val finalOffset = gridState.firstVisibleItemScrollOffset
            if (finalIndex != lastSavedScrollIndex || finalOffset != lastSavedScrollOffset) {
                onScrollPositionChange(finalIndex, finalOffset)
            }
        }
    }
    
    // Observe layout outside composition and request at most once per current item batch.
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    val paginationGate = remember(title) { GridPaginationGate() }
    LaunchedEffect(gridState, title, items.size, visibleItems.size) {
        val itemCount = visibleItems.size
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisibleIndex ->
                if (paginationGate.shouldLoadMore(itemCount, lastVisibleIndex, items.size)) {
                    currentOnLoadMore()
                }
            }
    }
    
    // Entry focus requester
    val entryRequester = remember { FocusRequester() }
    val backIconRequester = remember { FocusRequester() }
    val filterRequester = remember { FocusRequester() }
    val emptyStateActionRequester = remember { FocusRequester() }
    val cardRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    var pendingDirectionalTargetIndex by remember(itemKeys) { mutableStateOf<Int?>(null) }
    var lastReportedFocusKey by remember(itemKeys) { mutableStateOf<String?>(null) }
    var isBackIconFocused by remember { mutableStateOf(false) }
    var lastFocusedPosterIndex by remember {
        mutableIntStateOf(requestedFocusIndex.coerceIn(0, (visibleItems.lastIndex).coerceAtLeast(0)))
    }
    
    // Track if focus has been restored to prevent re-triggering
    var focusRestored by remember { mutableStateOf(false) }

    val dispatchBackFromKey = com.saab.tv.ui.navigation.rememberAppBackKeyAction(onBack)
    com.saab.tv.ui.navigation.AppBackHandler(priority = com.saab.tv.ui.navigation.AppBackPriority.Screen) {
        if (isBackIconFocused) {
            onBack()
        } else {
            backIconRequester.requestFocusSafely()
        }
    }

    // Keep the saved viewport when it still contains the selected card; otherwise
    // bring that card into view before restoring focus.
    LaunchedEffect(itemKeys, lastFocusedItemKey, restoredFocusIndex, gridState.layoutInfo.totalItemsCount) {
        if (visibleItems.isEmpty()) {
            focusRestored = false
            withFrameNanos { }
            backIconRequester.requestFocusSafely()
            return@LaunchedEffect
        }
        val focusedItemWasRemoved = lastFocusedItemKey != null && lastFocusedItemKey !in itemKeys
        if (gridState.layoutInfo.totalItemsCount > 0 && (!focusRestored || focusedItemWasRemoved)) {
            withFrameNanos { }
            val targetIndex = requestedFocusIndex.coerceIn(0, visibleItems.lastIndex)
            if (gridState.layoutInfo.visibleItemsInfo.none { it.index == targetIndex }) {
                gridState.scrollToItem(targetIndex)
            }
            entryRequester.requestFocusWhenAttached()
            focusRestored = true
        }
    }

    // Scroll and restore as one cancellable operation so newer D-pad input wins.
    LaunchedEffect(gridState, itemKeys) {
        snapshotFlow { pendingDirectionalTargetIndex }
            .distinctUntilChanged()
            .collectLatest { target ->
                if (target != null && target in visibleItems.indices) {
                    if (gridState.layoutInfo.visibleItemsInfo.none { it.index == target }) {
                        gridState.scrollToItemUsingSaabMotion(target)
                    }
                    withFrameNanos { }
                    cardRequesters[target]?.requestFocusWhenAttached()
                }
            }
    }

    // Use theme background color
    val backgroundColor = MaterialTheme.colorScheme.background
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        // ══════════════════════════════════════════════════════════════
        // SCROLLABLE GRID - Positioned first so header overlays it
        // ══════════════════════════════════════════════════════════════
        CompositionLocalProvider(LocalBringIntoViewSpec provides pivotSpec,
            com.saab.tv.ui.components.LocalWatchedIds provides liveWatchedIds,
            com.saab.tv.ui.components.LocalPosterFocusReturn provides { originalPosterFocus = it }) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columnCount),
                state = gridState,
                contentPadding = PaddingValues(
                    start = startPadding,
                    end = 50.dp,
                    top = headerHeight + 8.dp, // Start below header
                    bottom = 78.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxSize().zIndex(
                    if (com.saab.tv.ui.trailer.InlineTrailerAnchor.session != null) 3f else 0f)
                    .onFocusChanged {
                        gridHasFocus = it.hasFocus
                        if (!it.hasFocus) lastReportedFocusKey = null
                    }
            ) {
                itemsIndexed(
                    items = visibleItems,
                    key = { index, _ -> lazyItemKeys[index] }
                ) { index, item ->
                    
                    val shouldRequestFocus = index == requestedFocusIndex
                    val itemRequester = remember(index) { FocusRequester() }
                    val effectiveRequester = if (shouldRequestFocus) entryRequester else itemRequester
                    DisposableEffect(index, effectiveRequester) {
                        cardRequesters[index] = effectiveRequester
                        onDispose {
                            if (cardRequesters[index] === effectiveRequester) {
                                cardRequesters.remove(index)
                            }
                        }
                    }
                    
                    SaabTvCard(
                        previewItem = enrichedItems[item.canonicalPresentationKey()] ?: item,
                        title = item.name,
                        posterUrl = item.poster,
                        normalWidth = gridCardWidth,
                        normalHeight = gridCardWidth * 1.5f,
                        landscapeWidth = gridCardWidth,
                        onClick = { onMovieClick(item) },
                        onLongClick = { bounds -> actionBounds = bounds; actionItem = item },
                        isWatched = item.id in liveWatchedIds,
                        modifier = Modifier
                            .then(if (landscapeCards) Modifier else Modifier.aspectRatio(2f / 3f))
                            .onPreviewKeyEvent { keyEvent ->
                                if (com.saab.tv.ui.trailer.InlineTrailerAnchor.session != null) return@onPreviewKeyEvent false
                                if (dpadRepeatGate.shouldConsume(keyEvent)) return@onPreviewKeyEvent true
                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    when (keyEvent.key) {
                                        // Block UP navigation on first row
                                        Key.DirectionUp -> {
                                            if (index < columnCount) {
                                                pendingDirectionalTargetIndex = null
                                                return@onPreviewKeyEvent backIconRequester.requestFocusSafely()
                                            }
                                            val targetIndex = index - columnCount
                                            val targetRequester = cardRequesters[targetIndex]
                                            if (targetRequester?.requestFocusSafely() == true) {
                                                pendingDirectionalTargetIndex = null
                                                return@onPreviewKeyEvent true
                                            } else {
                                                // Let native focus/scroll happen, then correct to the intended same-column target.
                                                pendingDirectionalTargetIndex = targetIndex
                                                return@onPreviewKeyEvent false
                                            }
                                        }
                                        Key.DirectionDown -> {
                                            val targetIndex = index + columnCount
                                            if (targetIndex >= visibleItems.size) {
                                                pendingDirectionalTargetIndex = null
                                                return@onPreviewKeyEvent true
                                            }
                                            val targetRequester = cardRequesters[targetIndex]
                                            if (targetRequester?.requestFocusSafely() == true) {
                                                pendingDirectionalTargetIndex = null
                                                return@onPreviewKeyEvent true
                                            } else {
                                                // Let native focus/scroll happen, then correct to the intended same-column target.
                                                pendingDirectionalTargetIndex = targetIndex
                                                return@onPreviewKeyEvent false
                                            }
                                        }
                                        // Block LEFT navigation on first column (no exit on left key)
                                        Key.DirectionLeft -> {
                                            pendingDirectionalTargetIndex = null
                                            if (index % columnCount == 0) {
                                                return@onPreviewKeyEvent true
                                            }
                                        }
                                        Key.DirectionRight -> {
                                            pendingDirectionalTargetIndex = null
                                        }
                                    }
                                }
                                false
                            }
                            .onFocusChanged {
                                if (it.isFocused) {
                                    previewItem = item
                                    focusedArtworkKey = itemKeys[index]
                                    previewViewModel.ensureTmdbEnrichment(item)
                                    if (lastReportedFocusKey != itemKeys[index]) {
                                        lastReportedFocusKey = itemKeys[index]
                                        onFocusChange(index, itemKeys[index])
                                    }
                                    lastFocusedPosterIndex = index
                                    val pendingTarget = pendingDirectionalTargetIndex
                                    if (pendingTarget != null) {
                                        if (index == pendingTarget) {
                                            // Correction reached intended item.
                                            pendingDirectionalTargetIndex = null
                                        } else {
                                            // Keep pending until correction actually succeeds.
                                            cardRequesters[pendingTarget]?.requestFocusSafely()
                                        }
                                    }
                                }
                            }
                            .focusRequester(effectiveRequester)
                    )
                }
                if (hasMoreItems) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "grid-load-more") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            when {
                                isLoadingMore -> Text(stringResource(R.string.catalog_grid_loading_more), color = Color.White.copy(alpha = 0.7f))
                                hasLoadMoreError -> {
                                    Text(stringResource(R.string.catalog_grid_page_failed), color = Color.White.copy(alpha = 0.75f))
                                    TextButton(onClick = {
                                        paginationGate.reset()
                                        onRetryLoadMore()
                                    }, modifier = Modifier.testTag("grid-load-more-retry")) {
                                        Text(stringResource(R.string.common_retry), color = Color.White)
                                    }
                                }
                                else -> TextButton(onClick = {
                                    paginationGate.reset()
                                    onLoadMore()
                                }, modifier = Modifier.testTag("grid-load-more-button")) {
                                    Text(stringResource(R.string.catalog_grid_load_more), color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (visibleItems.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(top = headerHeight + 48.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when {
                    isInitialLoading -> Text(stringResource(R.string.catalog_grid_loading), color = Color.White.copy(alpha = 0.75f))
                    hasInitialLoadError -> {
                        Text(stringResource(R.string.catalog_grid_page_failed), color = Color.White.copy(alpha = 0.75f))
                        TextButton(
                            onClick = onRetryInitialLoad,
                            modifier = Modifier.focusRequester(emptyStateActionRequester).testTag("grid-initial-retry")
                        ) {
                            Text(stringResource(R.string.common_retry), color = Color.White)
                        }
                    }
                    else -> {
                        Text(
                            text = stringResource(
                                if (items.isEmpty()) R.string.catalog_grid_empty_title else R.string.catalog_grid_no_filter_results
                            ),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White
                        )
                        when {
                            isLoadingMore -> Text(
                                stringResource(R.string.catalog_grid_loading_more),
                                color = Color.White.copy(alpha = 0.75f)
                            )
                            hasLoadMoreError -> {
                                Text(
                                    stringResource(R.string.catalog_grid_page_failed),
                                    color = Color.White.copy(alpha = 0.75f)
                                )
                                TextButton(
                                    onClick = {
                                        paginationGate.reset()
                                        onRetryLoadMore()
                                    },
                                    modifier = Modifier.focusRequester(emptyStateActionRequester)
                                        .testTag("grid-empty-page-retry")
                                ) {
                                    Text(stringResource(R.string.common_retry), color = Color.White)
                                }
                            }
                            hasMoreItems -> TextButton(
                                onClick = {
                                    paginationGate.reset()
                                    onLoadMore()
                                },
                                modifier = Modifier.focusRequester(emptyStateActionRequester)
                                    .testTag("grid-empty-load-more")
                            ) {
                                Text(stringResource(R.string.catalog_grid_load_more), color = Color.White)
                            }
                        }
                    }
                }
            }
        }
        
        // ══════════════════════════════════════════════════════════════
        // FIXED HEADER - Overlays grid with gradient fade
        // ══════════════════════════════════════════════════════════════
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .zIndex(10f)
        ) {
            // Gradient background - extends beyond header for smooth fade
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(headerHeight + 48.dp) // Extended gradient
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                backgroundColor,
                                backgroundColor,
                                backgroundColor,
                                backgroundColor.copy(alpha = 0.95f),
                                backgroundColor.copy(alpha = 0.7f),
                                backgroundColor.copy(alpha = 0.3f),
                                Color.Transparent
                            ),
                            startY = 0f,
                            endY = with(density) { (headerHeight + 48.dp).toPx() }
                        )
                    )
            )
            
            // Header content - tight padding
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = startPadding,
                        top = 10.dp,
                        end = 50.dp,
                        bottom = 0.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back indicator (mirrored view more arrow)
                Box(
                    modifier = Modifier
                        .focusRequester(backIconRequester)
                        .onFocusChanged { isBackIconFocused = it.isFocused }
                        .onPreviewKeyEvent { event ->
                            if (event.isBackOrEscape()) {
                                if (event.isInitialBackPress()) dispatchBackFromKey()
                                return@onPreviewKeyEvent true
                            }
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> {
                                    if (visibleItems.isNotEmpty()) {
                                        val targetIndex = lastFocusedPosterIndex.coerceIn(0, visibleItems.lastIndex)
                                        val targetRequester = cardRequesters[targetIndex]
                                        if (targetRequester?.requestFocusSafely() == true) {
                                            pendingDirectionalTargetIndex = null
                                        } else {
                                            pendingDirectionalTargetIndex = targetIndex
                                        }
                                    } else if (
                                        hasInitialLoadError ||
                                        (hasLoadMoreError || hasMoreItems) && visibleItems.isEmpty()
                                    ) {
                                        emptyStateActionRequester.requestFocusSafely()
                                    } else {
                                        filterRequester.requestFocusSafely()
                                    }
                                    true
                                }
                                Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                                    onBack()
                                    true
                                }
                                Key.DirectionUp, Key.DirectionLeft -> true
                                else -> false
                            }
                        }
                        .focusable()
                        .testTag("grid-back-control")
                        .size(30.dp)
                        .graphicsLayer {
                            scaleX = -1f // Mirror the icon without changing its focus geometry.
                        }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_view_more_arrow),
                        contentDescription = stringResource(R.string.common_back),
                        tint = if (isBackIconFocused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxSize()
                    )
                }
                
                Spacer(modifier = Modifier.width(10.dp))
                
                // Category title
                Text(
                    text = title,
                    modifier = Modifier.widthIn(max = 420.dp),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.weight(1f))
                GridChoiceMenu(
                    label = stringResource(R.string.catalog_grid_filter_label),
                    value = when (selectedFilter) {
                        CatalogMediaFilter.ALL -> stringResource(R.string.catalog_grid_filter_all)
                        CatalogMediaFilter.MOVIES -> stringResource(R.string.catalog_grid_filter_movies)
                        CatalogMediaFilter.SERIES -> stringResource(R.string.catalog_grid_filter_series)
                    },
                    expanded = filterMenuExpanded,
                    onExpandedChange = { filterMenuExpanded = it },
                    options = listOf(
                        stringResource(R.string.catalog_grid_filter_all) to CatalogMediaFilter.ALL,
                        stringResource(R.string.catalog_grid_filter_movies) to CatalogMediaFilter.MOVIES,
                        stringResource(R.string.catalog_grid_filter_series) to CatalogMediaFilter.SERIES
                    ),
                    onSelect = { filterKey = it.name },
                    testTag = "grid-filter-button",
                    focusRequester = filterRequester
                )
                Spacer(Modifier.width(8.dp))
                GridChoiceMenu(
                    label = stringResource(R.string.catalog_grid_sort_label),
                    value = when (selectedSort) {
                        CatalogGridSort.FEATURED -> stringResource(R.string.catalog_grid_sort_featured)
                        CatalogGridSort.TITLE -> stringResource(R.string.catalog_grid_sort_title)
                        CatalogGridSort.NEWEST -> stringResource(R.string.catalog_grid_sort_newest)
                        CatalogGridSort.RATING -> stringResource(R.string.catalog_grid_sort_rating)
                    },
                    expanded = sortMenuExpanded,
                    onExpandedChange = { sortMenuExpanded = it },
                    options = listOf(
                        stringResource(R.string.catalog_grid_sort_featured) to CatalogGridSort.FEATURED,
                        stringResource(R.string.catalog_grid_sort_title) to CatalogGridSort.TITLE,
                        stringResource(R.string.catalog_grid_sort_newest) to CatalogGridSort.NEWEST,
                        stringResource(R.string.catalog_grid_sort_rating) to CatalogGridSort.RATING
                    ),
                    onSelect = { sortKey = it.name },
                    testTag = "grid-sort-button"
                )
            }
        }
    }
    actionItem?.let { item ->
        CompositionLocalProvider(com.saab.tv.ui.components.LocalWatchedIds provides liveWatchedIds) {
            CatalogQuickActionsPopup(item, actionBounds, profileId,
                onDismiss = {
                    actionItem = null
                    actionScope.launch {
                        withFrameNanos { }
                        originalPosterFocus.requestFocusOrFallback(entryRequester)
                    }
                }, onTrailerClick = onTrailerClick, viewModel = previewViewModel)
        }
    }
    com.saab.tv.ui.trailer.BackdropTrailerPreview(
        profileId = profileId,
        focusedItem = previewItem,
        settings = com.saab.tv.data.profile.rememberTrailerPreviewSettings(profileId),
        enabled = allowTrailerAutoplay && !com.saab.tv.ui.trailer.LocalManualTrailerActive.current && (gridHasFocus || previewActive) && !isBackIconFocused && actionItem == null,
        resolveTrailer = previewViewModel::trailerFor, onActiveChanged = { previewActive = it },
        onOpen = onMovieClick, onDismiss = { originalPosterFocus.requestFocusOrFallback(entryRequester) },
        homeViewModel = previewViewModel
    )
}

@Composable
private fun <T> GridChoiceMenu(
    label: String,
    value: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    options: List<Pair<String, T>>,
    onSelect: (T) -> Unit,
    testTag: String,
    focusRequester: FocusRequester? = null
) {
    Box {
        TextButton(
            onClick = { onExpandedChange(true) },
            modifier = Modifier.testTag(testTag)
                .then(if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester))
        ) {
            Text(
                stringResource(R.string.common_labeled_value, label, value),
                color = Color.White,
                maxLines = 1
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            options.forEach { (text, option) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onSelect(option)
                        onExpandedChange(false)
                    }
                )
            }
        }
    }
}
