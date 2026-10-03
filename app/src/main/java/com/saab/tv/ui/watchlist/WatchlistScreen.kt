package com.saab.tv.ui.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.ui.home.DpadRepeatGate
import com.saab.tv.ui.home.CardActionIcon
import com.saab.tv.ui.home.HomeViewModel
import com.saab.tv.ui.home.InfiniteLoopRow
import com.saab.tv.ui.home.UpKeyDebouncer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun WatchlistScreen(
    currentProfile: ProfileEntity?,
    entryRequester: FocusRequester,
    drawerRequester: FocusRequester,
    onMovieClick: (MetaItem) -> Unit,
    onTrailerClick: (String, String) -> Unit = { _, _ -> },
    onPreviewActiveChanged: (Boolean) -> Unit = {},
    watchedIds: Set<String> = emptySet(),
    viewModel: WatchlistViewModel = hiltViewModel(),
    actionsViewModel: HomeViewModel = hiltViewModel()
) {
    val manualTrailerLauncher = com.saab.tv.ui.trailer.LocalManualTrailerLauncher.current
    val movies by viewModel.movieItems.collectAsStateWithLifecycle()
    val series by viewModel.seriesItems.collectAsStateWithLifecycle()
    val actionScope = rememberCoroutineScope()
    val context = LocalContext.current
    var actionItem by remember { mutableStateOf<MetaItem?>(null) }
    var previewItem by remember { mutableStateOf<MetaItem?>(null) }
    var hasContentFocus by remember { mutableStateOf(false) }
    var previewActive by remember { mutableStateOf(false) }
    var originalPosterFocus by remember { mutableStateOf<FocusRequester?>(null) }
    var actionBounds by remember { mutableStateOf(Rect.Zero) }
    val onLongClick: (MetaItem, Boolean, Rect) -> Unit = { item, _, bounds ->
        actionBounds = bounds
        actionItem = item
    }

    androidx.activity.compose.BackHandler(enabled = hasContentFocus) { runCatching { drawerRequester.requestFocus() } }

    val upKeyDebouncer = remember { UpKeyDebouncer() }
    val dpadRepeatGate = remember { DpadRepeatGate() }

    var lastFocusedKey by remember { mutableStateOf(viewModel.lastFocusedKey) }

    LaunchedEffect(currentProfile?.id) {
        viewModel.setProfileId(currentProfile?.id)
        lastFocusedKey = viewModel.lastFocusedKey
    }

    val movieIds = remember(movies) { movies.mapTo(mutableSetOf()) { it.id } }
    val seriesIds = remember(series) { series.mapTo(mutableSetOf()) { it.id } }
    val restorableFocusKey = lastFocusedKey.takeIf {
        WatchlistFocusPolicy.isValid(it, movieIds, seriesIds)
    }

    // Redirect focus when the focused item was removed (e.g., unwatchlisted from details)
    LaunchedEffect(movies, series) {
        val key = lastFocusedKey ?: return@LaunchedEffect
        val target = WatchlistFocusPolicy.parse(key)
        if (target == null) {
            lastFocusedKey = null
            viewModel.lastFocusedKey = null
            return@LaunchedEffect
        }
        val rowIndex = target.rowIndex
        val itemId = target.itemId

        val items = when (rowIndex) {
            0 -> movies
            1 -> series
            else -> {
                lastFocusedKey = null
                viewModel.lastFocusedKey = null
                return@LaunchedEffect
            }
        }
        val stillExists = items.any { it.id == itemId }
        if (!stillExists && items.isNotEmpty()) {
            // Focus the last item in the same row (closest to where the removed item was)
            val fallbackItem = items.last()
            val fallbackIndex = items.lastIndex
            lastFocusedKey = "${rowIndex}_${fallbackItem.id}_$fallbackIndex"
            viewModel.lastFocusedKey = lastFocusedKey
        } else if (!stillExists && items.isEmpty()) {
            // Row is now empty — focus the other row if it exists
            val otherItems = if (rowIndex == 0) series else movies
            if (otherItems.isNotEmpty()) {
                val otherRow = if (rowIndex == 0) 1 else 0
                lastFocusedKey = "${otherRow}_${otherItems.first().id}_0"
                viewModel.lastFocusedKey = lastFocusedKey
            } else {
                lastFocusedKey = null
                viewModel.lastFocusedKey = null
            }
        }
    }

    // Resolve missing posters (e.g., items pulled from Trakt)
    LaunchedEffect(movies) { movies.forEach { viewModel.resolvePosterIfNeeded(it) } }
    LaunchedEffect(series) { series.forEach { viewModel.resolvePosterIfNeeded(it) } }

    val isTopNav = currentProfile?.navPosition == "top"
    val startPadding = if (isTopNav) 50.dp else 120.dp
    val topPadding = if (isTopNav) 24.dp else 16.dp

    androidx.compose.runtime.CompositionLocalProvider(com.saab.tv.ui.components.LocalWatchedIds provides watchedIds,
        com.saab.tv.ui.components.LocalPosterFocusReturn provides { originalPosterFocus = it }) {
    Box(modifier = Modifier.fillMaxSize().onFocusChanged { hasContentFocus = it.hasFocus }.focusGroup()) {
        if (movies.isEmpty() && series.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .focusRequester(entryRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                            runCatching { drawerRequester.requestFocus() }
                            true
                        } else false
                    }
                    .focusable(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Your watchlist is empty",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topPadding)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 20.dp))

                if (movies.isNotEmpty()) {
                    InfiniteLoopRow(
                        startPadding = startPadding,
                        isTopNav = isTopNav,
                        rowIndex = 0,
                        title = "Movies",
                        items = movies,
                        onMovieClick = onMovieClick,
                        onMovieLongClick = onLongClick,
                        onViewMore = {},
                        onFocused = { item: MetaItem?, key: String ->
                            previewItem = item
                            lastFocusedKey = key
                            viewModel.lastFocusedKey = key
                        },
                        entryRequester = entryRequester,
                        drawerRequester = drawerRequester,
                        locallyFocusedItemId = restorableFocusKey?.takeIf { it.startsWith("0_") },
                        isGlobalFocusPresent = restorableFocusKey != null,
                        isFirstRow = true,
                        isInfiniteLoopEnabled = false,
                        upKeyDebouncer = upKeyDebouncer,
                        repeatGate = dpadRepeatGate,
                        externalListState = viewModel.movieRowState
                    )
                }

                if (series.isNotEmpty()) {
                    InfiniteLoopRow(
                        startPadding = startPadding,
                        isTopNav = isTopNav,
                        rowIndex = 1,
                        title = "Series",
                        items = series,
                        onMovieClick = onMovieClick,
                        onMovieLongClick = onLongClick,
                        onViewMore = {},
                        onFocused = { item: MetaItem?, key: String ->
                            previewItem = item
                            lastFocusedKey = key
                            viewModel.lastFocusedKey = key
                        },
                        entryRequester = entryRequester,
                        drawerRequester = drawerRequester,
                        locallyFocusedItemId = restorableFocusKey?.takeIf { it.startsWith("1_") },
                        isGlobalFocusPresent = restorableFocusKey != null,
                        isFirstRow = movies.isEmpty(),
                        isInfiniteLoopEnabled = false,
                        upKeyDebouncer = upKeyDebouncer,
                        repeatGate = dpadRepeatGate,
                        externalListState = viewModel.seriesRowState
                    )
                }
            }
        }
        actionItem?.let { item ->
            val density = LocalDensity.current
            val configuration = LocalConfiguration.current
            val popupWidthPx = with(density) { 124.dp.roundToPx() }
            val popupHeightPx = with(density) { 60.dp.roundToPx() }
            val marginPx = with(density) { 12.dp.roundToPx() }
            val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
            val screenHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }
            val x = (actionBounds.center.x.toInt() - popupWidthPx / 2)
                .coerceIn(marginPx, (screenWidthPx - popupWidthPx - marginPx).coerceAtLeast(marginPx))
            val y = (actionBounds.center.y.toInt() - popupHeightPx / 2)
                .coerceIn(marginPx, (screenHeightPx - popupHeightPx - marginPx).coerceAtLeast(marginPx))
            val firstActionRequester = remember(item.id) { FocusRequester() }
            var actionsArmed by remember(item.id) { mutableStateOf(false) }
            var suppressOpeningKeyUp by remember(item.id) { mutableStateOf(true) }
            LaunchedEffect(item.id) {
                delay(80)
                runCatching { firstActionRequester.requestFocus() }
                delay(420)
                actionsArmed = true
            }
            Popup(alignment = Alignment.TopStart, offset = IntOffset(x, y),
                onDismissRequest = { actionItem = null }, properties = PopupProperties(focusable = true)) {
                Row(Modifier.width(124.dp)
                    .onPreviewKeyEvent { event ->
                        if (suppressOpeningKeyUp && event.type == KeyEventType.KeyUp &&
                            (event.key == Key.Enter || event.key == Key.DirectionCenter)) {
                            suppressOpeningKeyUp = false
                            actionsArmed = true
                            true
                        } else false
                    }
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.42f), RoundedCornerShape(12.dp))
                    .padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CardActionIcon(Icons.Default.Videocam, "Watch Trailer", onClick = {
                        actionItem = null
                        if (manualTrailerLauncher != null) {
                            manualTrailerLauncher(item, null, null)
                        } else actionScope.launch {
                            val trailer = try { actionsViewModel.trailerFor(item) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { null }
                            if (trailer != null) onTrailerClick(trailer.first, trailer.second)
                            else android.widget.Toast.makeText(context, "No trailer available", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }, modifier = Modifier.weight(1f), enabled = actionsArmed, focusRequester = firstActionRequester)
                    CardActionIcon(Icons.Default.Bookmark, "Remove From Watchlist", onClick = {
                        viewModel.remove(item)
                        actionItem = null
                    }, modifier = Modifier.weight(1f), enabled = actionsArmed, destructive = true)
                }
            }
        }
    }
    } // CompositionLocalProvider
    com.saab.tv.ui.trailer.BackdropTrailerPreview(
        profileId = currentProfile?.id ?: 0,
        focusedItem = previewItem, catalog = if (previewItem?.type == "series") series else movies,
        settings = com.saab.tv.data.profile.rememberTrailerPreviewSettings(currentProfile?.id ?: 0),
        enabled = !com.saab.tv.ui.trailer.LocalManualTrailerActive.current && (hasContentFocus || previewActive) && actionItem == null && currentProfile != null,
        resolveTrailer = actionsViewModel::trailerFor,
        onActiveChanged = { previewActive = it },
        onFullscreenChanged = onPreviewActiveChanged,
        onOpen = onMovieClick, onDismiss = { runCatching { originalPosterFocus?.requestFocus() ?: entryRequester.requestFocus() } },
        homeViewModel = actionsViewModel
    )
}
