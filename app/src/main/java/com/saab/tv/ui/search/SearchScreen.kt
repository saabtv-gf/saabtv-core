package com.saab.tv.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.remote_input.RemoteInputMode
import com.saab.tv.ui.addons.RemotePasteDialog
import com.saab.tv.ui.components.SaabTvBackground
import com.saab.tv.ui.components.SaabTvCard
import com.saab.tv.ui.home.DpadRepeatGate
import com.saab.tv.ui.home.CatalogQuickActionsPopup

private fun FocusRequester.requestFocusSafely(): Boolean = runCatching { requestFocus(); true }.getOrDefault(false)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SearchScreen(
    entryRequester: FocusRequester, drawerRequester: FocusRequester,
    searchSessionId: Long = 0L, viewModel: SearchViewModel = hiltViewModel(),
    currentProfile: ProfileEntity?, onMovieClick: (MetaItem) -> Unit,
    onTrailerClick: (String, String) -> Unit = { _, _ -> },
    onPreviewActiveChanged: (Boolean) -> Unit = {},
    onViewMore: (String, List<MetaItem>) -> Unit = { _, _ -> },
    moviesViewMoreRequester: FocusRequester = remember { FocusRequester() },
    seriesViewMoreRequester: FocusRequester = remember { FocusRequester() },
    resultsRequester: FocusRequester = remember { FocusRequester() },
    lastFocusedId: String? = null, onFocusedIdChange: (String?) -> Unit = {},
    watchedIds: Set<String> = emptySet()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var actionItem by remember { mutableStateOf<MetaItem?>(null) }
    var actionBounds by remember { mutableStateOf(Rect.Zero) }
    val onLongClick: (MetaItem, Rect) -> Unit = { item, bounds ->
        actionItem = item
        actionBounds = bounds
    }
    var remoteSearch by remember { mutableStateOf(false) }
    var resultFocus by remember { mutableStateOf(false) }
    var previewActive by remember { mutableStateOf(false) }
    var originalPosterFocus by remember { mutableStateOf<FocusRequester?>(null) }
    var previewId by remember { mutableStateOf<String?>(null) }
    val previewViewModel = androidx.hilt.navigation.compose.hiltViewModel<com.saab.tv.ui.home.HomeViewModel>()
    val focusedResult: (String?) -> Unit = { id -> previewId = id; onFocusedIdChange(id) }
    var hasFocus by remember { mutableStateOf(false) }
    var focusEstablished by remember { mutableStateOf(false) }
    var restoreResultFocus by remember { mutableStateOf(lastFocusedId != null) }
    val moviesEntry = remember { FocusRequester() }
    val seriesEntry = remember { FocusRequester() }
    val topNav = currentProfile?.navPosition == "top"
    val firstResult = state.movies.firstOrNull() ?: state.series.firstOrNull()
    val focusTarget = state.results.firstOrNull { it.id == lastFocusedId } ?: firstResult
    val targetKey = focusTarget?.let { "${it.type}:${it.id}" }

    BackHandler(enabled = !topNav || hasFocus || !focusEstablished) { drawerRequester.requestFocusSafely() }
    LaunchedEffect(searchSessionId) {
        if (viewModel.beginSearchSession(searchSessionId)) {
            restoreResultFocus = false
            withFrameNanos { }
            entryRequester.requestFocusSafely()
        }
    }
    LaunchedEffect(lastFocusedId, targetKey) {
        if (restoreResultFocus && lastFocusedId != null && focusTarget?.id == lastFocusedId) {
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
            com.saab.tv.ui.components.LocalPosterFocusReturn provides { originalPosterFocus = it }) {
        BoxWithConstraints(Modifier.fillMaxSize().onFocusChanged {
            hasFocus = it.hasFocus
            if (hasFocus) focusEstablished = true
        }) {
            val top = if (topNav) 48.dp else 8.dp
            val posterHeight = ((maxHeight - top - 236.dp) / 2f - 42.dp).coerceIn(60.dp, 155.dp)
            Column(Modifier.fillMaxSize().padding(
                start = if (topNav) 36.dp else 90.dp, end = 28.dp, top = top, bottom = 8.dp
            )) {
                Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.query.ifBlank { "Search Movies And Series" }, style = MaterialTheme.typography.titleMedium,
                        color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (state.isLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                if (state.results.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(when {
                            state.isLoading -> "Searching…"
                            state.query.length < 3 -> "Type At Least 3 Characters Or Scan QR To Search"
                            state.error != null -> state.error.orEmpty()
                            else -> "No Matching Titles"
                        }, color = Color.LightGray)
                    }
                } else {
                    Column(Modifier.weight(1f).onFocusChanged { resultFocus = it.hasFocus }) {
                        SearchResultRow("Movies", state.movies, posterHeight, targetKey, resultsRequester,
                            entryRequester, drawerRequester, moviesViewMoreRequester, lastFocusedId, watchedIds,
                            onMovieClick, onLongClick, onViewMore, focusedResult, Modifier.weight(1f), moviesEntry,
                            drawerRequester, if (state.series.isNotEmpty()) seriesEntry else entryRequester)
                        SearchResultRow("Series", state.series, posterHeight, targetKey, resultsRequester,
                            entryRequester, drawerRequester, seriesViewMoreRequester, lastFocusedId, watchedIds,
                            onMovieClick, onLongClick, onViewMore, focusedResult, Modifier.weight(1f), seriesEntry,
                            if (state.movies.isNotEmpty()) moviesEntry else drawerRequester, entryRequester)
                    }
                }
                TvKeyboard(
                    onKeyPress = viewModel::appendCharacter, onBackspace = viewModel::removeCharacter,
                    onSpace = { viewModel.appendCharacter(" ") }, onClear = viewModel::clearSearch,
                    onOpenRemoteInput = { remoteSearch = true },
                    entryRequester = entryRequester, drawerRequester = drawerRequester,
                    isTopNav = topNav, hasResults = state.results.isNotEmpty(),
                    contentEntryRequester = resultsRequester
                )
            }
        }
        }
    }
    if (remoteSearch) RemotePasteDialog(
        onDismissRequest = { remoteSearch = false; entryRequester.requestFocusSafely() },
        onUrlReceived = viewModel::onQueryChange, mode = RemoteInputMode.SEARCH,
        title = "Remote Search", description = "Scan With Your Phone To Type A Search"
    )
    actionItem?.let { item ->
        CatalogQuickActionsPopup(item, actionBounds, currentProfile?.id ?: 1,
            onDismiss = { actionItem = null }, onTrailerClick = onTrailerClick)
    }
    com.saab.tv.ui.trailer.BackdropTrailerPreview(
        profileId = currentProfile?.id ?: 0,
        focusedItem = state.results.firstOrNull { it.id == previewId }, catalog = state.results,
        settings = com.saab.tv.data.profile.rememberTrailerPreviewSettings(currentProfile?.id ?: 0),
        enabled = false, // Search results never autoplay trailers.
        resolveTrailer = previewViewModel::trailerFor,
        onActiveChanged = { previewActive = it },
        onFullscreenChanged = onPreviewActiveChanged,
        onOpen = onMovieClick, onDismiss = { runCatching { originalPosterFocus?.requestFocus() ?: resultsRequester.requestFocusSafely() } }
    )
}

@Composable
private fun SearchResultRow(
    title: String, items: List<MetaItem>, posterHeight: Dp, targetKey: String?,
    targetRequester: FocusRequester, keyboardRequester: FocusRequester, drawerRequester: FocusRequester,
    moreRequester: FocusRequester, lastFocusedId: String?, watchedIds: Set<String>,
    onClick: (MetaItem) -> Unit, onLongClick: (MetaItem, Rect) -> Unit,
    onMore: (String, List<MetaItem>) -> Unit,
    onFocused: (String?) -> Unit, modifier: Modifier,
    rowRequester: FocusRequester, upRequester: FocusRequester, downRequester: FocusRequester
) {
    val listState = rememberLazyListState()
    var focusedTitle by remember { mutableStateOf("") }
    var rowFocusedId by remember(items) { mutableStateOf(items.firstOrNull()?.id) }
    val initialFocusedId = remember { lastFocusedId }
    var scrollRestored by remember { mutableStateOf(false) }
    LaunchedEffect(initialFocusedId, items) {
        val index = items.indexOfFirst { it.id == initialFocusedId }
        if (index >= 0 && !scrollRestored) {
            listState.scrollToItem(index)
            scrollRestored = true
        }
    }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (focusedTitle.isBlank()) title else "$title · $focusedTitle",
                color = Color.White, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (items.isNotEmpty()) TextButton(onClick = { onMore(title, items) }, modifier = Modifier
                .height(30.dp).focusRequester(moreRequester)
                .focusProperties { down = rowRequester; up = upRequester }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("View All", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (items.isEmpty()) Text("No Matching $title", color = Color.Gray)
        else LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(6.dp)) {
            itemsIndexed(items, key = { _, item -> "${item.type}:${item.id}" }) { index, item ->
                SaabTvCard(previewItem = item, title = item.name, posterUrl = item.poster, onClick = { onClick(item) },
                    onLongClick = { bounds -> onLongClick(item, bounds) },
                    isWatched = item.id in watchedIds,
                    onFocused = { focusedTitle = item.name; rowFocusedId = item.id; onFocused(item.id) },
                    normalWidth = posterHeight * 2f / 3f, normalHeight = posterHeight,
                    modifier = Modifier
                        .then(if ("${item.type}:${item.id}" == targetKey) Modifier.focusRequester(targetRequester) else Modifier)
                        .then(if (item.id == rowFocusedId) Modifier.focusRequester(rowRequester) else Modifier)
                        .focusProperties { down = downRequester; up = moreRequester }
                        .onPreviewKeyEvent {
                            if (com.saab.tv.ui.trailer.InlineTrailerAnchor.session != null) return@onPreviewKeyEvent false
                            if (it.type == KeyEventType.KeyDown && index == 0 && it.key == Key.DirectionLeft) {
                                drawerRequester.requestFocusSafely(); true
                            } else false
                        })
            }
        }
    }
}

@Composable
fun TvKeyboard(
    onKeyPress: (String) -> Unit, onBackspace: () -> Unit, onSpace: () -> Unit,
    onClear: () -> Unit, onOpenRemoteInput: () -> Unit,
    entryRequester: FocusRequester, drawerRequester: FocusRequester, isTopNav: Boolean,
    hasResults: Boolean, contentEntryRequester: FocusRequester? = null, modifier: Modifier = Modifier
) {
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
                    if (hasResults) contentEntryRequester?.requestFocusSafely()
                    else drawerRequester.requestFocusSafely()
                    true
                }
                column == 0 && it.key == Key.DirectionLeft -> {
                    if (!isTopNav) drawerRequester.requestFocusSafely()
                    true
                }
                column == lastColumn && it.key == Key.DirectionRight -> true
                row == 4 && it.key == Key.DirectionDown -> true
                else -> false
            }
        }
    Column(modifier.fillMaxWidth().height(192.dp).padding(top = 8.dp),
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
            val actions = listOf("Space" to onSpace, "Backspace" to onBackspace, "Clear" to onClear, "QR Search" to onOpenRemoteInput)
            actions.forEachIndexed { index, (label, action) ->
                KeyboardKey(label, action, keyModifier(label, 4, index, 3).width(if (index == 0) 170.dp else 106.dp))
            }
        }
    }
}

@Composable
private fun KeyboardKey(label: String, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    Button(onClick = onClick, modifier = modifier.height(32.dp).onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(5.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
        border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color.White.copy(alpha = 0.18f)),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            contentColor = if (focused) MaterialTheme.colorScheme.onPrimary else Color.White
        )) { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
}
