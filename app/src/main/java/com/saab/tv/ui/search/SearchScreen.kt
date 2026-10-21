package com.saab.tv.ui.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.R
import com.saab.tv.domain.schema.canonicalPresentationKey
import com.saab.tv.remote_input.RemoteInputMode
import com.saab.tv.ui.addons.RemotePasteDialog
import com.saab.tv.ui.components.SaabTvBackground
import com.saab.tv.ui.components.SaabTvCard
import com.saab.tv.ui.home.DpadRepeatGate
import com.saab.tv.ui.home.ViewMoreCard
import com.saab.tv.ui.navigation.handoffNavigationFocus
import com.saab.tv.ui.home.CatalogQuickActionsPopup
import com.saab.tv.ui.components.ArtworkPrefetchDispatcher
import com.saab.tv.ui.components.ArtworkPrefetchEffect
import com.saab.tv.ui.components.firstItemIndexByCanonicalKey
import com.saab.tv.ui.components.FocusShelf
import com.saab.tv.ui.components.SetupButton
import com.saab.tv.ui.utils.requestFocusOrFallback
import com.saab.tv.ui.utils.requestFocusSafely
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.saab.tv.ui.components.focusFrameSize
import com.saab.tv.ui.navigation.LocalNavigationFocusHandoff

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SearchScreen(
    entryRequester: FocusRequester, drawerRequester: FocusRequester,
    searchSessionId: Long = 0L, viewModel: SearchViewModel = hiltViewModel(),
    previewViewModel: com.saab.tv.ui.home.HomeViewModel = hiltViewModel(),
    currentProfile: ProfileEntity?, onMovieClick: (MetaItem) -> Unit,
    onTrailerClick: (String, String) -> Unit = { _, _ -> },
    onViewMore: (String, List<MetaItem>) -> Unit = { _, _ -> },
    moviesViewMoreRequester: FocusRequester = remember { FocusRequester() },
    seriesViewMoreRequester: FocusRequester = remember { FocusRequester() },
    resultsRequester: FocusRequester = remember { FocusRequester() },
    lastFocusedId: String? = null, onFocusedIdChange: (String?) -> Unit = {},
    watchedIds: Set<String> = emptySet()
) {
    val authorizeNavigationHandoff = LocalNavigationFocusHandoff.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resultsAreCurrent = state.resultsAreCurrent
    val actionScope = rememberCoroutineScope()
    // The ViewModel publishes only typed, provider-independent projections. Convert once here
    // at the existing navigation/action callback boundary until those APIs accept projections.
    val resultItems = remember(state.results) { state.results.map { it.asMetaItem() } }
    val movieItems = remember(state.movies) { state.movies.map { it.asMetaItem() } }
    val seriesItems = remember(state.series) { state.series.map { it.asMetaItem() } }
    val visibleMovieItems = remember(movieItems, state.mediaFilter) {
        movieItems.takeIf { state.mediaFilter != SearchMediaFilter.SERIES }.orEmpty()
    }
    val visibleSeriesItems = remember(seriesItems, state.mediaFilter) {
        seriesItems.takeIf { state.mediaFilter != SearchMediaFilter.MOVIES }.orEmpty()
    }
    val visibleResultItems = remember(visibleMovieItems, visibleSeriesItems) {
        visibleMovieItems + visibleSeriesItems
    }
    // Focus changes are frequent on TV. Build canonical identity lookup once per
    // visible result batch instead of recalculating each title on every D-pad move.
    val resultIndexByKey = remember(visibleResultItems) { firstItemIndexByCanonicalKey(visibleResultItems) }
    val interpretationChips = remember(state.query, state.tmdbSearchAvailable) {
        if (state.tmdbSearchAvailable) searchInterpretationChips(state.query) else emptyList()
    }
    var actionItem by remember { mutableStateOf<MetaItem?>(null) }
    var actionBounds by remember { mutableStateOf(Rect.Zero) }
    val onLongClick: (MetaItem, Rect) -> Unit = { item, bounds ->
        actionItem = item
        actionBounds = bounds
    }
    var remoteSearch by remember { mutableStateOf(false) }
    var resultFocus by remember { mutableStateOf(false) }
    var originalPosterFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val filterEntry = remember { FocusRequester() }
    val interpretationEntry = remember { FocusRequester() }
    val enrichedItems by remember(previewViewModel) {
        previewViewModel.state.map { it.enrichedMeta }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = emptyMap())
    val liveWatchedIds by remember(previewViewModel, currentProfile?.id) {
        currentProfile?.id?.let(previewViewModel::watchedIdsForProfile) ?: flowOf(emptySet())
    }.collectAsStateWithLifecycle(initialValue = emptySet())
    val focusedResult: (String?) -> Unit = { id ->
        // The index is built from the currently visible, type-filtered batch.
        // Resolve against that same list so focus on a Series result never maps
        // to an unrelated Movie at the same index in the unfiltered batch.
        val item = resolveSearchFocusItem(visibleResultItems, resultIndexByKey, id)
        val presentationKey = item?.canonicalPresentationKey()
        onFocusedIdChange(presentationKey)
        if (resultsAreCurrent) item?.let(previewViewModel::ensureTmdbEnrichment)
    }
    var restoreResultFocus by remember { mutableStateOf(lastFocusedId != null) }
    val moviesEntry = remember { FocusRequester() }
    val seriesEntry = remember { FocusRequester() }
    val topNav = currentProfile?.navPosition == "top"
    val firstResult = visibleResultItems.firstOrNull()
    val focusTarget = resolveSearchFocusItem(visibleResultItems, resultIndexByKey, lastFocusedId, firstResult)
    val targetKey = focusTarget?.canonicalPresentationKey()

    com.saab.tv.ui.navigation.AppBackHandler(
        enabled = true,
        priority = com.saab.tv.ui.navigation.AppBackPriority.Screen
    ) {
        if (resultFocus && entryRequester.requestFocusSafely()) {
            resultFocus = false
        } else {
            authorizeNavigationHandoff?.invoke(null)
            drawerRequester.requestFocusSafely()
        }
    }
    LaunchedEffect(searchSessionId) {
        if (viewModel.beginSearchSession(searchSessionId)) {
            restoreResultFocus = false
            withFrameNanos { }
            entryRequester.requestFocusSafely()
        }
    }
    LaunchedEffect(resultsAreCurrent) {
        if (!resultsAreCurrent) actionItem = null
    }
    LaunchedEffect(currentProfile?.id, currentProfile?.tmdbEnabled, currentProfile?.tmdbLanguage, currentProfile?.titleCardShape) {
        viewModel.configureSearchProfile(currentProfile?.id)
        viewModel.configureTmdbSearch(currentProfile?.tmdbEnabled == true, currentProfile?.tmdbLanguage.orEmpty())
        previewViewModel.configureTmdbProfile(currentProfile)
    }
    LaunchedEffect(lastFocusedId, targetKey) {
        // A saved result can disappear when the query is refreshed or its provider
        // changes. In that case focusTarget is the first available result; restore
        // to it instead of leaving the Search screen without a focused control.
        if (restoreResultFocus && focusTarget != null) {
            repeat(20) {
                withFrameNanos { }
                if (resultsRequester.requestFocusSafely()) {
                    restoreResultFocus = false
                    return@LaunchedEffect
                }
            }
        }
    }

    SaabTvBackground {
        // TV navigation needs compact visible keys, not phone-sized touch targets.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp,
            com.saab.tv.ui.components.LocalWatchedIds provides liveWatchedIds,
            com.saab.tv.ui.components.LocalPosterFocusReturn provides { originalPosterFocus = it }) {
        BoxWithConstraints(Modifier.fillMaxSize().focusGroup()) {
            val top = if (topNav) 48.dp else 8.dp
            val visibleShelfCount = listOf(visibleMovieItems, visibleSeriesItems).count { it.isNotEmpty() }.coerceAtLeast(1)
            val interpretationHeight = if (interpretationChips.isNotEmpty()) 40.dp else 0.dp
            val shelfReserved = 42.dp * visibleShelfCount
            val posterHeight = ((maxHeight - top - 36.dp - 42.dp - interpretationHeight - 272.dp - 8.dp - shelfReserved) /
                visibleShelfCount.toFloat()).coerceIn(60.dp, 155.dp)
            Column(Modifier.fillMaxSize().padding(
                start = if (topNav) 36.dp else 90.dp, end = 28.dp, top = top, bottom = 8.dp
            )) {
                Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.query.ifBlank { stringResource(R.string.search_heading) }, style = MaterialTheme.typography.titleMedium,
                        color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (state.isLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                val filters = listOf(
                    SearchMediaFilter.MOVIES to stringResource(R.string.nav_movies),
                    SearchMediaFilter.SERIES to stringResource(R.string.nav_series)
                )
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp).onFocusChanged { resultFocus = it.hasFocus },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    filters.forEachIndexed { index, (filter, label) ->
                        val isSelected = state.mediaFilter == filter
                        SetupButton(
                            text = label,
                            onClick = { viewModel.setMediaFilter(filter) },
                            primary = isSelected,
                            compact = true,
                            focusRequester = filterEntry.takeIf { index == 0 },
                            modifier = Modifier
                                .testTag("search-filter-${filter.name.lowercase()}")
                                .semantics { selected = isSelected }
                                .focusProperties {
                                    if (index == 0) left = drawerRequester
                                    up = drawerRequester
                                    down = when {
                                        interpretationChips.isNotEmpty() -> interpretationEntry
                                        visibleResultItems.isNotEmpty() -> resultsRequester
                                        else -> entryRequester
                                    }
                                }
                        )
                    }
                }
                if (interpretationChips.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 36.dp).onFocusChanged { resultFocus = it.hasFocus },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.search_interpreted_as),
                            color = Color.LightGray,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            itemsIndexed(interpretationChips, key = { _, chip -> chip.phrase }) { _, chip ->
                                SetupButton(
                                    text = "${chip.label} ×",
                                    onClick = {
                                        viewModel.onQueryChange(removeSearchInterpretation(state.query, chip.phrase))
                                        actionScope.launch {
                                            withFrameNanos { }
                                            entryRequester.requestFocusSafely()
                                        }
                                    },
                                    compact = true,
                                    modifier = Modifier
                                        .then(if (chip == interpretationChips.firstOrNull()) Modifier.focusRequester(interpretationEntry) else Modifier)
                                        .focusProperties {
                                            up = filterEntry
                                            down = if (visibleResultItems.isNotEmpty()) resultsRequester else entryRequester
                                        }
                                        .testTag("search-interpretation-${chip.phrase}")
                                )
                            }
                        }
                    }
                }
                if (resultItems.isEmpty() || visibleResultItems.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(when {
                                state.isLoading -> stringResource(R.string.search_searching)
                                state.error != null -> stringResource(R.string.search_failed)
                                state.query.trim().length < 3 && !state.hasSubmittedCurrentQuery -> stringResource(R.string.search_prompt)
                                visibleResultItems.isEmpty() && resultItems.isNotEmpty() -> stringResource(
                                    R.string.search_no_matching_type,
                                    if (state.mediaFilter == SearchMediaFilter.MOVIES) stringResource(R.string.nav_movies)
                                    else stringResource(R.string.nav_series)
                                )
                                state.failedProviders.isNotEmpty() -> stringResource(R.string.search_source_unavailable)
                                else -> stringResource(R.string.search_no_titles)
                            }, color = Color.LightGray)
                            if (state.error != null || state.failedProviders.isNotEmpty()) {
                                SetupButton(
                                    text = stringResource(R.string.search_retry),
                                    onClick = viewModel::retrySearch,
                                    primary = true,
                                    compact = true
                                )
                            }
                            if (state.query.isBlank() && state.recentSearches.isNotEmpty()) {
                                Row(
                                    Modifier.fillMaxWidth(0.8f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        stringResource(R.string.search_recent_searches),
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    SetupButton(
                                        text = stringResource(R.string.search_clear_recent),
                                        onClick = viewModel::clearRecentSearches,
                                        compact = true,
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    itemsIndexed(state.recentSearches, key = { _, query -> query.lowercase() }) { _, query ->
                                        SetupButton(
                                            text = query,
                                            onClick = { viewModel.selectRecentSearch(query) },
                                            compact = true
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Column(Modifier.weight(1f).onFocusChanged { resultFocus = it.hasFocus }) {
                        if (state.error != null || state.failedProviders.isNotEmpty()) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 32.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    stringResource(
                                        if (state.error != null) R.string.search_previous_results
                                        else R.string.search_source_unavailable
                                    ),
                                    color = Color.LightGray,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                SetupButton(
                                    text = stringResource(R.string.search_retry),
                                    onClick = viewModel::retrySearch,
                                    compact = true
                                )
                            }
                        } else if (!resultsAreCurrent) {
                            Text(
                                stringResource(
                                    if (state.query.trim().length < 3 && !state.hasSubmittedCurrentQuery) R.string.search_short_query_hint
                                    else R.string.search_updating_results
                                ),
                                color = Color.LightGray,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                            )
                        } else if (state.failedProviders.isNotEmpty()) {
                            Text(
                                stringResource(R.string.search_source_unavailable),
                                color = Color.LightGray,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                            )
                        }
                        val landscapeCards = currentProfile?.titleCardShape == "landscape"
                        if (visibleMovieItems.isNotEmpty()) {
                            SearchResultRow(
                                title = stringResource(R.string.nav_movies), items = visibleMovieItems,
                                posterHeight = posterHeight, targetKey = targetKey, targetRequester = resultsRequester,
                                keyboardRequester = entryRequester, drawerRequester = drawerRequester,
                                moreRequester = moviesViewMoreRequester, lastFocusedId = lastFocusedId,
                                watchedIds = liveWatchedIds, enrichedItems = enrichedItems,
                                onClick = onMovieClick, onLongClick = onLongClick, onMore = onViewMore,
                                onFocused = focusedResult, modifier = Modifier.weight(1f), rowRequester = moviesEntry,
                                upRequester = if (interpretationChips.isNotEmpty()) interpretationEntry else filterEntry,
                                downRequester = if (visibleSeriesItems.isNotEmpty()) seriesEntry else entryRequester,
                                landscapeCards = landscapeCards,
                                pivotOffset = if (topNav) 14.dp else 30.dp,
                                onItemShown = {
                                    currentProfile?.id?.let { profileId -> previewViewModel.ensureWatchedAlias(profileId, it) }
                                },
                                onPrepareArtworkItem = previewViewModel::ensureTmdbEnrichment,
                                onEndReached = viewModel::loadMoreResults,
                                isTopNav = topNav,
                                isInteractive = resultsAreCurrent,
                                canLoadMore = state.hasMoreResults,
                                viewMoreTitle = "Movies"
                            )
                        }
                        if (visibleSeriesItems.isNotEmpty()) {
                            SearchResultRow(
                                title = stringResource(R.string.nav_series), items = visibleSeriesItems,
                                posterHeight = posterHeight, targetKey = targetKey, targetRequester = resultsRequester,
                                keyboardRequester = entryRequester, drawerRequester = drawerRequester,
                                moreRequester = seriesViewMoreRequester, lastFocusedId = lastFocusedId,
                                watchedIds = liveWatchedIds, enrichedItems = enrichedItems,
                                onClick = onMovieClick, onLongClick = onLongClick, onMore = onViewMore,
                                onFocused = focusedResult, modifier = Modifier.weight(1f), rowRequester = seriesEntry,
                                upRequester = if (visibleMovieItems.isNotEmpty()) moviesEntry
                                    else if (interpretationChips.isNotEmpty()) interpretationEntry else filterEntry,
                                downRequester = entryRequester,
                                landscapeCards = landscapeCards,
                                pivotOffset = if (topNav) 14.dp else 30.dp,
                                onItemShown = {
                                    currentProfile?.id?.let { profileId -> previewViewModel.ensureWatchedAlias(profileId, it) }
                                },
                                onPrepareArtworkItem = previewViewModel::ensureTmdbEnrichment,
                                onEndReached = viewModel::loadMoreResults,
                                isTopNav = topNav,
                                isInteractive = resultsAreCurrent,
                                canLoadMore = state.hasMoreResults,
                                viewMoreTitle = "Series"
                            )
                        }
                    }
                }
                TvKeyboard(
                    onKeyPress = viewModel::appendCharacter, onBackspace = viewModel::removeCharacter,
                    onSpace = { viewModel.appendCharacter(" ") }, onClear = viewModel::clearSearch,
                    onOpenRemoteInput = { remoteSearch = true },
                    entryRequester = entryRequester, drawerRequester = drawerRequester,
                    isTopNav = topNav, hasResults = resultItems.isNotEmpty() || state.query.isNotBlank(),
                    contentEntryRequester = if (resultItems.isNotEmpty()) resultsRequester else filterEntry,
                    onSubmitSearch = viewModel::submitQuery
                )
            }
        }
        }
    }
    if (remoteSearch) RemotePasteDialog(
        onDismissRequest = { remoteSearch = false; entryRequester.requestFocusSafely() },
        onUrlReceived = viewModel::onQueryChange, mode = RemoteInputMode.SEARCH,
        title = stringResource(R.string.search_remote_title),
        description = stringResource(R.string.search_remote_description)
    )
    actionItem?.takeIf { currentProfile != null }?.let { item ->
        CompositionLocalProvider(com.saab.tv.ui.components.LocalWatchedIds provides liveWatchedIds) {
            CatalogQuickActionsPopup(item, actionBounds, requireNotNull(currentProfile).id,
                onDismiss = {
                    actionItem = null
                    actionScope.launch {
                        withFrameNanos { }
                        originalPosterFocus.requestFocusOrFallback(resultsRequester)
                    }
                }, onTrailerClick = onTrailerClick, viewModel = previewViewModel)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SearchResultRow(
    title: String, items: List<MetaItem>, posterHeight: Dp, targetKey: String?,
    targetRequester: FocusRequester, keyboardRequester: FocusRequester, drawerRequester: FocusRequester,
    moreRequester: FocusRequester, lastFocusedId: String?, watchedIds: Set<String>,
    enrichedItems: Map<String, MetaItem>,
    onClick: (MetaItem) -> Unit, onLongClick: (MetaItem, Rect) -> Unit,
    onMore: (String, List<MetaItem>) -> Unit,
    onFocused: (String?) -> Unit, modifier: Modifier,
    rowRequester: FocusRequester, upRequester: FocusRequester, downRequester: FocusRequester,
    landscapeCards: Boolean,
    pivotOffset: Dp,
    onItemShown: (MetaItem) -> Unit = {},
    onPrepareArtworkItem: (MetaItem) -> Unit = {},
    artworkPrefetchDispatcher: ArtworkPrefetchDispatcher? = null,
    onEndReached: () -> Unit = {},
    isTopNav: Boolean = false,
    isInteractive: Boolean = true,
    canLoadMore: Boolean = false,
    viewMoreTitle: String = title
) {
    val authorizeNavigationHandoff = LocalNavigationFocusHandoff.current
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val artworkPrefetcher = remember(context, artworkPrefetchDispatcher) {
        artworkPrefetchDispatcher ?: ArtworkPrefetchDispatcher.forCoil(context)
    }
    val currentOnItemShown by rememberUpdatedState(onItemShown)
    val currentOnEndReached by rememberUpdatedState(onEndReached)
    val cardWidth = if (landscapeCards) 190.dp else posterHeight * (2f / 3f)
    val focusFrame = focusFrameSize(cardWidth, landscapeCards)
    val rowHasCardFocus = remember { mutableStateOf(false) }
    var focusedTitle by remember { mutableStateOf("") }
    var focusedItemIndex by remember(items) { mutableIntStateOf(0) }
    var rowFocusedId by remember(items) { mutableStateOf(items.firstOrNull()?.canonicalPresentationKey()) }
    val initialFocusedId = remember { lastFocusedId }
    var scrollRestored by remember { mutableStateOf(false) }
    ArtworkPrefetchEffect(
        items = items,
        enrichedItems = enrichedItems,
        listState = listState,
        focusedItemKey = rowFocusedId,
        landscapeCards = landscapeCards,
        dispatcher = artworkPrefetcher,
        posterSize = if (landscapeCards) null else DpSize(posterHeight * (2f / 3f), posterHeight),
        onPrepareItem = { if (landscapeCards) onPrepareArtworkItem(it) }
    )
    LaunchedEffect(initialFocusedId, items) {
        val index = items.indexOfFirst { it.canonicalPresentationKey() == initialFocusedId }
        if (index >= 0 && !scrollRestored) {
            listState.scrollToItem(index)
            scrollRestored = true
        }
    }
    LaunchedEffect(listState, items.size, canLoadMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisibleIndex ->
                if (canLoadMore && items.isNotEmpty() && lastVisibleIndex >= items.lastIndex - 3) {
                    currentOnEndReached()
                }
            }
    }
    Column(
        modifier.fillMaxWidth().onFocusChanged {
            if (!it.hasFocus) rowHasCardFocus.value = false
        }
    ) {
        Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (focusedTitle.isBlank()) title else "$title · $focusedTitle",
                color = Color.White, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (items.isEmpty()) Text(stringResource(R.string.search_no_matching_type, title), color = Color.Gray)
        else {
          FocusShelf(
            state = listState,
            focused = rowHasCardFocus,
            width = focusFrame.width,
            height = focusFrame.height,
            startPadding = pivotOffset,
            focusedItemIndex = focusedItemIndex,
            tag = "search-shelf-focus-indicator",
            horizontalArrangement = Arrangement.spacedBy(12.dp)
          ) {
                itemsIndexed(items, key = { _, item -> item.canonicalPresentationKey() }) { index, item ->
                    LaunchedEffect(item.canonicalPresentationKey()) { currentOnItemShown(item) }
                    val previewItem = enrichedItems[item.canonicalPresentationKey()] ?: item
                    val itemKey = item.canonicalPresentationKey()
                    val itemFocusRequester = targetRequester.takeIf { itemKey == targetKey }
                    SaabTvCard(previewItem = previewItem, title = item.name, posterUrl = item.poster,
                        onClick = { if (isInteractive) onClick(item) },
                        onLongClick = if (isInteractive) ({ bounds -> onLongClick(item, bounds) }) else null,
                        isWatched = item.id in watchedIds,
                        normalWidth = cardWidth,
                        normalHeight = focusFrame.height,
                        landscapeWidth = cardWidth,
                        showFocusOutline = false,
                        focusRequester = itemFocusRequester,
                        onFocused = {
                            rowHasCardFocus.value = true
                            focusedTitle = item.name
                            focusedItemIndex = index
                            rowFocusedId = itemKey
                            onFocused(itemKey)
                        },
                        modifier = Modifier
                            .then(if (itemKey == rowFocusedId) Modifier.focusRequester(rowRequester) else Modifier)
                            .focusProperties { down = downRequester; up = upRequester }
                            .onPreviewKeyEvent {
                                if (com.saab.tv.ui.trailer.InlineTrailerAnchor.session != null) return@onPreviewKeyEvent false
                                if (it.type == KeyEventType.KeyDown && index == 0 && it.key == Key.DirectionLeft) {
                                    if (!isTopNav) handoffNavigationFocus(it, authorizeNavigationHandoff, drawerRequester)
                                    else false
                                } else false
                            })
                }
                item(key = "search-view-more:$title") {
                    ViewMoreCard(
                        onClick = { if (isInteractive) onMore(viewMoreTitle, items) },
                        cardWidth = cardWidth,
                        isLandscape = landscapeCards,
                        label = stringResource(R.string.search_view_all),
                        onFocused = {
                            rowHasCardFocus.value = true
                            focusedTitle = ""
                            focusedItemIndex = items.size
                        },
                        modifier = Modifier
                            .focusRequester(moreRequester)
                            .focusProperties { down = downRequester; up = upRequester }
                            .onPreviewKeyEvent {
                                if (com.saab.tv.ui.trailer.InlineTrailerAnchor.session != null) return@onPreviewKeyEvent false
                                it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight
                            }
                    )
                }
          }
        }
    }
}

@Composable
fun TvKeyboard(
    onKeyPress: (String) -> Unit, onBackspace: () -> Unit, onSpace: () -> Unit,
    onClear: () -> Unit, onOpenRemoteInput: () -> Unit,
    entryRequester: FocusRequester, drawerRequester: FocusRequester, isTopNav: Boolean,
    hasResults: Boolean, contentEntryRequester: FocusRequester? = null, modifier: Modifier = Modifier,
    onSubmitSearch: () -> Unit = {}
) {
    val authorizeNavigationHandoff = LocalNavigationFocusHandoff.current
    val rows = remember { listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm.'-") }
    var lastKey by remember { mutableStateOf("q") }
    val repeatGate = remember { DpadRepeatGate(horizontalRepeatIntervalMs = 80L, verticalRepeatIntervalMs = 120L) }
    fun keyModifier(id: String, row: Int, column: Int, lastColumn: Int): Modifier = Modifier
        .then(if (id == lastKey) Modifier.focusRequester(entryRequester) else Modifier)
        .onFocusChanged { if (it.isFocused) lastKey = id }
        .onPreviewKeyEvent {
            if (repeatGate.shouldConsume(it)) return@onPreviewKeyEvent true
            if (it.type != KeyEventType.KeyDown) false
            else when {
                row == 0 && it.key == Key.DirectionUp -> {
                    if (hasResults) contentEntryRequester?.requestFocusSafely() == true
                    else handoffNavigationFocus(it, authorizeNavigationHandoff, drawerRequester)
                }
                column == 0 && it.key == Key.DirectionLeft -> {
                    if (!isTopNav) handoffNavigationFocus(it, authorizeNavigationHandoff, drawerRequester)
                    else false
                }
                column == lastColumn && it.key == Key.DirectionRight -> true
                row == 4 && it.key == Key.DirectionDown -> true
                else -> false
            }
        }
    // Material buttons reserve a 48dp TV focus/touch target even in compact mode.
    // Five rows plus their gaps need 264dp; the old 224dp fixed box clipped the
    // action row, making Space/Backspace/Clear/QR appear clickable but inert.
    Column(modifier.fillMaxWidth().heightIn(min = 264.dp).padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEachIndexed { row, keys ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                keys.forEachIndexed { column, key ->
                    KeyboardKey(key.uppercase(), { onKeyPress(key.toString()) },
                        keyModifier(key.toString(), row, column, keys.lastIndex).width(48.dp))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val actions = listOf(
                stringResource(R.string.search_key_space) to onSpace,
                stringResource(R.string.search_key_backspace) to onBackspace,
                stringResource(R.string.search_key_clear) to onClear,
                stringResource(R.string.search_key_qr) to onOpenRemoteInput,
                stringResource(R.string.search_key_submit) to onSubmitSearch
            )
            actions.forEachIndexed { index, (label, action) ->
                KeyboardKey(label, action, keyModifier(label, 4, index, actions.lastIndex).width(if (index == 0) 170.dp else 106.dp))
            }
        }
    }
}

@Composable
private fun KeyboardKey(label: String, onClick: () -> Unit, modifier: Modifier) {
    SetupButton(
        text = label,
        onClick = onClick,
        modifier = modifier.heightIn(min = 40.dp),
        compact = true
    )
}
