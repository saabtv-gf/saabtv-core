package com.saab.tv.ui.watchlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.ui.components.LocalPosterFocusReturn
import com.saab.tv.ui.components.LocalWatchedIds
import com.saab.tv.ui.components.LocalTitleCardShape
import com.saab.tv.ui.home.CatalogQuickActionsPopup
import com.saab.tv.ui.home.DpadRepeatGate
import com.saab.tv.ui.home.HomeViewModel
import com.saab.tv.ui.home.InfiniteLoopRow
import com.saab.tv.ui.home.UpKeyDebouncer
import com.saab.tv.ui.trailer.BackdropTrailerPreview
import com.saab.tv.ui.trailer.LocalManualTrailerActive
import com.saab.tv.ui.trailer.LocalManualTrailerLauncher
import com.saab.tv.data.profile.rememberTrailerPreviewSettings
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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
    val library by viewModel.libraryItems.collectAsStateWithLifecycle()
    val liveWatchedIds by remember(actionsViewModel, currentProfile?.id) {
        actionsViewModel.watchedIdsForProfile(currentProfile?.id ?: 1)
    }.collectAsStateWithLifecycle(initialValue = emptySet())
    val enrichedItems by remember(actionsViewModel) {
        actionsViewModel.state.map { it.enrichedMeta }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = emptyMap())
    var actionItem by remember { mutableStateOf<MetaItem?>(null) }
    var actionBounds by remember { mutableStateOf(Rect.Zero) }
    var previewItem by remember { mutableStateOf<MetaItem?>(null) }
    var hasContentFocus by remember { mutableStateOf(false) }
    var previewActive by remember { mutableStateOf(false) }
    var originalPosterFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val isTopNav = currentProfile?.navPosition == "top"
    val landscapeCards = LocalTitleCardShape.current == "landscape"
    val startPadding = if (isTopNav) 50.dp else 120.dp
    val actionRows = remember { listOf(UpKeyDebouncer(), UpKeyDebouncer(), UpKeyDebouncer()) }
    val repeatGates = remember { listOf(DpadRepeatGate(), DpadRepeatGate(), DpadRepeatGate()) }
    val rows = listOf(
        "Continue Watching" to library.inProgress,
        "Watchlist" to library.watchlist,
        "Watched" to library.watched
    ).filter { it.second.isNotEmpty() }

    LaunchedEffect(currentProfile?.id) { viewModel.setProfileId(currentProfile?.id) }
    LaunchedEffect(actionsViewModel, currentProfile) {
        actionsViewModel.configureTmdbProfile(currentProfile)
    }
    LaunchedEffect(currentProfile?.id, currentProfile?.tmdbEnabled, landscapeCards, library.watchlist.map { it.type to it.id }) {
        actionsViewModel.configureTmdbProfile(currentProfile)
        if (landscapeCards) {
            // Warm a small first-screen window before D-pad focus reaches these cards.
            library.watchlist.take(8).forEach { item ->
                actionsViewModel.ensureTmdbEnrichment(item)
                if (currentProfile?.tmdbEnabled != true || item.poster.isNullOrBlank()) {
                    viewModel.resolvePosterIfNeeded(item)
                }
            }
        }
    }
    BackHandler(enabled = hasContentFocus && actionItem == null && !previewActive) {
        runCatching { drawerRequester.requestFocus() }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalWatchedIds provides liveWatchedIds,
        LocalPosterFocusReturn provides { originalPosterFocus = it }
    ) {
        Box(Modifier.fillMaxSize().onFocusChanged { hasContentFocus = it.hasFocus }.focusGroup()) {
            if (rows.isEmpty()) {
                Box(
                    Modifier.fillMaxSize().focusRequester(entryRequester).focusable(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Your Library Is Empty", color = Color.White.copy(alpha = 0.65f))
                }
            } else {
                Column(
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(top = if (isTopNav) 28.dp else 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "My Library",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        modifier = Modifier.padding(start = startPadding, bottom = 4.dp)
                    )
                    rows.forEachIndexed { index, (title, items) ->
                        val rowState = remember(title) { LazyListState() }
                        InfiniteLoopRow(
                            startPadding = startPadding,
                            isTopNav = isTopNav,
                            rowIndex = index,
                            title = title,
                            items = items,
                            onMovieClick = { item ->
                                if (title == "Continue Watching") onContinueClick(item) else onMovieClick(item)
                            },
                            onMovieLongClick = { item, _, bounds ->
                                actionItem = item
                                actionBounds = bounds
                            },
                            onViewMore = {},
                            onFocused = { item, _ ->
                                previewItem = item
                                item?.let(actionsViewModel::ensureTmdbEnrichment)
                                if (title == "Watchlist" &&
                                    (currentProfile?.tmdbEnabled != true || item?.poster.isNullOrBlank())
                                ) item?.let(viewModel::resolvePosterIfNeeded)
                            },
                            entryRequester = entryRequester,
                            drawerRequester = drawerRequester,
                            locallyFocusedItemId = null,
                            isGlobalFocusPresent = false,
                            isFirstRow = index == 0,
                            isInfiniteLoopEnabled = false,
                            upKeyDebouncer = actionRows[index],
                            repeatGate = repeatGates[index],
                            externalListState = rowState,
                            isLandscapeCards = landscapeCards,
                            enrichedItems = enrichedItems,
                            onItemShown = { item ->
                                actionsViewModel.ensureTmdbEnrichment(item)
                                if (currentProfile?.tmdbEnabled != true || item.poster.isNullOrBlank()) {
                                    viewModel.resolvePosterIfNeeded(item)
                                }
                            },
                            onWatchedItemShown = { item ->
                                actionsViewModel.ensureWatchedAlias(currentProfile?.id ?: 1, item)
                            }
                        )
                    }
                    Spacer(Modifier.height(120.dp))
                }
            }

            actionItem?.let { item ->
                CatalogQuickActionsPopup(
                    item = item,
                    bounds = actionBounds,
                    profileId = currentProfile?.id ?: 1,
                    onDismiss = { actionItem = null },
                    onTrailerClick = onTrailerClick,
                    viewModel = actionsViewModel
                )
            }
        }
    }

    val manualTrailerLauncher = LocalManualTrailerLauncher.current
    BackdropTrailerPreview(
        profileId = currentProfile?.id ?: 0,
        focusedItem = previewItem,
        catalog = rows.flatMap { it.second },
        settings = rememberTrailerPreviewSettings(currentProfile?.id ?: 0),
        enabled = manualTrailerLauncher == null && !LocalManualTrailerActive.current &&
            (hasContentFocus || previewActive) && actionItem == null && currentProfile != null,
        resolveTrailer = actionsViewModel::trailerFor,
        onActiveChanged = { previewActive = it },
        onFullscreenChanged = onPreviewActiveChanged,
        onOpen = onMovieClick,
        onDismiss = { runCatching { originalPosterFocus?.requestFocus() ?: entryRequester.requestFocus() } },
        homeViewModel = actionsViewModel
    )
}
