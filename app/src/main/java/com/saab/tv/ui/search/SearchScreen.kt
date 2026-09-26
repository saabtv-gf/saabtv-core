package com.saab.tv.ui.search

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.remote_input.RemoteInputMode
import com.saab.tv.ui.addons.RemotePasteDialog
import com.saab.tv.ui.components.SaabTvBackground
import com.saab.tv.ui.components.SaabTvCard
import com.saab.tv.ui.common.ListStateSafety
import com.saab.tv.ui.details.FilterDropdown
import com.saab.tv.ui.home.DpadRepeatGate
import com.saab.tv.ui.home.ViewMoreCard
import com.saab.tv.ui.utils.ImagePrefetcher
import kotlinx.coroutines.delay

private const val PREVIEW_COUNT = 3
private val RESULT_POSTER_WIDTH = 118.dp
private val RESULT_POSTER_WIDTH_TOP_NAV = 116.dp
private val RESULT_POSTER_HEIGHT = 208.dp
private val RESULT_POSTER_HEIGHT_TOP_NAV = 150.dp

private fun FocusRequester.requestFocusSafely(): Boolean =
    runCatching {
        requestFocus()
        true
    }.getOrDefault(false)

@Composable
fun SearchScreen(
    entryRequester: FocusRequester, // Navigation -> Search field
    drawerRequester: FocusRequester, // Left -> Drawer
    searchSessionId: Long = 0L,
    viewModel: SearchViewModel = hiltViewModel(),
    currentProfile: ProfileEntity?,
    onMovieClick: (MetaItem) -> Unit,
    onViewMore: (title: String, items: List<MetaItem>) -> Unit = { _, _ -> },
    moviesViewMoreRequester: FocusRequester = remember { FocusRequester() },
    seriesViewMoreRequester: FocusRequester = remember { FocusRequester() },
    resultsRequester: FocusRequester = remember { FocusRequester() },
    discoverRequester: FocusRequester = remember { FocusRequester() },
    lastFocusedId: String? = null,
    onFocusedIdChange: (String?) -> Unit = {},
    onDiscoverClick: (MetaItem) -> Unit = onMovieClick,
    watchedIds: Set<String> = emptySet()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Internal Requesters
    val discoverGridEntryRequester = remember { FocusRequester() }

    var showRemoteSearch by remember { mutableStateOf(false) }

    val isTopNav = currentProfile?.navPosition == "top"

    var isContentFocused by remember { mutableStateOf(false) }
    // Guards against double-back race: when returning from details, focus restoration
    // takes ~200ms. Until focus is established, keep BackHandler enabled so a quick
    // second back press doesn't exit the app. Resets on each fresh composition.
    var focusEverSet by remember { mutableStateOf(false) }

    // Hoisted so it survives DiscoverGrid being removed/re-added when query toggles around 3 chars
    var discoverFocusRestored by remember { mutableStateOf(false) }

    // BACK: Go to Drawer
    BackHandler(enabled = !isTopNav || isContentFocused || !focusEverSet) {
        drawerRequester.requestFocusSafely()
    }

    // A new visit starts with the TV QWERTY keyboard. Returning from details
    // keeps the same query and result set.
    LaunchedEffect(searchSessionId) {
        if (viewModel.beginSearchSession(searchSessionId)) {
            delay(180)
            entryRequester.requestFocusSafely()
        }
    }

    val topPadding = if (isTopNav) 48.dp else 0.dp
    val startPadding = if (isTopNav) 50.dp else 90.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged {
                isContentFocused = it.hasFocus
                if (it.hasFocus) focusEverSet = true
            }
    ) {
        SaabTvBackground {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = startPadding, end = 50.dp, bottom = 0.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp)
        ) {

            // --- LEFT PANE: SEARCH ACTIONS + DISCOVER FILTERS ---
            Column(
                modifier = Modifier
                    .width(280.dp)
                    .fillMaxHeight()
                    .padding(top = 20.dp + topPadding)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Top
            ) {
                TvKeyboard(
                    onKeyPress = { viewModel.onQueryChange(state.query + it) },
                    onBackspace = { viewModel.onQueryChange(state.query.dropLast(1)) },
                    onSpace = { viewModel.onQueryChange(state.query + " ") },
                    onClear = { viewModel.clearSearch() },
                    onOpenRemoteInput = { showRemoteSearch = true },
                    entryRequester = entryRequester,
                    drawerRequester = drawerRequester,
                    isTopNav = isTopNav,
                    hasResults = state.results.isNotEmpty() || state.discoverItems.isNotEmpty(),
                    contentEntryRequester = if (state.query.length >= 3) resultsRequester else discoverGridEntryRequester,
                    modifier = Modifier.height(325.dp)
                )

                // DISCOVER FILTERS — greyed out with fade when searching
                if (state.discoverCatalogs.isNotEmpty()) {
                    val isDiscoverActive = state.query.length < 3
                    val filterAlpha by animateFloatAsState(
                        targetValue = if (isDiscoverActive) 1f else 0.3f,
                        animationSpec = tween(durationMillis = 300),
                        label = "discover_filter_alpha"
                    )
                    val filterGap = if (isTopNav) 4.dp else 8.dp
                    val filterRightInterceptor = if (isDiscoverActive) {
                        Modifier.onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight
                                && state.discoverItems.isNotEmpty()
                            ) {
                                discoverGridEntryRequester.requestFocusSafely()
                                true
                            } else false
                        }
                    } else Modifier

                    Spacer(modifier = Modifier.height(if (isTopNav) 8.dp else 16.dp))

                    Column(
                        modifier = Modifier
                            .graphicsLayer { alpha = filterAlpha }
                            .focusProperties { canFocus = isDiscoverActive }
                    ) {
                        Text(
                            text = "Discover",
                            style = TvMaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = Color.White.copy(0.9f),
                            modifier = Modifier.padding(bottom = filterGap)
                        )

                        // Type dropdown
                        FilterDropdown(
                            currentValue = state.selectedType.replaceFirstChar { it.uppercase() },
                            options = state.availableTypes.map { it.replaceFirstChar { c -> c.uppercase() } },
                            modifier = Modifier.fillMaxWidth().then(filterRightInterceptor),
                            onSelect = { selected ->
                                viewModel.selectType(selected.lowercase())
                            }
                        )
                        Spacer(modifier = Modifier.height(filterGap))

                        // Catalog dropdown
                        val selectedCatalog = state.selectedCatalog
                        if (selectedCatalog != null) {
                            FilterDropdown(
                                currentValue = selectedCatalog.catalogName,
                                options = state.availableCatalogs.map { it.catalogName },
                                modifier = Modifier.fillMaxWidth().then(filterRightInterceptor),
                                onSelect = { selected ->
                                    val catalog = state.availableCatalogs.find { it.catalogName == selected }
                                    if (catalog != null) viewModel.selectCatalog(catalog)
                                }
                            )
                            Spacer(modifier = Modifier.height(filterGap))
                        }

                        // Genre dropdown (only if genres exist)
                        if (state.availableGenres.size > 1) {
                            FilterDropdown(
                                currentValue = state.selectedGenre ?: "All",
                                options = state.availableGenres,
                                modifier = Modifier.fillMaxWidth().then(filterRightInterceptor),
                                onSelect = { viewModel.selectGenre(it) }
                            )
                        }
                    }
                }
            }

            // --- RIGHT PANE: RESULTS ---
            val searchBarHeight = 48.dp
            val density = LocalDensity.current

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(top = 20.dp + topPadding)
            ) {
                // CONTENT AREA (behind header)
                if (state.isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                } else if (state.results.isEmpty() && state.query.length >= 3) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No results for \"${state.query}\"", color = Color.White.copy(0.5f))
                    }
                } else if (state.query.length < 3) {
                    // DISCOVER MODE
                    if (state.discoverCatalogs.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Search for movies, series, and more", color = Color.White.copy(0.3f))
                        }
                    } else if (state.isDiscoverLoading && state.discoverItems.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    } else if (state.discoverItems.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No content available", color = Color.White.copy(0.3f))
                        }
                    } else {
                        DiscoverGrid(
                            items = state.discoverItems,
                            onItemClick = { item ->
                                onFocusedIdChange(item.id)
                                onDiscoverClick(item)
                            },
                            onLoadMore = { viewModel.loadMoreDiscover() },
                            discoverRequester = discoverRequester,
                            lastFocusedId = lastFocusedId,
                            onFocusedIdChange = onFocusedIdChange,
                            keyboardRequester = entryRequester,
                            gridEntryRequester = discoverGridEntryRequester,
                            headerHeight = searchBarHeight,
                            initialScrollIndex = viewModel.discoverScrollIndex,
                            initialScrollOffset = viewModel.discoverScrollOffset,
                            onScrollPositionChange = { index, offset ->
                                viewModel.updateDiscoverScrollPosition(index, offset)
                            },
                            focusRestored = discoverFocusRestored,
                            onFocusRestored = { discoverFocusRestored = true },
                            modifier = Modifier.fillMaxSize(),
                            watchedIds = watchedIds
                        )
                    }
                } else {
                    val posterWidth = if (isTopNav) RESULT_POSTER_WIDTH_TOP_NAV else RESULT_POSTER_WIDTH
                    val posterHeight = if (isTopNav) RESULT_POSTER_HEIGHT_TOP_NAV else RESULT_POSTER_HEIGHT
                    val posterFocusTargetId = remember(state.movies, state.series, lastFocusedId) {
                        val visibleItems = state.movies.take(PREVIEW_COUNT) + state.series.take(PREVIEW_COUNT)
                        lastFocusedId?.takeIf { rememberedId ->
                            visibleItems.any { it.id == rememberedId }
                        } ?: visibleItems.firstOrNull()?.id
                    }
                    // Results: static layout, two category rows that fill the space
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = searchBarHeight + 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // ═══════════════════════════════════════
                        // MOVIES SECTION
                        // ═══════════════════════════════════════
                        if (state.movies.isNotEmpty()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Movies",
                                    style = TvMaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = Color.White.copy(0.9f),
                                    modifier = Modifier.padding(bottom = if (isTopNav) 12.dp else 4.dp)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = if (isTopNav) 6.dp else 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    val blockUp = Modifier.onPreviewKeyEvent { event ->
                                        event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp
                                    }
                                    val moviePreview = state.movies.take(PREVIEW_COUNT)
                                    moviePreview.forEachIndexed { index, movie ->
                                        val shouldAttachRequester = movie.id == posterFocusTargetId

                                        SaabTvCard(
                                            title = movie.name,
                                            posterUrl = movie.poster,
                                            onClick = { onMovieClick(movie) },
                                            isWatched = movie.id in watchedIds,
                                            modifier = Modifier
                                                .width(posterWidth)
                                                .height(posterHeight)
                                                .then(blockUp)
                                                .onFocusChanged { if (it.isFocused) onFocusedIdChange(movie.id) }
                                                .then(if (shouldAttachRequester) Modifier.focusRequester(resultsRequester) else Modifier)
                                        )
                                    }

                                    // ViewMore card for Movies
                                    ViewMoreCard(
                                        onClick = { onViewMore("Movies", state.movies) },
                                        modifier = Modifier
                                            .width(posterWidth)
                                            .height(posterHeight)
                                            .then(blockUp)
                                            .focusRequester(moviesViewMoreRequester)
                                            .onFocusChanged { if (it.isFocused) onFocusedIdChange("viewmore_movies") }
                                    )
                                }
                            }
                        }

                        // ═══════════════════════════════════════
                        // SERIES SECTION
                        // ═══════════════════════════════════════
                        if (state.series.isNotEmpty()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Series",
                                    style = TvMaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = Color.White.copy(0.9f),
                                    modifier = Modifier.padding(bottom = if (isTopNav) 12.dp else 4.dp)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = if (isTopNav) 6.dp else 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    val seriesPreview = state.series.take(PREVIEW_COUNT)
                                    seriesPreview.forEachIndexed { index, series ->
                                        val isRemembered = series.id == posterFocusTargetId

                                        SaabTvCard(
                                            title = series.name,
                                            posterUrl = series.poster,
                                            onClick = { onMovieClick(series) },
                                            modifier = Modifier
                                                .width(posterWidth)
                                                .height(posterHeight)
                                                .onFocusChanged { if (it.isFocused) onFocusedIdChange(series.id) }
                                                .then(if (isRemembered) Modifier.focusRequester(resultsRequester) else Modifier)
                                        )
                                    }

                                    // ViewMore card for Series
                                    ViewMoreCard(
                                        onClick = { onViewMore("Series", state.series) },
                                        modifier = Modifier
                                            .width(posterWidth)
                                            .height(posterHeight)
                                            .focusRequester(seriesViewMoreRequester)
                                            .onFocusChanged { if (it.isFocused) onFocusedIdChange("viewmore_series") }
                                    )
                                }
                            }
                        }
                    }
                }

                // ══════════════════════════════════════════════════════════════
                // FIXED SEARCH BAR HEADER - Overlays content with gradient fade
                // ══════════════════════════════════════════════════════════════
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .zIndex(10f)
                        .layout { measurable, constraints ->
                            val extraPx = 20.dp.roundToPx()
                            val placeable = measurable.measure(
                                constraints.copy(maxWidth = constraints.maxWidth + extraPx * 2)
                            )
                            layout(constraints.maxWidth, placeable.height) {
                                placeable.place(-extraPx, 0)
                            }
                        }
                ) {
                    // Gradient background
                    val backgroundColor = MaterialTheme.colorScheme.background
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(searchBarHeight + 32.dp)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        backgroundColor,
                                        backgroundColor.copy(alpha = 0.95f),
                                        backgroundColor.copy(alpha = 0.7f),
                                        backgroundColor.copy(alpha = 0.3f),
                                        Color.Transparent
                                    ),
                                    startY = 0f,
                                    endY = with(density) { (searchBarHeight + 32.dp).toPx() }
                                )
                            )
                    )

                    // Search bar content
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(searchBarHeight)
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))

                        BasicTextField(
                                value = state.query,
                                onValueChange = { viewModel.onQueryChange(it) },
                                readOnly = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusProperties { canFocus = false },
                                textStyle = MaterialTheme.typography.headlineMedium.copy(
                                    color = Color.White,
                                    fontWeight = FontWeight.Normal
                                ),
                                cursorBrush = SolidColor(Color.White),
                                decorationBox = { innerTextField ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (state.query.isEmpty()) {
                                            Text(
                                                text = "Type to search...",
                                                style = MaterialTheme.typography.headlineMedium.copy(
                                                    fontSize = 20.sp,
                                                    fontWeight = FontWeight.Normal
                                                ),
                                                color = Color.White.copy(alpha = 0.5f)
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                    }
                }
            }
        }
    }
    }

    if (showRemoteSearch) {
        RemotePasteDialog(
            onDismissRequest = { showRemoteSearch = false },
            onUrlReceived = { query ->
                viewModel.onQueryChange(query.trim())
                showRemoteSearch = false
            },
            mode = RemoteInputMode.SEARCH,
            title = "Remote Search",
            description = "Scan with your phone to type a search query"
        )
    }
}

private const val DISCOVER_COLUMNS = 5
private const val DISCOVER_DPAD_REPEAT_HORIZONTAL_MS = 150L
private const val DISCOVER_DPAD_REPEAT_VERTICAL_MS = 200L

@OptIn(ExperimentalFoundationApi::class)
private class DiscoverPivotSpec(
    private val pivotOffset: Float
) : BringIntoViewSpec {

    @Deprecated("", level = DeprecationLevel.HIDDEN)
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float>
        get() = androidx.compose.animation.core.spring(
            stiffness = androidx.compose.animation.core.Spring.StiffnessLow,
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
            visibilityThreshold = 0.1f
        )

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val targetPosition = pivotOffset
        val currentPosition = offset
        return currentPosition - targetPosition
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiscoverGrid(
    items: List<MetaItem>,
    onItemClick: (MetaItem) -> Unit,
    onLoadMore: () -> Unit,
    discoverRequester: FocusRequester,
    lastFocusedId: String?,
    onFocusedIdChange: (String?) -> Unit,
    keyboardRequester: FocusRequester,
    gridEntryRequester: FocusRequester,
    headerHeight: Dp = 48.dp,
    initialScrollIndex: Int = 0,
    initialScrollOffset: Int = 0,
    onScrollPositionChange: (Int, Int) -> Unit = { _, _ -> },
    focusRestored: Boolean = false,
    onFocusRestored: () -> Unit = {},
    modifier: Modifier = Modifier,
    watchedIds: Set<String> = emptySet()
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val dpadRepeatGate = remember {
        DpadRepeatGate(
            horizontalRepeatIntervalMs = DISCOVER_DPAD_REPEAT_HORIZONTAL_MS,
            verticalRepeatIntervalMs = DISCOVER_DPAD_REPEAT_VERTICAL_MS
        )
    }

    // Pivot spec: items scroll to just below the header
    val headerHeightPx = with(density) { headerHeight.toPx() }
    val pivotSpec = remember(headerHeightPx) { DiscoverPivotSpec(pivotOffset = headerHeightPx + 16f) }

    // Compute restore index from lastFocusedId so the grid starts at the right position
    val matchedRestoreIndex = lastFocusedId?.let { id -> items.indexOfFirst { it.id == id } } ?: -1
    val requestedRestoreIndex = matchedRestoreIndex.takeIf { it >= 0 } ?: initialScrollIndex
    val restoreIndex = ListStateSafety.boundedIndex(requestedRestoreIndex, items.size)
    val restoreOffset = if (restoreIndex != initialScrollIndex || requestedRestoreIndex != restoreIndex) {
        0
    } else initialScrollOffset.coerceAtLeast(0)
    val restoreFocusIndex = matchedRestoreIndex.takeIf { it >= 0 } ?: restoreIndex

    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = restoreIndex,
        initialFirstVisibleItemScrollOffset = restoreOffset
    )
    val cardRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    var pendingDirectionalTargetIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(items.size) {
        if (items.isNotEmpty() && gridState.firstVisibleItemIndex > items.lastIndex) {
            gridState.scrollToItem(items.lastIndex)
        }
        pendingDirectionalTargetIndex = pendingDirectionalTargetIndex?.takeIf { it in items.indices }
    }

    // Persist scroll position back to ViewModel
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> onScrollPositionChange(index, offset) }
    }

    // First fully visible item in first column — entry target from keyboard/filters
    // Items partially scrolled behind the header have negative offset.y, so skip those
    val firstVisibleFirstColIndex by remember {
        derivedStateOf {
            gridState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index % DISCOVER_COLUMNS == 0 && it.offset.y >= 0 }
                ?.index ?: 0
        }
    }

    // Prefetch image URLs
    val imageUrls = remember(items) { items.map { it.poster } }

    // Pagination trigger
    val lastVisibleIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
    LaunchedEffect(lastVisibleIndex, items.size) {
        if (items.isNotEmpty() && lastVisibleIndex >= items.size - 12) {
            onLoadMore()
        }
    }

    // Focus restoration — only when returning from details (lastFocusedId is set)
    LaunchedEffect(lastFocusedId, gridState.layoutInfo.totalItemsCount) {
        if (!focusRestored && lastFocusedId != null && gridState.layoutInfo.totalItemsCount > 0) {
            kotlinx.coroutines.delay(16)
            if (discoverRequester.requestFocusSafely()) onFocusRestored()
        }
    }

    // Pending directional target correction
    LaunchedEffect(gridState) {
        snapshotFlow { pendingDirectionalTargetIndex to gridState.layoutInfo.visibleItemsInfo.map { it.index } }
            .collect { (pendingTarget, visibleIndexes) ->
                if (pendingTarget != null && visibleIndexes.contains(pendingTarget)) {
                    cardRequesters[pendingTarget]?.requestFocusSafely()
                }
            }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides pivotSpec) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(DISCOVER_COLUMNS),
            state = gridState,
            contentPadding = PaddingValues(top = headerHeight + 8.dp, bottom = 78.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = modifier
        ) {
            itemsIndexed(
                items = items,
                key = { index, item -> "${item.id}_$index" }
            ) { index, item ->
                val isRestoreTarget = index == restoreFocusIndex

                val itemRequester = remember(index) { FocusRequester() }
                val effectiveRequester = if (isRestoreTarget) discoverRequester else itemRequester

                DisposableEffect(index, effectiveRequester) {
                    cardRequesters[index] = effectiveRequester
                    onDispose {
                        if (cardRequesters[index] === effectiveRequester) {
                            cardRequesters.remove(index)
                        }
                    }
                }

                val isGridEntry = index == firstVisibleFirstColIndex

                SaabTvCard(
                    title = item.name,
                    posterUrl = item.poster,
                    onClick = { onItemClick(item) },
                    isWatched = item.id in watchedIds,
                    modifier = Modifier
                        .aspectRatio(2f / 3f)
                        .onPreviewKeyEvent { keyEvent ->
                            if (dpadRepeatGate.shouldConsume(keyEvent)) return@onPreviewKeyEvent true
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                when (keyEvent.key) {
                                    Key.DirectionUp -> {
                                        if (index < DISCOVER_COLUMNS) {
                                            // First row: block — stay on current item
                                            pendingDirectionalTargetIndex = null
                                            return@onPreviewKeyEvent true
                                        }
                                        val targetIndex = index - DISCOVER_COLUMNS
                                        val targetRequester = cardRequesters[targetIndex]
                                        if (targetRequester != null) {
                                            pendingDirectionalTargetIndex = null
                                            targetRequester.requestFocusSafely()
                                            return@onPreviewKeyEvent true
                                        } else {
                                            pendingDirectionalTargetIndex = targetIndex
                                            return@onPreviewKeyEvent false
                                        }
                                    }
                                    Key.DirectionDown -> {
                                        val targetIndex = index + DISCOVER_COLUMNS
                                        if (targetIndex >= items.size) {
                                            pendingDirectionalTargetIndex = null
                                            return@onPreviewKeyEvent true
                                        }
                                        val targetRequester = cardRequesters[targetIndex]
                                        if (targetRequester != null) {
                                            pendingDirectionalTargetIndex = null
                                            targetRequester.requestFocusSafely()
                                            return@onPreviewKeyEvent true
                                        } else {
                                            pendingDirectionalTargetIndex = targetIndex
                                            return@onPreviewKeyEvent false
                                        }
                                    }
                                    Key.DirectionLeft -> {
                                        pendingDirectionalTargetIndex = null
                                        if (index % DISCOVER_COLUMNS == 0) {
                                            keyboardRequester.requestFocusSafely()
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
                                ImagePrefetcher.prefetchAround(context, imageUrls, index, count = 12)
                                onFocusedIdChange(item.id)
                                val pendingTarget = pendingDirectionalTargetIndex
                                if (pendingTarget != null) {
                                    if (index == pendingTarget) {
                                        pendingDirectionalTargetIndex = null
                                    } else {
                                        cardRequesters[pendingTarget]?.requestFocusSafely()
                                    }
                                }
                            }
                        }
                        .focusRequester(effectiveRequester)
                        .then(if (isGridEntry) Modifier.focusRequester(gridEntryRequester) else Modifier)
                )
            }
        }
    }
}

@Composable
fun TvKeyboard(
    onKeyPress: (String) -> Unit,
    onBackspace: () -> Unit,
    onSpace: () -> Unit,
    onClear: () -> Unit,
    onOpenRemoteInput: () -> Unit,
    entryRequester: FocusRequester,
    drawerRequester: FocusRequester,
    isTopNav: Boolean,
    hasResults: Boolean,
    contentEntryRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    val keys = remember {
        listOf(
            "q", "w", "e", "r", "t", "y",
            "u", "i", "o", "p", "a", "s",
            "d", "f", "g", "h", "j", "k",
            "l", "z", "x", "c", "v", "b",
            "n", "m", "1", "2", "3", "4",
            "5", "6", "7", "8", "9", "0"
        )
    }

    // MEMORY STATE: KEYBOARD
    var lastFocusedIndex by remember { mutableIntStateOf(0) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.fillMaxWidth(),
        userScrollEnabled = false,
        contentPadding = PaddingValues(top = 11.dp, start = 6.dp, end = 6.dp, bottom = 10.dp)
    ) {
        itemsIndexed(keys) { index, key ->
            val isLeftEdge = index % 6 == 0
            val isRightEdge = (index + 1) % 6 == 0
            val isTopRow = index < 6

            // MAGNET LOGIC: KEYBOARD
            val isRemembered = index == lastFocusedIndex

            KeyButton(
                text = key,
                onClick = { onKeyPress(key) },
                // Attach 'entryRequester' ONLY to the remembered item
                modifier = Modifier
                    .onFocusChanged { if (it.isFocused) lastFocusedIndex = index }
                    .then(if (isRemembered) Modifier.focusRequester(entryRequester) else Modifier)
                    .onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown) {
                            when {
                                // Left arrow on left edge -> go to drawer/topnav
                                isLeftEdge && it.key == Key.DirectionLeft -> {
                                    if (!isTopNav) drawerRequester.requestFocusSafely()
                                    true
                                }
                                // Right arrow on right edge -> focus first visible poster or block
                                isRightEdge && it.key == Key.DirectionRight -> {
                                    if (!hasResults) return@onPreviewKeyEvent true
                                    if (contentEntryRequester != null) {
                                        contentEntryRequester.requestFocusSafely()
                                        return@onPreviewKeyEvent true
                                    }
                                    false
                                }
                                // Up arrow on top row -> go to drawer/topnav
                                isTopRow && it.key == Key.DirectionUp -> {
                                    if (isTopNav) {
                                        drawerRequester.requestFocusSafely()
                                    }
                                    true // Always consume to prevent default focus search finding sidebar
                                }
                                else -> false
                            }
                        } else false
                    }
            )
        }

        val spaceIndex = keys.size
        item(span = { GridItemSpan(2) }) {
            val isRemembered = spaceIndex == lastFocusedIndex
            KeyButton(
                icon = Icons.Default.SpaceBar,
                onClick = onSpace,
                label = "Space",
                modifier = Modifier
                    .onFocusChanged { if (it.isFocused) lastFocusedIndex = spaceIndex }
                    .then(if (isRemembered) Modifier.focusRequester(entryRequester) else Modifier)
                    .onPreviewKeyEvent {
                        if (it.key == Key.DirectionLeft && it.type == KeyEventType.KeyDown) {
                            if (!isTopNav) drawerRequester.requestFocusSafely()
                            true
                        } else false
                    }
            )
        }

        val backIndex = keys.size + 1
        item(span = { GridItemSpan(2) }) {
            val isRemembered = backIndex == lastFocusedIndex
            KeyButton(
                icon = Icons.AutoMirrored.Filled.Backspace,
                onClick = onBackspace,
                label = "Back",
                modifier = Modifier
                    .onFocusChanged { if (it.isFocused) lastFocusedIndex = backIndex }
                    .then(if (isRemembered) Modifier.focusRequester(entryRequester) else Modifier)
            )
        }

        val clearIndex = keys.size + 2
        item(span = { GridItemSpan(2) }) {
            val isRemembered = clearIndex == lastFocusedIndex
            KeyButton(
                text = "Clear",
                onClick = onClear,
                label = "Clear Search",
                modifier = Modifier
                    .onFocusChanged { if (it.isFocused) lastFocusedIndex = clearIndex }
                    .then(if (isRemembered) Modifier.focusRequester(entryRequester) else Modifier)
                    .onPreviewKeyEvent {
                        if (it.key == Key.DirectionRight && it.type == KeyEventType.KeyDown) {
                            if (!hasResults) return@onPreviewKeyEvent true
                            if (contentEntryRequester != null) {
                                contentEntryRequester.requestFocusSafely()
                                return@onPreviewKeyEvent true
                            }
                            false
                        } else false
                    }
            )
        }

        val remoteInputIndex = keys.size + 3
        item(span = { GridItemSpan(6) }) {
            val isRemembered = remoteInputIndex == lastFocusedIndex
            KeyButton(
                text = "Scan QR To Type",
                icon = Icons.Default.QrCode2,
                label = "Remote Search",
                onClick = onOpenRemoteInput,
                modifier = Modifier
                    .onFocusChanged { if (it.isFocused) lastFocusedIndex = remoteInputIndex }
                    .then(if (isRemembered) Modifier.focusRequester(entryRequester) else Modifier)
                    .onPreviewKeyEvent {
                        when {
                            it.type != KeyEventType.KeyDown -> false
                            it.key == Key.DirectionLeft -> {
                                if (!isTopNav) drawerRequester.requestFocusSafely()
                                true
                            }
                            it.key == Key.DirectionRight && contentEntryRequester != null -> {
                                contentEntryRequester.requestFocusSafely()
                                true
                            }
                            it.key == Key.DirectionRight && !hasResults -> true
                            else -> false
                        }
                    }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun KeyButton(
    text: String? = null,
    icon: ImageVector? = null,
    label: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val dynamicContentColor = if (isFocused) Color.Black else Color.White

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .height(35.dp)
            .fillMaxWidth()
            .border(
                width = if (isFocused) 3.dp else 1.dp,
                color = if (isFocused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.14f),
                shape = RoundedCornerShape(4.dp)
            ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(0.1f),
            focusedContainerColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        modifier = Modifier.size(18.dp),
                        tint = dynamicContentColor
                    )
                }
                if (icon != null && text != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                }
                if (text != null) {
                Text(
                    text = text,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = dynamicContentColor
                )
                }
            }
        }
    }
}
