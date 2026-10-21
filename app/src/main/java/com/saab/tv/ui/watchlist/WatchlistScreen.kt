package com.saab.tv.ui.watchlist

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.saab.tv.R
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.schema.canonicalPresentationKey
import com.saab.tv.ui.components.LocalPosterFocusReturn
import com.saab.tv.ui.components.LocalWatchedIds
import com.saab.tv.ui.components.LocalTitleCardShape
import com.saab.tv.ui.components.SaabMotion
import com.saab.tv.ui.theme.accessibleContentColor
import com.saab.tv.ui.navigation.LocalNavigationFocusHandoff
import com.saab.tv.ui.home.CatalogQuickActionsPopup
import com.saab.tv.ui.home.CatalogGridSort
import com.saab.tv.ui.home.DpadRepeatGate
import com.saab.tv.ui.home.HomeViewModel
import com.saab.tv.ui.home.InfiniteLoopRow
import com.saab.tv.ui.home.focusKeyWithoutRowId
import com.saab.tv.ui.home.homeCardFocusKey
import com.saab.tv.ui.trailer.BackdropTrailerPreview
import com.saab.tv.ui.trailer.LocalManualTrailerActive
import com.saab.tv.ui.trailer.LocalManualTrailerLauncher
import com.saab.tv.data.profile.rememberTrailerPreviewSettings
import com.saab.tv.ui.utils.requestFocusOrFallback
import com.saab.tv.ui.utils.requestFocusSafely
import com.saab.tv.ui.utils.requestFocusWhenAttached
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun WatchlistScreen(
    currentProfile: ProfileEntity?,
    entryRequester: FocusRequester,
    drawerRequester: FocusRequester,
    onMovieClick: (MetaItem) -> Unit,
    onContinueClick: (MetaItem) -> Unit = onMovieClick,
    onTrailerClick: (String, String) -> Unit = { _, _ -> },
    onPreviewActiveChanged: (Boolean) -> Unit = {},
    watchedIds: Set<String> = emptySet(),
    viewModel: WatchlistViewModel = hiltViewModel(),
    actionsViewModel: HomeViewModel = hiltViewModel()
) {
    val authorizeNavigationHandoff = LocalNavigationFocusHandoff.current
    val storedLibrary by viewModel.libraryItems.collectAsStateWithLifecycle()
    val activeLibraryProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val library = storedLibrary.takeIf { activeLibraryProfileId == currentProfile?.id } ?: MyLibraryItems()
    val liveWatchedIds by remember(actionsViewModel, currentProfile?.id) {
        currentProfile?.id?.let(actionsViewModel::watchedIdsForProfile) ?: flowOf(emptySet())
    }.collectAsStateWithLifecycle(initialValue = emptySet())
    val enrichedItems by remember(actionsViewModel) {
        actionsViewModel.state.map { it.enrichedMeta }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = emptyMap())
    var actionItem by remember { mutableStateOf<MetaItem?>(null) }
    var actionSectionTitle by remember { mutableStateOf<String?>(null) }
    var actionBounds by remember { mutableStateOf(Rect.Zero) }
    var previewItem by remember { mutableStateOf<MetaItem?>(null) }
    var hasContentFocus by remember { mutableStateOf(false) }
    var previewActive by remember { mutableStateOf(false) }
    var originalPosterFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val actionScope = rememberCoroutineScope()
    var statusFilter by rememberSaveable(currentProfile?.id) { mutableStateOf(MyLibraryStatusFilter.ALL) }
    var mediaFilter by rememberSaveable(currentProfile?.id) { mutableStateOf(MyLibraryMediaFilter.ALL) }
    var sortKey by rememberSaveable(currentProfile?.id) { mutableStateOf(CatalogGridSort.FEATURED.name) }
    val selectedSort = remember(sortKey) {
        CatalogGridSort.entries.firstOrNull { it.name == sortKey } ?: CatalogGridSort.FEATURED
    }
    val isTopNav = currentProfile?.navPosition == "top"
    val landscapeCards = LocalTitleCardShape.current == "landscape"
    val startPadding = if (isTopNav) 50.dp else 120.dp
    val repeatGates = remember { listOf(DpadRepeatGate(), DpadRepeatGate(), DpadRepeatGate()) }
    val watchlistItems = remember(library.watchlist) { library.watchlist.map(LibraryCardProjection::asActionItem) }
    val filteredSections = remember(library, statusFilter, mediaFilter, selectedSort) {
        sortMyLibrarySections(filterMyLibrary(library, statusFilter, mediaFilter), selectedSort)
    }
    val rows = remember(filteredSections) {
        filteredSections.map { section -> section.title to section.items.map(LibraryCardProjection::asActionItem) }
    }
    val restoredFocus = remember(
        rows,
        viewModel.lastFocusedSectionTitle,
        viewModel.lastFocusedKey,
        viewModel.lastFocusedItemIndex
    ) {
        WatchlistFocusPolicy.resolve(
            rows,
            viewModel.lastFocusedSectionTitle,
            viewModel.lastFocusedKey,
            viewModel.lastFocusedItemIndex
        )
    }

    LaunchedEffect(currentProfile?.id) { viewModel.setProfileId(currentProfile?.id) }
    LaunchedEffect(actionsViewModel, currentProfile) {
        actionsViewModel.configureTmdbProfile(currentProfile)
    }
    LaunchedEffect(currentProfile?.id, currentProfile?.tmdbEnabled, landscapeCards, watchlistItems.map { it.type to it.id }) {
        actionsViewModel.configureTmdbProfile(currentProfile)
        if (landscapeCards) {
            // Warm a small first-screen window before D-pad focus reaches these cards.
            watchlistItems.take(8).forEach { item ->
                actionsViewModel.ensureTmdbEnrichment(item)
                if (currentProfile?.tmdbEnabled != true || item.poster.isNullOrBlank()) {
                    viewModel.resolvePosterIfNeeded(item)
                }
            }
        }
    }
    com.saab.tv.ui.navigation.AppBackHandler(
        enabled = hasContentFocus && actionItem == null && !previewActive,
        priority = com.saab.tv.ui.navigation.AppBackPriority.Screen
    ) {
        authorizeNavigationHandoff?.invoke(null)
        drawerRequester.requestFocusSafely()
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalWatchedIds provides liveWatchedIds,
        LocalPosterFocusReturn provides { originalPosterFocus = it }
    ) {
        Box(Modifier.fillMaxSize().onFocusChanged { hasContentFocus = it.hasFocus }.focusGroup()) {
            Column(
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = if (isTopNav) 28.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(R.string.library_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    modifier = Modifier.padding(start = startPadding, bottom = 4.dp)
                )
                Column(
                    Modifier.fillMaxWidth().padding(start = startPadding, end = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(Modifier.fillMaxWidth().focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MyLibraryStatusFilter.entries.forEach { filter ->
                            LibraryFilterChip(
                                label = stringResource(filter.labelRes),
                                selected = statusFilter == filter,
                                testTag = "library-status-${filter.name.lowercase()}",
                                onClick = { statusFilter = filter }
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MyLibraryMediaFilter.entries.forEach { filter ->
                            LibraryFilterChip(
                                label = stringResource(filter.labelRes),
                                selected = mediaFilter == filter,
                                testTag = "library-media-${filter.name.lowercase()}",
                                onClick = { mediaFilter = filter }
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        LibrarySortMenu(sort = selectedSort, onSort = { sortKey = it.name })
                    }
                }
                if (rows.isEmpty()) {
                    val emptyMessageRes = if (library.inProgress.isEmpty() && library.watchlist.isEmpty() && library.watched.isEmpty()) {
                        R.string.library_empty
                    } else {
                        when (mediaFilter) {
                            MyLibraryMediaFilter.MOVIES -> R.string.library_empty_movies
                            MyLibraryMediaFilter.SERIES -> R.string.library_empty_series
                            MyLibraryMediaFilter.ALL -> R.string.library_empty_titles
                        }
                    }
                    Box(
                        Modifier.fillMaxWidth().height(220.dp).testTag("library-empty-state")
                            .focusRequester(entryRequester).focusable(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(emptyMessageRes), color = Color.White.copy(alpha = 0.65f))
                    }
                } else {
                    rows.forEachIndexed { index, (title, items) ->
                        val sectionTitle = stringResource(librarySectionTitleResource(title))
                        val rowId = WatchlistFocusPolicy.rowId(title)
                        val rowFocus = restoredFocus?.takeIf { it.rowIndex == index }
                        val rowState = remember(title, rowFocus?.itemIndex) {
                            LazyListState(firstVisibleItemIndex = rowFocus?.itemIndex ?: 0)
                        }
                        InfiniteLoopRow(
                            startPadding = startPadding,
                            isTopNav = isTopNav,
                            rowIndex = index,
                            showWatchedBadge = title != LIBRARY_CONTINUE_WATCHING,
                            title = sectionTitle,
                            items = items,
                            onMovieClick = { item ->
                                if (title == LIBRARY_CONTINUE_WATCHING) onContinueClick(item) else onMovieClick(item)
                            },
                            onMovieLongClick = { item, _, bounds ->
                                actionItem = item
                                actionSectionTitle = title
                                actionBounds = bounds
                            },
                            onViewMore = {},
                            onFocused = { item, focusKey ->
                                item?.let {
                                    val itemIndex = focusKeyWithoutRowId(focusKey).substringAfterLast('_').toIntOrNull() ?: 0
                                    viewModel.rememberFocusedCard(title, it.canonicalPresentationKey(), itemIndex)
                                }
                                previewItem = item
                                item?.let(actionsViewModel::ensureTmdbEnrichment)
                                if (title == LIBRARY_WATCHLIST &&
                                    (currentProfile?.tmdbEnabled != true || item?.poster.isNullOrBlank())
                                ) item?.let(viewModel::resolvePosterIfNeeded)
                            },
                            entryRequester = entryRequester,
                            drawerRequester = drawerRequester,
                            focusRowId = rowId,
                            locallyFocusedItemId = rowFocus?.let {
                                homeCardFocusKey(index, rowId, it.itemId, it.itemIndex)
                            },
                            isGlobalFocusPresent = restoredFocus != null,
                            isFirstRow = index == 0,
                            isInfiniteLoopEnabled = false,
                            repeatGate = repeatGates[index],
                            externalListState = rowState,
                            isLandscapeCards = landscapeCards,
                            enrichedItems = enrichedItems,
                            onPrepareArtworkItem = actionsViewModel::ensureTmdbEnrichment,
                            onItemShown = { item ->
                                if (currentProfile?.tmdbEnabled != true || item.poster.isNullOrBlank()) {
                                    viewModel.resolvePosterIfNeeded(item)
                                }
                            },
                            onWatchedItemShown = { item ->
                                currentProfile?.id?.let { actionsViewModel.ensureWatchedAlias(it, item) }
                            }
                        )
                    }
                    Spacer(Modifier.height(120.dp))
                }
            }

            actionItem?.let { item -> currentProfile?.let { profile ->
                val dismissActions: () -> Unit = {
                    actionItem = null
                    actionSectionTitle = null
                    actionScope.launch {
                        withFrameNanos { }
                        originalPosterFocus.requestFocusOrFallback(entryRequester)
                    }
                }
                val mutationComplete: () -> Unit = {
                    actionScope.launch {
                        viewModel.refreshLibrary()
                        // The card may have moved to another section (for example,
                        // Watchlist -> Watched). Restore through the policy-selected
                        // entry requester after the new rows have been laid out.
                        entryRequester.requestFocusWhenAttached()
                    }
                }
                if (actionSectionTitle == LIBRARY_CONTINUE_WATCHING) {
                    val projection = library.inProgress.firstOrNull {
                        it.asActionItem().canonicalPresentationKey() == item.canonicalPresentationKey()
                    }
                    LibraryContinueQuickActionsPopup(
                        item = item,
                        positionMs = projection?.positionMs ?: 0L,
                        durationMs = projection?.durationMs ?: 0L,
                        bounds = actionBounds,
                        profileId = profile.id,
                        seekIntervalSeconds = profile.seekTimeIntervalSeconds,
                        onDismiss = dismissActions,
                        onResume = onContinueClick,
                        onMutationComplete = mutationComplete,
                        viewModel = actionsViewModel
                    )
                } else {
                    CatalogQuickActionsPopup(
                        item = item,
                        bounds = actionBounds,
                        profileId = profile.id,
                        onDismiss = dismissActions,
                        onTrailerClick = onTrailerClick,
                        onLibraryMutationComplete = mutationComplete,
                        viewModel = actionsViewModel
                    )
                }
            } }
        }
    }

    val manualTrailerLauncher = LocalManualTrailerLauncher.current
    BackdropTrailerPreview(
        profileId = currentProfile?.id ?: 0,
        focusedItem = previewItem,
        settings = rememberTrailerPreviewSettings(currentProfile?.id ?: 0),
        enabled = manualTrailerLauncher == null && !LocalManualTrailerActive.current &&
            (hasContentFocus || previewActive) && actionItem == null && currentProfile != null,
        resolveTrailer = actionsViewModel::trailerFor,
        onActiveChanged = { previewActive = it },
        onFullscreenChanged = onPreviewActiveChanged,
        onOpen = onMovieClick,
        onDismiss = { originalPosterFocus.requestFocusOrFallback(entryRequester) },
        homeViewModel = actionsViewModel
    )
}

@Composable
private fun LibraryFilterChip(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape: Shape = RoundedCornerShape(18.dp)
    val backgroundColor by animateColorAsState(
        targetValue = when {
            selected -> Color(0xFF1488E8)
            focused -> Color.White.copy(alpha = 0.16f)
            else -> Color.White.copy(alpha = 0.08f)
        },
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "library-filter-background"
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused) Color.White else Color.White.copy(alpha = if (selected) 0.72f else 0.24f),
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "library-filter-border"
    )
    val textColor by animateColorAsState(
        targetValue = libraryFilterTextColor(selected),
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "library-filter-text"
    )
    Box(
        Modifier
            .testTag(testTag)
            .background(color = backgroundColor, shape = shape)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = borderColor,
                shape = shape
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .onFocusChanged { focused = it.isFocused }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = textColor, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun LibrarySortMenu(sort: CatalogGridSort, onSort: (CatalogGridSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (sort) {
        CatalogGridSort.FEATURED -> stringResource(R.string.catalog_grid_sort_featured)
        CatalogGridSort.TITLE -> stringResource(R.string.catalog_grid_sort_title)
        CatalogGridSort.NEWEST -> stringResource(R.string.catalog_grid_sort_newest)
        CatalogGridSort.RATING -> stringResource(R.string.catalog_grid_sort_rating)
    }
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag("library-sort-button")
        ) {
            Text(
                stringResource(R.string.common_labeled_value, stringResource(R.string.catalog_grid_sort_label), label),
                color = Color.White,
                maxLines = 1
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CatalogGridSort.entries.forEach { option ->
                val optionLabel = when (option) {
                    CatalogGridSort.FEATURED -> stringResource(R.string.catalog_grid_sort_featured)
                    CatalogGridSort.TITLE -> stringResource(R.string.catalog_grid_sort_title)
                    CatalogGridSort.NEWEST -> stringResource(R.string.catalog_grid_sort_newest)
                    CatalogGridSort.RATING -> stringResource(R.string.catalog_grid_sort_rating)
                }
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = {
                        onSort(option)
                        expanded = false
                    },
                    modifier = Modifier.testTag("library-sort-${option.name.lowercase()}")
                )
            }
        }
    }
}

internal fun libraryFilterTextColor(selected: Boolean): Color =
    if (selected) accessibleContentColor(Color(0xFF1488E8)) else Color.White
