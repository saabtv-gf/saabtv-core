package com.saab.tv.ui.home

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.saab.tv.R
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.WatchHistoryEntity
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.schema.canonicalPresentationKey
import com.saab.tv.domain.DashboardTab
import com.saab.tv.domain.HeroConfig
import com.saab.tv.domain.HubGroupRow
import com.saab.tv.domain.CategoryRow
import com.saab.tv.domain.schema.CatalogCardProjection
import com.saab.tv.domain.HomeRowItem
import com.saab.tv.domain.heroFor
import com.saab.tv.domain.layoutFor
import com.saab.tv.ui.components.SaabTvBackground
import com.saab.tv.ui.components.SaabMotion
import com.saab.tv.ui.components.SaabTvCard
import com.saab.tv.ui.components.EpisodePositionLabel
import com.saab.tv.ui.components.SetupButton
import com.saab.tv.ui.components.FocusPivotSpec
import com.saab.tv.ui.navigation.LocalNavigationFocusHandoff
import com.saab.tv.ui.utils.requestFocusOrFallback
import com.saab.tv.ui.utils.requestFocusSafely
import com.saab.tv.ui.addons.VoidButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf

private const val RAPID_VERTICAL_NAV_WINDOW_MS = 220L
private const val RAPID_PREVIEW_UPDATE_MIN_INTERVAL_MS = 120L
private const val DPAD_REPEAT_INTERVAL_HORIZONTAL_MS = 150L
private const val DPAD_REPEAT_INTERVAL_VERTICAL_MS = 200L

internal data class ShelfFocusRoute(
    val entry: FocusRequester,
    val up: FocusRequester?,
    val down: FocusRequester?
)

/** Keeps focus targets stable when asynchronous Home shelves are inserted or removed. */
internal class ShelfFocusRouteRegistry {
    private val requesters = linkedMapOf<String, FocusRequester>()

    fun routesFor(rowKeys: List<String>): Map<String, ShelfFocusRoute> {
        requesters.keys.retainAll(rowKeys.toSet())
        rowKeys.forEach { key -> requesters.getOrPut(key) { FocusRequester() } }

        return buildMap(rowKeys.size) {
            rowKeys.forEachIndexed { index, key ->
                put(
                    key,
                    ShelfFocusRoute(
                        entry = requesters.getValue(key),
                        up = rowKeys.getOrNull(index - 1)?.let(requesters::get),
                        down = rowKeys.getOrNull(index + 1)?.let(requesters::get)
                    )
                )
            }
        }
    }
}

@Composable
private fun rememberShelfFocusRoutes(rowKeys: List<String>): Map<String, ShelfFocusRoute> {
    val registry = remember { ShelfFocusRouteRegistry() }
    return remember(rowKeys, registry) { registry.routesFor(rowKeys) }
}

private class HomeFocusTimingTracker {
    var previous: Long = 0L
    var current: Long = 0L

    fun mark(now: Long = System.currentTimeMillis()) {
        previous = current
        current = now
    }

    fun isRapid(windowMs: Long): Boolean = current - previous in 1..windowMs
}

private class PreviewUpdateGate {
    var lastUpdateMs: Long = 0L
}

@Composable
fun HomeScreen(
    tab: DashboardTab,
    screenNameOverride: String? = null,
    entryRequester: FocusRequester,
    drawerRequester: FocusRequester,
    viewModel: HomeViewModel = hiltViewModel(),
    currentProfile: ProfileEntity?,
    onMovieClick: (MetaItem) -> Unit,
    onContinueClick: (MetaItem) -> Unit = onMovieClick,
    onTrailerClick: (String, String) -> Unit = { _, _ -> },
    onPreviewActiveChanged: (Boolean) -> Unit = {},
    onViewMore: (String, List<MetaItem>, String) -> Unit = { _, _, _ -> },
    onHubLoaded: (String, List<MetaItem>, String) -> Unit = onViewMore
) {
    val authorizeNavigationHandoff = LocalNavigationFocusHandoff.current
    val manualTrailerLauncher = com.saab.tv.ui.trailer.LocalManualTrailerLauncher.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val profileWatchedIds by remember(viewModel, currentProfile?.id) {
        currentProfile?.id?.let(viewModel::watchedIdsForProfile) ?: flowOf(emptySet())
    }.collectAsStateWithLifecycle(initialValue = emptySet())
    val actionScope = rememberCoroutineScope()
    val homeContext = LocalContext.current
    var longPressedItem by remember { mutableStateOf<Pair<MetaItem, Boolean>?>(null) }
    var longPressedBounds by remember { mutableStateOf(Rect.Zero) }
    var originalPosterFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val onMovieLongClick: (MetaItem, Boolean, Rect) -> Unit = { item, isContinue, bounds ->
        longPressedBounds = bounds
        longPressedItem = item to isContinue
    }
    val isOttScreen = screenNameOverride == "ott"
    val ottContentEntryRequester = remember { FocusRequester() }
    val contentEntryRequester = if (isOttScreen) ottContentEntryRequester else entryRequester
    val dismissQuickActions: () -> Unit = {
        longPressedItem = null
        actionScope.launch {
            withFrameNanos { }
            originalPosterFocus.requestFocusOrFallback(contentEntryRequester)
        }
    }
    val layoutMode = if (isOttScreen) "simple" else currentProfile?.layoutFor(tab) ?: "simple"
    val isTopNav = currentProfile?.navPosition == "top"
    val isLandscapeContinueWatching = currentProfile?.titleCardShape == "landscape"
    val infoTopPadding = if (isTopNav) 60.dp else 30.dp
    val startPadding = if (isTopNav) 50.dp else 120.dp

    val defaultScreenName = remember(tab) {
        when (tab) {
            DashboardTab.HOME -> "home"
            DashboardTab.MOVIES -> "movies"
            DashboardTab.SERIES -> "series"
        }
    }
    val screenName = screenNameOverride ?: defaultScreenName

    LaunchedEffect(currentProfile?.id, currentProfile?.tmdbEnabled, currentProfile?.tmdbLanguage, currentProfile?.titleCardShape) {
        viewModel.configureTmdbProfile(currentProfile)
    }

    // During tab switch, ignore persisted focus/scroll until this tab is actually loaded.
    // This avoids one-frame carry-over from the previous tab.
    val isCurrentTabLoaded = state.loadedScreen == screenName && state.loadedProfileId == currentProfile?.id
    val lastFocusedKey = if (isCurrentTabLoaded) state.lastFocusedKey else null
    val rowScrollPositions = if (isCurrentTabLoaded) viewModel.getRowScrollPositions() else emptyMap()
    val verticalScrollPosition = if (isCurrentTabLoaded) viewModel.getVerticalScrollPosition() else Pair(0, 0)

    // Track if content has focus to conditionally enable BackHandler
    var isContentFocused by remember { mutableStateOf(false) }
    var previewActive by remember { mutableStateOf(false) }
    // Guards against double-back race: when returning from details, focus restoration
    // takes ~200ms. Until focus is established, keep BackHandler enabled so a quick
    // second back press doesn't exit the app. Resets on each fresh composition.
    var focusEverSet by remember { mutableStateOf(false) }

    // Only enable explicit back handling if:
    // 1. We are in Side-Nav mode (Always handle)
    // 2. We are in Top-Nav mode AND content is focused (Handle = Open Nav)
    // 3. Top-Nav mode AND focus not yet established (transition guard)
    // If Top-Nav mode AND content is NOT focused AND focus was already set, disable this
    // handler so TopNavigationBar's handler can "Close Nav" (return to content).
    com.saab.tv.ui.navigation.AppBackHandler(
        enabled = isContentFocused,
        priority = com.saab.tv.ui.navigation.AppBackPriority.Screen
    ) {
        authorizeNavigationHandoff?.invoke(null)
        drawerRequester.requestFocusSafely()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged {
                isContentFocused = it.hasFocus
                if (it.hasFocus) focusEverSet = true
            }
            .focusGroup()
    ) {
        SaabTvBackground {
        CompositionLocalProvider(com.saab.tv.ui.components.LocalWatchedIds provides profileWatchedIds,
            com.saab.tv.ui.components.LocalPosterFocusReturn provides { originalPosterFocus = it }) {
        // LOGIC: If we are just starting OR the ViewModel is loading, show the Loading Box.
        // This box accepts focus immediately, which forces the NavDrawer to collapse.
        if (state.isLoading || state.loadedScreen != screenName || state.loadedProfileId != currentProfile?.id) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(entryRequester)
                    .focusable(),
                contentAlignment = Alignment.Center
            ) {
                if (isOttScreen) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.ott_loading_title),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Text(
                            text = stringResource(R.string.ott_loading_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.68f)
                        )
                        LinearProgressIndicator(
                            modifier = Modifier.width(420.dp).height(5.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.White.copy(alpha = 0.14f)
                        )
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp
                        )
                        Text(
                            text = stringResource(R.string.catalog_grid_loading),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.72f)
                        )
                    }
                }
            }
        } else if (state.rows.isEmpty() && state.mixedRows.isEmpty() && state.errorMessage != null) {
            LaunchedEffect(state.errorMessage) {
                delay(100)
                runCatching { entryRequester.requestFocus() }
            }
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 620.dp).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(
                            if (isOttScreen) R.string.ott_catalog_title else R.string.catalog_load_error_title
                        ),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = if (state.errorMessage == "catalog_load_failed") {
                            stringResource(R.string.catalog_load_failed)
                        } else state.errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.72f),
                        textAlign = TextAlign.Center
                    )
                    if (isOttScreen) {
                        Spacer(Modifier.height(24.dp))
                        OttRefreshButton(
                            onClick = { viewModel.refreshOtt(currentProfile) },
                            modifier = Modifier.focusRequester(entryRequester)
                        )
                    } else {
                        Spacer(Modifier.height(24.dp))
                        SetupButton(
                            text = stringResource(R.string.common_retry),
                            onClick = { viewModel.loadScreen(screenName, currentProfile, forceReload = true) },
                            modifier = Modifier.focusRequester(entryRequester),
                            primary = true
                        )
                    }
                }
            }
        } else if (
            !isOttScreen && state.rows.isEmpty() && state.mixedRows.isEmpty() && state.heroRow == null
        ) {
            Box(
                modifier = Modifier.fillMaxSize().focusRequester(entryRequester).focusable(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 620.dp).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.catalog_tab_empty_title),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Text(
                        text = stringResource(R.string.catalog_tab_empty_description),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.72f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            // DATA IS READY: Show Content
            val hasInProgressHistory = remember(state.history, state.seriesNextUp) {
                state.history.any { !it.watched } || state.seriesNextUp.any { !it.isComplete }
            }
            if (layoutMode == "cinematic") {
                CinematicLayout(
                    infoTopPadding = infoTopPadding,
                    startPadding = startPadding,
                    isTopNav = isTopNav,
                    state = state,
                    onMovieClick = onMovieClick,
                    onContinueClick = onContinueClick,
                    onMovieLongClick = onMovieLongClick,
                    onViewMore = onViewMore,
                    onHubClick = { hubItem ->
                        onViewMore(hubItem.title, emptyList(), hubItem.categoryId)
                        viewModel.openHub(hubItem) { title, items ->
                            onHubLoaded(title, items, hubItem.categoryId)
                        }
                    },
                    onLoadMore = { configId -> viewModel.loadMoreItems(configId) },
                    entryRequester = entryRequester,
                    drawerRequester = drawerRequester,
                    lastFocusedKey = lastFocusedKey,
                    rowScrollPositions = rowScrollPositions,
                    verticalScrollPosition = verticalScrollPosition,
                    historyScrollAdjustment = if (viewModel.needsHistoryScrollAdjustment(hasInProgressHistory)) 1 else 0,
                    onFocusChange = { viewModel.setLastFocusedKey(it) },
                    onScrollPositionChange = { key, pos -> viewModel.setRowScrollPosition(key, pos) },
                    onVerticalScrollChange = { viewModel.setVerticalScrollPosition(it, hasInProgressHistory) },
                    onPreviewItemVisible = {
                        viewModel.ensureMetadataFallback(it)
                        viewModel.ensureTmdbEnrichment(it)
                    },
                    onPrepareArtworkItem = { viewModel.ensureTmdbEnrichment(it) },
                    onWatchedItemShown = { item -> currentProfile?.id?.let { viewModel.ensureWatchedAlias(it, item) } },
                    isLandscapeContinueWatching = isLandscapeContinueWatching
                )
            } else {
                val heroConfig = if (isOttScreen) HeroConfig(null, 0, 0)
                    else currentProfile?.heroFor(tab) ?: HeroConfig(null, 10, 0)
                val heroItems = remember(state.heroRow, heroConfig.posterCount) {
                    state.heroRow?.items?.take(heroConfig.posterCount)
                        ?.map(CatalogCardProjection::asActionItem) ?: emptyList()
                }

                SimpleLayout(
                    startPadding = startPadding,
                    isTopNav = isTopNav,
                    state = state,
                    heroItems = heroItems,
                    heroAutoScrollSeconds = heroConfig.autoScrollSeconds,
                    onMovieClick = onMovieClick,
                    onContinueClick = onContinueClick,
                    onMovieLongClick = onMovieLongClick,
                    onViewMore = onViewMore,
                    onHubClick = { hubItem ->
                        onViewMore(hubItem.title, emptyList(), hubItem.categoryId)
                        viewModel.openHub(hubItem) { title, items ->
                            onHubLoaded(title, items, hubItem.categoryId)
                        }
                    },
                    onLoadMore = { configId -> viewModel.loadMoreItems(configId) },
                    entryRequester = contentEntryRequester,
                    drawerRequester = drawerRequester,
                    lastFocusedKey = lastFocusedKey,
                    rowScrollPositions = rowScrollPositions,
                    verticalScrollPosition = verticalScrollPosition,
                    historyScrollAdjustment = if (
                        viewModel.needsHistoryScrollAdjustment(hasInProgressHistory) &&
                        (heroItems.isEmpty() || verticalScrollPosition.first > 0)
                    ) 1 else 0,
                    onFocusChange = { viewModel.setLastFocusedKey(it) },
                    onScrollPositionChange = { key, pos -> viewModel.setRowScrollPosition(key, pos) },
                    onVerticalScrollChange = { viewModel.setVerticalScrollPosition(it, hasInProgressHistory) },
                    onHeroItemVisible = {
                        viewModel.ensureMetadataFallback(it)
                        viewModel.ensureTmdbEnrichment(it)
                    },
                    onPrepareArtworkItem = { viewModel.ensureTmdbEnrichment(it) },
                    onWatchedItemShown = { item -> currentProfile?.id?.let { viewModel.ensureWatchedAlias(it, item) } },
                    topActionRequester = entryRequester.takeIf { isOttScreen },
                    onRefreshOtt = if (isOttScreen) {
                        { viewModel.refreshOtt(currentProfile) }
                    } else {
                        null
                    },
                    isLandscapeContinueWatching = isLandscapeContinueWatching
                )
            }
        }
        longPressedItem?.takeIf { currentProfile != null }?.let { (item, isContinue) ->
            var confirmClear by remember(item.id) { mutableStateOf(false) }
            val firstActionRequester = remember(item.id, isContinue, confirmClear) { FocusRequester() }
            var actionsArmed by remember(item.id, isContinue, confirmClear) { mutableStateOf(false) }
            val profileId = requireNotNull(currentProfile).id
            val historyEntry = state.history.firstOrNull {
                it.id == item.id || (item.type == "series" && canonicalSeriesId(it.id) == item.id && !it.watched)
            }
            val nextUp = state.seriesNextUp.firstOrNull { it.seriesId == item.id }
            var watchlisted by remember(item.id, profileId) { mutableStateOf<Boolean?>(null) }
            var watched by remember(item.id, profileId) { mutableStateOf(item.id in profileWatchedIds) }
            var watchlistStateLoaded by remember(item.id, profileId) { mutableStateOf(false) }
            var watchedStateLoaded by remember(item.id, profileId) { mutableStateOf(false) }
            LaunchedEffect(item.id, isContinue, confirmClear) {
                actionsArmed = true
                withFrameNanos { }
                runCatching { firstActionRequester.requestFocus() }
            }
            LaunchedEffect(item.id, profileId) {
                watchlisted = try {
                    viewModel.isWatchlisted(profileId, item.id).also { watchlistStateLoaded = true }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
            LaunchedEffect(item.id, profileId, profileWatchedIds) {
                try {
                    watched = item.id in profileWatchedIds || viewModel.isTitleWatched(profileId, item)
                    watchedStateLoaded = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    watchedStateLoaded = false
                }
            }
            var pausedFrame by remember(historyEntry?.id, historyEntry?.position) {
                mutableStateOf<android.graphics.Bitmap?>(null)
            }
            LaunchedEffect(historyEntry?.id, historyEntry?.position, profileId) {
                pausedFrame = historyEntry?.takeIf { !it.watched }?.let {
                    viewModel.cachedPauseFrame(profileId, it.id, it.position, currentProfile?.seekTimeIntervalSeconds ?: 30)
                }
            }
            val density = LocalDensity.current
            val screen = LocalConfiguration.current
            val popupWidth = if (isContinue) 210.dp else 176.dp
            val popupWidthPx = with(density) { popupWidth.roundToPx() }
            val popupHeightPx = with(density) {
                (if (confirmClear) 106.dp else if (isContinue && pausedFrame != null) 174.dp else if (isContinue) 100.dp else 60.dp).roundToPx()
            }
            val marginPx = with(density) { 12.dp.roundToPx() }
            val screenWidthPx = with(density) { screen.screenWidthDp.dp.roundToPx() }
            val screenHeightPx = with(density) { screen.screenHeightDp.dp.roundToPx() }
            val popupX = (longPressedBounds.center.x.toInt() - popupWidthPx / 2)
                .coerceIn(marginPx, (screenWidthPx - popupWidthPx - marginPx).coerceAtLeast(marginPx))
            val popupY = (longPressedBounds.center.y.toInt() - popupHeightPx / 2)
                .coerceIn(marginPx, (screenHeightPx - popupHeightPx - marginPx).coerceAtLeast(marginPx))
            Popup(alignment = Alignment.TopStart, offset = IntOffset(popupX, popupY),
                onDismissRequest = dismissQuickActions, properties = PopupProperties(focusable = true)) {
              Column(
                  Modifier.width(popupWidth)
                      .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                      .border(1.dp, Color.White.copy(alpha = 0.42f), RoundedCornerShape(12.dp))
                      .padding(8.dp),
                  verticalArrangement = Arrangement.spacedBy(5.dp)
              ) {
                if (confirmClear) {
                    Text(stringResource(R.string.quick_action_clear_progress_prompt), style = MaterialTheme.typography.bodySmall, color = Color.White)
                    CatalogQuickActionStrip(
                        listOf(
                            CatalogQuickAction(
                                Icons.Default.Delete,
                                stringResource(R.string.details_action_clear_progress),
                                onClick = {
                                    viewModel.clearContinueProgress(profileId, item)
                                    dismissQuickActions()
                                },
                                enabled = actionsArmed,
                                destructive = true,
                                focusRequester = firstActionRequester
                            ),
                            CatalogQuickAction(
                                Icons.Default.Close,
                                stringResource(R.string.common_cancel),
                                onClick = { confirmClear = false },
                                enabled = actionsArmed
                            )
                        )
                    )
                } else if (isContinue) {
                    if (item.type == "series") {
                        val parts = historyEntry?.id?.split(":").orEmpty()
                        val season = parts.getOrNull(1)?.toIntOrNull() ?: nextUp?.nextSeason
                        val episode = parts.getOrNull(2)?.toIntOrNull() ?: nextUp?.nextEpisode
                        if (season != null && episode != null) {
                            EpisodePositionLabel(season, episode)
                        }
                    }
                    historyEntry?.let {
                        val remainingMs = (it.duration - it.position).coerceAtLeast(0L)
                        Text(stringResource(R.string.quick_action_remaining_minutes, remainingMs / 60_000), style = MaterialTheme.typography.bodySmall, color = Color.White)
                    }
                    pausedFrame?.let { bitmap ->
                        Image(bitmap = bitmap.asImageBitmap(), contentDescription = stringResource(R.string.quick_action_paused_scene), modifier = Modifier.fillMaxWidth().height(70.dp), contentScale = ContentScale.Fit)
                    }
                    CatalogQuickActionStrip(
                        listOf(
                            CatalogQuickAction(
                                Icons.Default.PlayArrow,
                                stringResource(R.string.details_action_resume),
                                onClick = { longPressedItem = null; onContinueClick(item) },
                                enabled = actionsArmed,
                                focusRequester = firstActionRequester
                            ),
                            CatalogQuickAction(
                                Icons.Default.DoneAll,
                                if (watched) stringResource(R.string.quick_action_mark_unwatched)
                                else stringResource(R.string.episode_mark_watched),
                                onClick = {
                                    if (watched) {
                                        viewModel.unmarkTitleWatched(profileId, item)
                                        watched = false
                                    } else {
                                        viewModel.markTitleWatched(profileId, item)
                                        watched = true
                                    }
                                    dismissQuickActions()
                                },
                                enabled = actionsArmed && watchedStateLoaded
                            ),
                            CatalogQuickAction(
                                Icons.Default.Delete,
                                stringResource(R.string.details_action_clear_progress),
                                onClick = { confirmClear = true },
                                enabled = actionsArmed,
                                destructive = true
                            )
                        )
                    )
                } else {
                    CatalogQuickActionStrip(
                        listOf(
                            CatalogQuickAction(
                                Icons.Default.Videocam,
                                stringResource(R.string.quick_action_watch_trailer),
                                onClick = {
                                    if (manualTrailerLauncher != null) {
                                        manualTrailerLauncher(item, null, null, null)
                                    } else actionScope.launch {
                                        val trailer = try {
                                            viewModel.trailerFor(item)
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            null
                                        }
                                        if (trailer != null) onTrailerClick(trailer.first, trailer.second)
                                        else android.widget.Toast.makeText(homeContext, homeContext.getString(R.string.quick_action_no_trailer), android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    longPressedItem = null
                                },
                                enabled = actionsArmed,
                                focusRequester = firstActionRequester
                            ),
                            CatalogQuickAction(
                                Icons.Default.DoneAll,
                                if (watched) stringResource(R.string.quick_action_mark_unwatched)
                                else stringResource(R.string.episode_mark_watched),
                                onClick = {
                                    if (watched) {
                                        viewModel.unmarkTitleWatched(profileId, item)
                                        watched = false
                                    } else {
                                        viewModel.markTitleWatched(profileId, item)
                                        watched = true
                                    }
                                    dismissQuickActions()
                                },
                                enabled = actionsArmed && watchedStateLoaded
                            ),
                            CatalogQuickAction(
                                if (watchlisted == true) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                if (watchlisted == true) stringResource(R.string.quick_action_remove_watchlist)
                                else stringResource(R.string.details_action_add_watchlist),
                                onClick = {
                                    viewModel.toggleWatchlist(profileId, item)
                                    dismissQuickActions()
                                },
                                enabled = actionsArmed && watchlistStateLoaded,
                                destructive = watchlisted == true
                            )
                        )
                    )
                }
              }
            }
        }
        } // CompositionLocalProvider
        val previewSettings = com.saab.tv.data.profile.rememberTrailerPreviewSettings(currentProfile?.id ?: 0)
        val previewHistory = remember(state.history, state.seriesNextUp) {
            buildContinueWatchingItems(state.history, state.seriesNextUp)
        }
        val previewMetadataIndex = remember(state.mixedRows) {
            homePreviewMetadataIndex(state.mixedRows)
        }
        val previewItem = remember(state.lastFocusedKey, state.mixedRows, previewHistory) {
            resolveCinematicPreviewItem(state.lastFocusedKey, state.mixedRows, previewHistory)
                ?: state.lastFocusedKey?.takeIf { it.startsWith("hero_") }?.removePrefix("hero_")
                    ?.let { id -> state.heroRow?.items?.firstOrNull { it.asActionItem().id == id }?.asActionItem() }
        }
        val isHistoryPreview = state.lastFocusedKey?.startsWith("-1_") == true
        com.saab.tv.ui.trailer.BackdropTrailerPreview(
            profileId = currentProfile?.id ?: 0,
            focusedItem = previewItem, settings = previewSettings,
            enabled = !com.saab.tv.ui.trailer.LocalManualTrailerActive.current && com.saab.tv.ui.trailer.TrailerPreviewPolicy.allowsHover(false, state.lastFocusedKey?.startsWith("-1_") == true) && isCurrentTabLoaded && (isContentFocused || previewActive) && longPressedItem == null && currentProfile != null,
            resolveTrailer = viewModel::trailerFor, onActiveChanged = { previewActive = it },
            onFullscreenChanged = onPreviewActiveChanged,
            onOpen = { item ->
                if (state.lastFocusedKey?.startsWith("-1_") == true) onContinueClick(item) else onMovieClick(item)
            },
            onDismiss = { originalPosterFocus.requestFocusOrFallback(contentEntryRequester) },
            homeViewModel = viewModel
        )
    }
}
}

@Composable
internal fun CardActionIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean,
    focusRequester: FocusRequester? = null,
    destructive: Boolean = false
) {
    com.saab.tv.ui.components.DetailActionButton(
        label = description, icon = icon, onClick = onClick, enabled = enabled,
        modifier = modifier.then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        compact = true, destructive = destructive && description == stringResource(R.string.details_action_clear_progress)
    )
}

@Composable
internal fun OttRefreshButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SetupButton(
        text = stringResource(R.string.ott_refresh),
        onClick = onClick,
        modifier = modifier,
        primary = true,
        content = {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            androidx.compose.material3.Text(
                text = stringResource(R.string.ott_refresh),
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold
                )
            )
        }
    )
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CinematicLayout(
    infoTopPadding: androidx.compose.ui.unit.Dp,
    startPadding: androidx.compose.ui.unit.Dp,
    isTopNav: Boolean,
    state: HomeViewModel.HomeState,
    onMovieClick: (MetaItem) -> Unit,
    onContinueClick: (MetaItem) -> Unit,
    onMovieLongClick: (MetaItem, Boolean, Rect) -> Unit,
    onViewMore: (String, List<MetaItem>, String) -> Unit,
    onHubClick: (com.saab.tv.domain.HubItem) -> Unit,
    onLoadMore: (String) -> Unit,
    entryRequester: FocusRequester,
    drawerRequester: FocusRequester,
    lastFocusedKey: String?,
    rowScrollPositions: Map<String, Pair<Int, Int>>,
    verticalScrollPosition: Pair<Int, Int>,
    historyScrollAdjustment: Int,
    onFocusChange: (String) -> Unit,
    onScrollPositionChange: (String, Pair<Int, Int>) -> Unit,
    onVerticalScrollChange: (Pair<Int, Int>) -> Unit,
    onPreviewItemVisible: (MetaItem) -> Unit,
    onPrepareArtworkItem: (MetaItem) -> Unit = {},
    onWatchedItemShown: (MetaItem) -> Unit = {},
    isLandscapeContinueWatching: Boolean = false
) {
    val continueWatchingTitle = stringResource(R.string.library_continue_watching)
    var instantFocusItem by remember { mutableStateOf<MetaItem?>(null) }
    var displayedItem by remember { mutableStateOf<MetaItem?>(null) }
    var hasRequestedFocus by remember { mutableStateOf(false) }
    val previewUpdateGate = remember { PreviewUpdateGate() }

    // Cache the history items transformation to avoid allocating new list on every recomposition
    // Stable key: only rebuild when items/order/watched-state change, not when images update
    val historyKey = remember(state.history) {
        state.history.map { "${it.id}:${it.watched}:${it.lastWatched}:${it.poster != null}" }
    }
    val nextUpKey = remember(state.seriesNextUp) {
        state.seriesNextUp.map { "${it.seriesId}:${it.isComplete}:${it.nextSeason}:${it.nextEpisode}:${it.updatedAt}:${it.poster != null}" }
    }
    val historyItems = remember(historyKey, nextUpKey) {
        buildContinueWatchingItems(state.history, state.seriesNextUp)
    }
    val shelfFocusKeys = remember(historyItems.isNotEmpty(), state.mixedRows) {
        buildList {
            if (historyItems.isNotEmpty()) add("history")
            addAll(state.mixedRows.map { it.id })
        }
    }
    val shelfFocusRoutes = rememberShelfFocusRoutes(shelfFocusKeys)
    val focusRestorationIndex = remember(state.mixedRows) {
        homeFocusRestorationIndex(state.mixedRows)
    }

    // Proactive metadata fetch for continue watching cards (posters + landscape)
    LaunchedEffect(historyItems) {
        // Keep initial TMDB enrichment work bounded; focused navigation warms the
        // next item on demand, so preferring the first few recent titles is enough.
        historyItems.take(3).forEach { item -> onPreviewItemVisible(item) }
    }

    // Redirect stale continue-watching focus key after progress was cleared
    val effectiveLastFocusedKey = remember(lastFocusedKey, historyItems, focusRestorationIndex) {
        resolveEffectiveFocusKey(lastFocusedKey, historyItems, state.mixedRows, focusRestorationIndex)
    }
    val historyFocusRedirected = effectiveLastFocusedKey != lastFocusedKey

    val restoredPreviewItem = remember(effectiveLastFocusedKey, state.mixedRows, historyItems) {
        resolveCinematicPreviewItem(
            lastFocusedKey = effectiveLastFocusedKey,
            mixedRows = state.mixedRows,
            historyItems = historyItems
        )
    }
    val previewMetadataIndex = remember(state.mixedRows) {
        homePreviewMetadataIndex(state.mixedRows)
    }
    val renderedPreviewItem = remember(displayedItem, state.enrichedMeta, state.heroRow, historyItems, previewMetadataIndex) {
        resolveLatestPreviewItem(displayedItem, state, historyItems, previewMetadataIndex)
    }

    LaunchedEffect(state.rows, state.history, restoredPreviewItem, effectiveLastFocusedKey) {
        if (instantFocusItem == null) {
            val first = when {
                restoredPreviewItem != null -> restoredPreviewItem
                // Returning from details with a saved focus key: avoid showing the wrong
                // poster while focus restoration is still in progress.
                effectiveLastFocusedKey != null -> null
                else -> historyItems.firstOrNull() ?: state.rows.firstOrNull()?.items?.firstOrNull()
                    ?.asActionItem()
            }
            instantFocusItem = first
            displayedItem = first
            if (first != null) {
                onPreviewItemVisible(first)
            }
        }

        // SMART FOCUS:
        // We are now safe to request focus because HomeScreen ensures we only reach here when data is ready.
        if (!hasRequestedFocus && (historyItems.isNotEmpty() || state.mixedRows.isNotEmpty())) {
            repeat(8) {
                if (!hasRequestedFocus) {
                    delay(100)
                    hasRequestedFocus = requestFocusIfReady(entryRequester::requestFocus)
                }
            }
        }
    }

    LaunchedEffect(instantFocusItem) {
        val target = instantFocusItem ?: return@LaunchedEffect
        displayedItem = target
    }

    val density = LocalDensity.current
    // Adjust pivot with 40dp offset to prevent slide-up (5.dp content padding + 40.dp)
    val titleHeadroomPx = remember(density) { with(density) { 45.dp.toPx() } }
    
    // Skip vertical scroll on restoration (when we have a saved focus key)
    val isVerticalRestoration = effectiveLastFocusedKey != null
    var skipVerticalScroll by remember(isVerticalRestoration) { mutableStateOf(isVerticalRestoration) }

    // Track focus timing without triggering recomposition on every focus hop.
    val verticalFocusTiming = remember { HomeFocusTimingTracker() }

    val verticalPivot = remember(titleHeadroomPx) {
        FocusPivotSpec(
            customOffset = titleHeadroomPx,
            skipScrollProvider = { skipVerticalScroll }
        )
    }
    
    // Wrap onFocusChange to track timing for vertical dynamic stiffness
    val wrappedOnFocusChange: (String) -> Unit = remember(onFocusChange) {
        { key ->
            verticalFocusTiming.mark()
            onFocusChange(key)
        }
    }

    fun updatePreviewItem(item: MetaItem?) {
        if (item == null) return
        val now = System.currentTimeMillis()
        val isRapid = verticalFocusTiming.isRapid(RAPID_VERTICAL_NAV_WINDOW_MS)
        val allowUpdate = HomePreviewMetadataPolicy.shouldWarmFocusedMetadata(
            isRapid,
            now - previewUpdateGate.lastUpdateMs,
            RAPID_PREVIEW_UPDATE_MIN_INTERVAL_MS
        )
        if (!allowUpdate) return

        // Only warm addon/TMDB metadata for a preview that can actually be shown.
        // Rapid D-pad focus hops are intentionally ignored to avoid request fan-out.
        onPreviewItemVisible(item)
        if (instantFocusItem?.id != item.id || instantFocusItem?.type != item.type) {
            instantFocusItem = item
            previewUpdateGate.lastUpdateMs = now
        } else {
            previewUpdateGate.lastUpdateMs = now
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Keep the catalog backdrop visible while optional enrichment arrives;
        // crossfade to the richer artwork instead of flashing through black.
        CinematicBackground(renderedPreviewItem)

        Column(modifier = Modifier.fillMaxSize().zIndex(2f)) {
            // INFO SECTION
            Box(
                modifier = Modifier
                    .weight(0.45f)
                    .fillMaxWidth()
                    .padding(start = startPadding, top = infoTopPadding),
                contentAlignment = Alignment.TopStart
            ) {
                AnimatedContent(
                    targetState = renderedPreviewItem,
                    contentKey = { it?.canonicalPresentationKey() },
                    transitionSpec = {
                        fadeIn(tween(SaabMotion.duration(SaabMotion.HERO_INFO_IN_MS)))
                            .togetherWith(fadeOut(tween(SaabMotion.duration(SaabMotion.HERO_INFO_OUT_MS))))
                    },
                    label = "Info"
                ) { item ->
                    if (item != null) {
                        // Show catalog facts immediately; enrich this same title
                        // in place instead of hiding or fading it a second time.
                        Column {
                            Box(
                                modifier = Modifier
                                    .width(700.dp)
                                    .height(90.dp),
                                contentAlignment = Alignment.BottomStart
                            ) {
                                if (!item.logo.isNullOrEmpty()) {
                                    SubcomposeAsyncImage(
                                        model = item.logo,
                                        contentDescription = item.name,
                                        contentScale = ContentScale.Fit,
                                        alignment = Alignment.BottomStart,
                                        modifier = Modifier
                                            .widthIn(max = 500.dp)
                                            .heightIn(max = 90.dp),
                                        error = {
                                            Text(
                                                text = item.name,
                                                style = MaterialTheme.typography.displaySmall.copy(
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontSize = 32.sp,
                                                    lineHeight = 1.05.em
                                                ),
                                                color = Color.White,
                                                maxLines = 2,
                                                softWrap = true,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    )
                                } else {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.displaySmall.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 32.sp,
                                            lineHeight = 1.05.em
                                        ),
                                        color = Color.White,
                                        maxLines = 2,
                                        softWrap = true,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            HomeMetaStrip(item = item)
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = item.description ?: "",
                                maxLines = if (isTopNav) 3 else 4,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, letterSpacing = 0.sp, lineHeight = 1.2.em),
                                color = Color.White.copy(0.85f),
                                textAlign = TextAlign.Start,
                                modifier = Modifier.width(450.dp)
                            )
                        }
                    }
                }
            }

            // LIST SECTION
            Box(modifier = Modifier.weight(0.55f)) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides verticalPivot) {
                    // Use persisted vertical scroll position for instant restoration.
                    // When the continue watching row first appears at index 0, all saved
                    // indices are off by 1 — adjust the initial index to compensate.
                    val verticalListState = rememberLazyListState(
                        initialFirstVisibleItemIndex = verticalScrollPosition.first + historyScrollAdjustment,
                        initialFirstVisibleItemScrollOffset = verticalScrollPosition.second
                    )

                    val dpadRepeatGate = remember {
                        DpadRepeatGate(
                            horizontalRepeatIntervalMs = DPAD_REPEAT_INTERVAL_HORIZONTAL_MS,
                            verticalRepeatIntervalMs = DPAD_REPEAT_INTERVAL_VERTICAL_MS
                        )
                    }

                    PersistLazyListPosition(
                        listState = verticalListState,
                        key = "cinematic_vertical",
                        minOffsetDeltaPx = 36,
                        onPositionChanged = onVerticalScrollChange
                    )

                    LazyColumn(
                        state = verticalListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    (event.key == Key.DirectionUp || event.key == Key.DirectionDown)
                                ) {
                                    skipVerticalScroll = false
                                }
                                false
                            }, // Keep restored focus still until the user navigates.
                        contentPadding = PaddingValues(top = 5.dp, bottom = 400.dp),
                        verticalArrangement = Arrangement.spacedBy((-12).dp)
                    ) {
                        if (historyItems.isNotEmpty()) {
                            item(key = "history_header", contentType = "row") {
                                // Reset scroll position when focus was redirected (cleared item removed)
                                val savedPosition = if (historyFocusRedirected) Pair(0, 0)
                                    else rowScrollPositions["history"] ?: Pair(0, 0)
                                val historyRowState = rememberLazyListState(
                                    initialFirstVisibleItemIndex = savedPosition.first,
                                    initialFirstVisibleItemScrollOffset = savedPosition.second
                                )

                                PersistLazyListPosition(
                                    listState = historyRowState,
                                    key = "cinematic_history",
                                    minOffsetDeltaPx = 36,
                                    onPositionChanged = { onScrollPositionChange("history", it) }
                                )

                                InfiniteLoopRow(
                                    startPadding = startPadding,
                                    isTopNav = isTopNav,
                                    rowIndex = -1,
                                    focusRowId = "history",
                                    title = continueWatchingTitle,
                                    items = historyItems,
                                    onMovieClick = onContinueClick,
                                    onMovieLongClick = onMovieLongClick,
                                    onViewMore = { onViewMore(continueWatchingTitle, historyItems, "") },
                                    onFocused = remember(wrappedOnFocusChange) {
                                        { item: MetaItem?, key: String ->
                                            updatePreviewItem(item)
                                            wrappedOnFocusChange(key)
                                        }
                                    },
                                    entryRequester = entryRequester,
                                    drawerRequester = drawerRequester,
                                    locallyFocusedItemId = if (effectiveLastFocusedKey?.startsWith("-1_") == true) effectiveLastFocusedKey else null,
                                    isGlobalFocusPresent = effectiveLastFocusedKey != null,
                                    isFirstRow = true,
                                    isInfiniteLoopEnabled = false,
                                    visibleItemCount = 15,
                                    isInfiniteScrollingEnabled = true,
                                    externalListState = historyRowState,
                                    repeatGate = dpadRepeatGate,
                                    isLandscapeCards = isLandscapeContinueWatching,
                                    enrichedItems = state.enrichedMeta,
                                    onItemShown = onPreviewItemVisible,
                                    onPrepareArtworkItem = onPrepareArtworkItem,
                                    onWatchedItemShown = onWatchedItemShown,
                                    rowHeight = if (isLandscapeContinueWatching) 140.dp else 210.dp,
                                    verticalEntryRequester = shelfFocusRoutes["history"]?.entry,
                                    upShelfRequester = shelfFocusRoutes["history"]?.up,
                                    downShelfRequester = shelfFocusRoutes["history"]?.down
                                )
                            }
                        }

                        itemsIndexed(
                            items = state.mixedRows,
                            key = { _, item -> 
                                when(item) {
                                    is HubGroupRow -> "hub_row_${item.id}"
                                    is CategoryRow -> "row_${item.id}"
                                    else -> "unknown_${item.id}"
                                }
                            },
                            contentType = { _, item -> 
                                when(item) {
                                    is HubGroupRow -> "hub_row"
                                    is CategoryRow -> "row"
                                    else -> "unknown"
                                }
                            }
                        ) { index, item ->
                            when (item) {
                                is HubGroupRow -> {

                                    // Create list state with saved scroll position for this hub row
                                    // Reset when focus was redirected from a removed history item to this first row
                                    val hubKey = "hub_${item.id}"
                                    val resetScroll = historyFocusRedirected && historyItems.isEmpty() && index == 0
                                    val savedHubPosition = if (resetScroll) null else rowScrollPositions[hubKey]
                                    val hubListState = rememberLazyListState(
                                        initialFirstVisibleItemIndex = savedHubPosition?.first ?: 0,
                                        initialFirstVisibleItemScrollOffset = savedHubPosition?.second ?: 0
                                    )

                                    PersistLazyListPosition(
                                        listState = hubListState,
                                        key = "cinematic_$hubKey",
                                        minOffsetDeltaPx = 36,
                                        onPositionChanged = { onScrollPositionChange(hubKey, it) }
                                    )

                                    HubRow(
                                        hubGroup = item,
                                        startPadding = startPadding,
                                        onHubClick = onHubClick,
                                        onFocused = { _, focusKey ->
                                            instantFocusItem = null
                                            wrappedOnFocusChange(focusKey)
                                        },
                                        entryRequester = entryRequester,
                                        drawerRequester = drawerRequester,
                                        locallyFocusedItemId = if (effectiveLastFocusedKey?.startsWith("hub_") == true) effectiveLastFocusedKey else null,
                                        isTopNav = isTopNav,
                                        rowIndex = index,
                                        externalListState = hubListState,
                                        repeatGate = dpadRepeatGate,
                                        isLastRow = index == state.mixedRows.lastIndex,
                                        isFirstRow = historyItems.isEmpty() && index == 0,
                                        isGlobalFocusPresent = effectiveLastFocusedKey != null
                                    )
                                }
                                is CategoryRow -> {
                                    // Create list state with saved scroll position for this row
                                    // Reset when focus was redirected from a removed history item to this first row
                                    val rowKey = item.id
                                    val resetScroll = historyFocusRedirected && historyItems.isEmpty() && index == 0
                                    val savedPosition = if (resetScroll) null else rowScrollPositions[rowKey]

                                    val initialIndex = savedPosition?.first ?: 0
                                    val initialOffset = savedPosition?.second ?: 0

                                    val rowListState = rememberLazyListState(
                                        initialFirstVisibleItemIndex = initialIndex,
                                        initialFirstVisibleItemScrollOffset = initialOffset
                                    )

                                    PersistLazyListPosition(
                                        listState = rowListState,
                                        key = "cinematic_$rowKey",
                                        minOffsetDeltaPx = 36,
                                        onPositionChanged = { onScrollPositionChange(rowKey, it) }
                                    )
                                    
                                    // Lazy pagination: load more items when scrolling near the end
                                    // Use snapshotFlow to avoid reading layoutInfo during composition
                                    val itemId = item.id
                                    val itemSize = item.items.size
                                    val actionItems = remember(item.items) {
                                        item.items.map(CatalogCardProjection::asActionItem)
                                    }
                                    LaunchedEffect(rowListState, itemId) {
                                        snapshotFlow {
                                            rowListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                                        }.collect { lastVisibleIndex ->
                                            if (itemSize > 0 && lastVisibleIndex >= itemSize - 10 && !itemId.startsWith("tmdb-")) {
                                                onLoadMore(itemId)
                                            }
                                        }
                                    }

                                    val focusRoute = shelfFocusRoutes[item.id]
                                    InfiniteLoopRow(
                                        startPadding = startPadding,
                                        isTopNav = isTopNav,
                                        rowIndex = index,
                                        focusRowId = item.id,
                                        title = item.title,
                                        items = actionItems,
                                        onMovieClick = onMovieClick,
                                        onMovieLongClick = onMovieLongClick,
                                        onViewMore = remember(item.title, item.items, item.id, onViewMore) {
                                            { onViewMore(item.title, actionItems, item.id) }
                                        },
                                        onFocused = remember(wrappedOnFocusChange) {
                                            { focusItem: MetaItem?, key: String ->
                                                updatePreviewItem(focusItem)
                                                wrappedOnFocusChange(key)
                                            }
                                        },
                                        entryRequester = entryRequester,
                                        drawerRequester = drawerRequester,
                                        locallyFocusedItemId = if (effectiveLastFocusedKey?.startsWith("${index}_") == true) effectiveLastFocusedKey else null,
                                        isGlobalFocusPresent = effectiveLastFocusedKey != null,
                                        isFirstRow = historyItems.isEmpty() && index == 0,
                                        isInfiniteLoopEnabled = item.isInfiniteLoopEnabled,
                                        visibleItemCount = item.visibleItemCount,
                                        isInfiniteScrollingEnabled = item.isInfiniteScrollingEnabled,
                                        externalListState = rowListState,
                                        repeatGate = dpadRepeatGate,
                                        onItemShown = onPreviewItemVisible,
                                        onPrepareArtworkItem = onPrepareArtworkItem,
                                        onWatchedItemShown = onWatchedItemShown,
                                        verticalEntryRequester = focusRoute?.entry,
                                        upShelfRequester = focusRoute?.up,
                                        downShelfRequester = focusRoute?.down
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun resolveCinematicPreviewItem(
    lastFocusedKey: String?,
    mixedRows: List<HomeRowItem>,
    historyItems: List<MetaItem>
): MetaItem? {
    if (lastFocusedKey.isNullOrEmpty()) return null
    if (lastFocusedKey.startsWith("hub_") || lastFocusedKey.startsWith("hero_")) return null

    val address = focusKeyWithoutRowId(lastFocusedKey)
    val firstSeparator = address.indexOf('_')
    if (firstSeparator <= 0) return null

    val rowToken = address.substring(0, firstSeparator)
    val itemToken = decodeFocusToken(address.substring(firstSeparator + 1).substringBeforeLast("_"))
    if (itemToken.isEmpty() || itemToken == "viewmore") return null

    if (rowToken == "-1") {
        return historyItems.firstOrNull { it.homeFocusIdentityKey() == itemToken }
            ?: historyItems.firstOrNull { it.id == itemToken }
    }

    val rowIndex = rowToken.toIntOrNull() ?: return null
    val row = mixedRows.getOrNull(rowIndex) as? CategoryRow ?: return null
    return row.items.firstOrNull { it.focusIdentityKey == itemToken }?.asActionItem()
        ?: row.items.firstOrNull { it.asActionItem().id == itemToken }?.asActionItem()
}

private fun buildContinueWatchingItems(
    history: List<WatchHistoryEntity>,
    seriesNextUp: List<com.saab.tv.data.model.SeriesNextUpEntity> = emptyList()
): List<MetaItem> {
    val result = mutableListOf<Pair<Long, MetaItem>>()
    val seriesIdsIncluded = mutableSetOf<String>()

    // 1. In-progress items (partially watched, not completed)
    val inProgress = history.filter { !it.watched }

    val seriesByCanonicalId = mutableMapOf<String, MutableList<WatchHistoryEntity>>()
    val movieById = mutableMapOf<String, WatchHistoryEntity>()

    inProgress.forEach { entry ->
        if (entry.type == "series") {
            val canonicalId = canonicalSeriesId(entry.id)
            seriesByCanonicalId.getOrPut(canonicalId) { mutableListOf() }.add(entry)
        } else {
            movieById.putIfAbsent(entry.id, entry)
        }
    }

    val chosenSeries = mutableMapOf<String, WatchHistoryEntity>()
    seriesByCanonicalId.forEach { (canonicalId, entries) ->
        val preferred = ContinueResumePolicy.latestEpisode(entries)
        if (preferred != null) {
            chosenSeries[canonicalId] = preferred
        }
    }

    inProgress.forEach { entry ->
        if (entry.type == "series") {
            val canonicalId = canonicalSeriesId(entry.id)
            val chosen = chosenSeries[canonicalId] ?: return@forEach
            if (chosen.id != entry.id) return@forEach
            seriesIdsIncluded.add(canonicalId)
            result.add(chosen.lastWatched to MetaItem(
                id = canonicalId,
                type = entry.type,
                name = entry.title,
                poster = entry.poster,
                background = chosen.background,
                logo = chosen.logo,
                progress = chosen.progress(),
                resumePlaybackId = chosen.id
            ))
        } else {
            val chosen = movieById[entry.id] ?: return@forEach
            if (chosen.id != entry.id) return@forEach
            result.add(entry.lastWatched to MetaItem(
                id = entry.id,
                type = entry.type,
                name = entry.title,
                poster = entry.poster,
                background = entry.background,
                logo = entry.logo,
                progress = entry.progress()
            ))
        }
    }

    // 2. Next-up entries: series where all in-progress episodes are watched,
    //    but there's a next episode available. Only include if the episode has aired.
    val today = java.time.LocalDate.now().toString() // "2026-04-06"
    for (nextUp in seriesNextUp) {
        if (nextUp.seriesId in seriesIdsIncluded) continue // already shown as in-progress

        // Check if the next episode has aired (null = assume aired)
        val released = nextUp.nextReleased
        if (released != null && released > today) continue

        // Complete with no next episode date = truly done, skip
        if (nextUp.isComplete && released == null) continue

        // +1 badge: show was complete (user was caught up) and episode has now aired
        val isReturning = nextUp.isComplete || nextUp.isNewEpisode

        result.add(nextUp.updatedAt to MetaItem(
            id = nextUp.seriesId,
            type = "series",
            name = nextUp.title,
            poster = nextUp.poster,
            hasNewEpisode = isReturning,
            resumePlaybackId = "${nextUp.seriesId}:${nextUp.nextSeason}:${nextUp.nextEpisode}"
        ))
    }

    // Sort all items by most recent activity
    return result.sortedByDescending { it.first }.map { it.second }
}

private fun canonicalSeriesId(playbackId: String): String {
    if (!isEpisodePlaybackId(playbackId)) return playbackId
    val parts = playbackId.split(":")
    return parts.dropLast(2).joinToString(":")
}

private fun isEpisodePlaybackId(playbackId: String): Boolean {
    val parts = playbackId.split(":")
    if (parts.size < 3) return false
    return parts[parts.lastIndex - 1].toIntOrNull() != null && parts.last().toIntOrNull() != null
}

private fun resolveLatestPreviewItem(
    current: MetaItem?,
    state: HomeViewModel.HomeState,
    historyItems: List<MetaItem>,
    index: HomePreviewMetadataIndex
): MetaItem? {
    val item = current ?: return null

    val key = item.canonicalPresentationKey()
    val enriched = state.enrichedMeta[key]
    val fromHistory = historyItems.firstOrNull { it.canonicalPresentationKey() == key }
    val fromHero = state.heroRow?.items?.asSequence()
        ?.map(CatalogCardProjection::asActionItem)
        ?.firstOrNull { it.canonicalPresentationKey() == key }
    return HomePreviewMetadataPolicy.select(
        current = item,
        enriched = enriched,
        hero = fromHero,
        row = index.rowsByKey[key],
        history = fromHistory
    )
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SimpleLayout(
    startPadding: androidx.compose.ui.unit.Dp,
    isTopNav: Boolean,
    state: HomeViewModel.HomeState,
    heroItems: List<MetaItem> = emptyList(),
    heroAutoScrollSeconds: Int = 0,
    onMovieClick: (MetaItem) -> Unit,
    onContinueClick: (MetaItem) -> Unit,
    onMovieLongClick: (MetaItem, Boolean, Rect) -> Unit,
    onViewMore: (String, List<MetaItem>, String) -> Unit,
    onHubClick: (com.saab.tv.domain.HubItem) -> Unit,
    onLoadMore: (String) -> Unit,
    entryRequester: FocusRequester,
    drawerRequester: FocusRequester,
    lastFocusedKey: String?,
    rowScrollPositions: Map<String, Pair<Int, Int>>,
    verticalScrollPosition: Pair<Int, Int>,
    historyScrollAdjustment: Int,
    onFocusChange: (String) -> Unit,
    onScrollPositionChange: (String, Pair<Int, Int>) -> Unit,
    onVerticalScrollChange: (Pair<Int, Int>) -> Unit,
    onHeroItemVisible: (MetaItem) -> Unit,
    onPrepareArtworkItem: (MetaItem) -> Unit = {},
    onWatchedItemShown: (MetaItem) -> Unit = {},
    topActionRequester: FocusRequester? = null,
    onRefreshOtt: (() -> Unit)? = null,
    isLandscapeContinueWatching: Boolean = false
) {
    val continueWatchingTitle = stringResource(R.string.library_continue_watching)
    var hasRequestedFocus by remember { mutableStateOf(false) }
    // Stable key: only rebuild when items/order/watched-state change, not when images update
    val historyKey = remember(state.history) {
        state.history.map { "${it.id}:${it.watched}:${it.lastWatched}:${it.poster != null}" }
    }
    val nextUpKey = remember(state.seriesNextUp) {
        state.seriesNextUp.map { "${it.seriesId}:${it.isComplete}:${it.nextSeason}:${it.nextEpisode}:${it.updatedAt}:${it.poster != null}" }
    }
    val historyItems = remember(historyKey, nextUpKey) {
        buildContinueWatchingItems(state.history, state.seriesNextUp)
    }
    val shelfFocusKeys = remember(historyItems.isNotEmpty(), state.mixedRows) {
        buildList {
            if (historyItems.isNotEmpty()) add("history")
            addAll(state.mixedRows.map { it.id })
        }
    }
    val shelfFocusRoutes = rememberShelfFocusRoutes(shelfFocusKeys)
    val focusRestorationIndex = remember(state.mixedRows) {
        homeFocusRestorationIndex(state.mixedRows)
    }

    // Proactive metadata fetch for continue watching cards (posters + landscape)
    LaunchedEffect(historyItems) {
        historyItems.take(3).forEach { item -> onHeroItemVisible(item) }
    }

    // Redirect stale continue-watching focus key after progress was cleared
    val effectiveLastFocusedKey = remember(lastFocusedKey, historyItems, focusRestorationIndex) {
        resolveEffectiveFocusKey(lastFocusedKey, historyItems, state.mixedRows, focusRestorationIndex)
    }
    val historyFocusRedirected = effectiveLastFocusedKey != lastFocusedKey

    val dpadRepeatGate = remember {
        DpadRepeatGate(
            horizontalRepeatIntervalMs = DPAD_REPEAT_INTERVAL_HORIZONTAL_MS,
            verticalRepeatIntervalMs = DPAD_REPEAT_INTERVAL_VERTICAL_MS
        )
    }
    // Focus requester for the first row's pivot item (used by hero carousel DOWN key)
    val firstRowPivotRequester = remember { FocusRequester() }

    LaunchedEffect(state.rows, historyItems) {
        if (!hasRequestedFocus && (state.rows.isNotEmpty() || historyItems.isNotEmpty())) {
            delay(100)
            val requester = when {
                effectiveLastFocusedKey != null -> entryRequester
                onRefreshOtt != null && topActionRequester != null -> topActionRequester
                else -> entryRequester
            }
            runCatching { requester.requestFocus() }
            hasRequestedFocus = true
        }
    }

    // Smooth vertical scrolling pivot for Simple layout
    val density = LocalDensity.current

    // Skip vertical scroll on restoration (when we have a saved focus key)
    val isVerticalRestoration = effectiveLastFocusedKey != null
    var skipVerticalScroll by remember(isVerticalRestoration) { mutableStateOf(isVerticalRestoration) }

    val verticalPivotPx = remember(density, isTopNav) { with(density) { (if (isTopNav) 91.dp else 71.dp).toPx() } }

    val verticalPivot = remember(verticalPivotPx) {
        FocusPivotSpec(
            customOffset = verticalPivotPx,
            skipScrollProvider = { skipVerticalScroll }
        )
    }

    // Wrap onFocusChange to track timing for vertical dynamic stiffness
    val wrappedOnFocusChange: (String) -> Unit = remember(onFocusChange) {
        { key ->
            onFocusChange(key)
        }
    }
    
    CompositionLocalProvider(LocalBringIntoViewSpec provides verticalPivot) {
        // Use persisted vertical scroll position for instant restoration.
        // When the continue watching row first appears at index 0, all saved
        // indices are off by 1 — adjust the initial index to compensate.
        val verticalListState = rememberLazyListState(
            initialFirstVisibleItemIndex = verticalScrollPosition.first + historyScrollAdjustment,
            initialFirstVisibleItemScrollOffset = verticalScrollPosition.second
        )

        PersistLazyListPosition(
            listState = verticalListState,
            key = "simple_vertical",
            minOffsetDeltaPx = 36,
            onPositionChanged = onVerticalScrollChange
        )

        LazyColumn(
            state = verticalListState,
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.DirectionUp || event.key == Key.DirectionDown)
                    ) {
                        skipVerticalScroll = false
                    }
                    false
                }, // Focus managed by items; user navigation resumes pivot motion.
            contentPadding = PaddingValues(top = if (heroItems.isNotEmpty()) 0.dp else if (isTopNav) 60.dp else 40.dp, bottom = 0.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)
        ) {
            if (onRefreshOtt != null && topActionRequester != null) {
                item(key = "ott_refresh_action", contentType = "action") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp)
                            .padding(start = startPadding, end = 48.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        OttRefreshButton(
                            onClick = onRefreshOtt,
                            modifier = Modifier.focusRequester(topActionRequester)
                        )
                    }
                }
            }

            if (heroItems.isNotEmpty()) {
                item(key = "hero_carousel") {
                    HeroCarousel(
                        items = heroItems,
                        autoScrollSeconds = heroAutoScrollSeconds,
                        onItemClick = onMovieClick,
                        startPadding = startPadding,
                        onFocusChange = { wrappedOnFocusChange("hero_$it") },
                        onCurrentItemVisible = onHeroItemVisible,
                        entryRequester = if (effectiveLastFocusedKey == null || effectiveLastFocusedKey.startsWith("hero_")) entryRequester else null,
                        drawerRequester = drawerRequester,
                        isFirstItem = true,
                        isTopNav = isTopNav,
                        repeatGate = dpadRepeatGate,
                        onNavigateDown = { firstRowPivotRequester.requestFocus() },
                        restoreItemId = if (effectiveLastFocusedKey?.startsWith("hero_") == true) effectiveLastFocusedKey.removePrefix("hero_") else null,
                        tmdbEnabled = state.tmdbEnabled,
                        tmdbEnrichedIds = state.tmdbEnrichedIds
                    )
                }
            }

            if (historyItems.isNotEmpty()) {
                item(key = "simple_history_header", contentType = "row") {
                    // Reset scroll position when focus was redirected (cleared item removed)
                    val savedPosition = if (historyFocusRedirected) Pair(0, 0)
                        else rowScrollPositions["history"] ?: Pair(0, 0)
                    val historyRowState = rememberLazyListState(
                        initialFirstVisibleItemIndex = savedPosition.first,
                        initialFirstVisibleItemScrollOffset = savedPosition.second
                    )

                    PersistLazyListPosition(
                        listState = historyRowState,
                        key = "simple_history",
                        minOffsetDeltaPx = 36,
                        onPositionChanged = { onScrollPositionChange("history", it) }
                    )

                    InfiniteLoopRow(
                        startPadding = startPadding,
                        isTopNav = isTopNav,
                        rowIndex = -1,
                        focusRowId = "history",
                        title = continueWatchingTitle,
                        items = historyItems,
                        onMovieClick = onContinueClick,
                        onMovieLongClick = onMovieLongClick,
                        onViewMore = { onViewMore(continueWatchingTitle, historyItems, "") },
                        onFocused = remember(wrappedOnFocusChange) {
                            { _: MetaItem?, key: String -> wrappedOnFocusChange(key) }
                        },
                        entryRequester = entryRequester,
                        drawerRequester = drawerRequester,
                        locallyFocusedItemId = if (effectiveLastFocusedKey?.startsWith("-1_") == true) effectiveLastFocusedKey else null,
                        isGlobalFocusPresent = effectiveLastFocusedKey != null,
                        isFirstRow = heroItems.isEmpty() && onRefreshOtt == null,
                        isInfiniteLoopEnabled = false,
                        visibleItemCount = 15,
                        isInfiniteScrollingEnabled = true,
                        externalListState = historyRowState,
                        repeatGate = dpadRepeatGate,
                        pivotFocusRequester = if (heroItems.isNotEmpty()) firstRowPivotRequester else null,
                        isLandscapeCards = isLandscapeContinueWatching,
                        enrichedItems = state.enrichedMeta,
                        onItemShown = onHeroItemVisible,
                        onPrepareArtworkItem = onPrepareArtworkItem,
                        onWatchedItemShown = onWatchedItemShown,
                        rowHeight = if (isLandscapeContinueWatching) 140.dp else 210.dp,
                        verticalEntryRequester = shelfFocusRoutes["history"]?.entry,
                        upShelfRequester = shelfFocusRoutes["history"]?.up,
                        downShelfRequester = shelfFocusRoutes["history"]?.down
                    )
                }
            }

            itemsIndexed(
                items = state.mixedRows,
                key = { _, item -> 
                    when(item) {
                        is HubGroupRow -> "simple_hub_${item.id}"
                        is CategoryRow -> "simple_row_${item.id}"
                        else -> "simple_unknown_${item.id}"
                    }
                },
                contentType = { _, item -> 
                    when(item) {
                        is HubGroupRow -> "hub_row"
                        is CategoryRow -> "row"
                        else -> "unknown"
                    }
                }
            ) { rowIndex, item ->
                when (item) {
                    is HubGroupRow -> {

                        // Create list state with saved scroll position for this hub row
                        // Reset when focus was redirected from a removed history item to this first row
                        val hubKey = "hub_${item.id}"
                        val resetScroll = historyFocusRedirected && historyItems.isEmpty() && rowIndex == 0
                        val savedHubPosition = if (resetScroll) null else rowScrollPositions[hubKey]
                        val hubListState = rememberLazyListState(
                            initialFirstVisibleItemIndex = savedHubPosition?.first ?: 0,
                            initialFirstVisibleItemScrollOffset = savedHubPosition?.second ?: 0
                        )

                        PersistLazyListPosition(
                            listState = hubListState,
                            key = "simple_$hubKey",
                            minOffsetDeltaPx = 36,
                            onPositionChanged = { onScrollPositionChange(hubKey, it) }
                        )

                        HubRow(
                            hubGroup = item,
                            startPadding = startPadding,
                            onHubClick = onHubClick,
                            onFocused = { _, focusKey ->
                                wrappedOnFocusChange(focusKey)
                            },
                            entryRequester = entryRequester,
                            drawerRequester = drawerRequester,
                            locallyFocusedItemId = if (effectiveLastFocusedKey?.startsWith("hub_") == true) effectiveLastFocusedKey else null,
                            isTopNav = isTopNav,
                            rowIndex = rowIndex,
                            isFirstRow = heroItems.isEmpty() && historyItems.isEmpty() && rowIndex == 0 && onRefreshOtt == null, // Only steal focus if no hero/history/action row
                            externalListState = hubListState,
                            repeatGate = dpadRepeatGate,
                            isLastRow = rowIndex == state.mixedRows.lastIndex,
                            pivotFocusRequester = if (heroItems.isNotEmpty() && historyItems.isEmpty() && rowIndex == 0) firstRowPivotRequester else null,
                            isGlobalFocusPresent = effectiveLastFocusedKey != null
                        )
                    }
                    is CategoryRow -> {
                        // Create list state with saved scroll position for this row
                        // Reset when focus was redirected from a removed history item to this first row
                        val rowKey = item.id
                        val resetScroll = historyFocusRedirected && historyItems.isEmpty() && rowIndex == 0
                        val savedPosition = if (resetScroll) null else rowScrollPositions[rowKey]

                        val initialIndex = savedPosition?.first ?: 0
                        val initialOffset = savedPosition?.second ?: 0

                        val rowListState = rememberLazyListState(
                            initialFirstVisibleItemIndex = initialIndex,
                            initialFirstVisibleItemScrollOffset = initialOffset
                        )

                        PersistLazyListPosition(
                            listState = rowListState,
                            key = "simple_$rowKey",
                            minOffsetDeltaPx = 36,
                            onPositionChanged = { onScrollPositionChange(rowKey, it) }
                        )
                        
                        // Lazy pagination: load more items when scrolling near the end
                        // Use snapshotFlow to avoid reading layoutInfo during composition
                        val itemId = item.id
                        val itemSize = item.items.size
                        val actionItems = remember(item.items) {
                            item.items.map(CatalogCardProjection::asActionItem)
                        }
                        LaunchedEffect(rowListState, itemId) {
                            snapshotFlow {
                                rowListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                            }.collect { lastVisibleIndex ->
                                if (itemSize > 0 && lastVisibleIndex >= itemSize - 10) {
                                    onLoadMore(itemId)
                                }
                            }
                        }
                        
                        // Custom row height: 193dp for last row in Top Nav, 210dp standard
                        val rowHeight = 210.dp

                        // STABILIZE LAMBDAS:
                        // These specific lambda instances must remain referentially equal
                        // across recompositions to allow InfiniteLoopRow to skip logic.
                        val onRowViewMore = remember(item.items, item.title, item.id, onViewMore) {
                            { onViewMore(item.title, actionItems, item.id) }
                        }
                        
                        val onRowFocused = remember(wrappedOnFocusChange) {
                            { _: MetaItem?, key: String -> wrappedOnFocusChange(key) }
                        }

                        val focusRoute = shelfFocusRoutes[item.id]
                        InfiniteLoopRow(
                            startPadding = startPadding,
                            isTopNav = isTopNav,
                            rowIndex = rowIndex,
                            focusRowId = item.id,
                            title = item.title,
                            items = actionItems,
                            onMovieClick = onMovieClick,
                            onMovieLongClick = onMovieLongClick,
                            onViewMore = onRowViewMore,
                            onFocused = onRowFocused,
                            entryRequester = entryRequester,
                            drawerRequester = drawerRequester,
                            locallyFocusedItemId = if (effectiveLastFocusedKey?.startsWith("${rowIndex}_") == true) effectiveLastFocusedKey else null,
                            isGlobalFocusPresent = effectiveLastFocusedKey != null,
                            isFirstRow = heroItems.isEmpty() && historyItems.isEmpty() && rowIndex == 0 && onRefreshOtt == null,
                            isInfiniteLoopEnabled = item.isInfiniteLoopEnabled,
                            visibleItemCount = item.visibleItemCount,
                            isInfiniteScrollingEnabled = item.isInfiniteScrollingEnabled,
                            externalListState = rowListState,
                            rowHeight = rowHeight,
                            repeatGate = dpadRepeatGate,
                            onItemShown = onHeroItemVisible,
                            onPrepareArtworkItem = onPrepareArtworkItem,
                            onWatchedItemShown = onWatchedItemShown,
                            pivotFocusRequester = if (heroItems.isNotEmpty() && historyItems.isEmpty() && rowIndex == 0) firstRowPivotRequester else null,
                            verticalEntryRequester = focusRoute?.entry,
                            upShelfRequester = focusRoute?.up,
                            downShelfRequester = focusRoute?.down
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PersistLazyListPosition(
    listState: androidx.compose.foundation.lazy.LazyListState,
    key: String,
    minOffsetDeltaPx: Int = 32,
    onPositionChanged: (Pair<Int, Int>) -> Unit
) {
    var lastIndex by remember(key, listState) { mutableIntStateOf(Int.MIN_VALUE) }
    var lastOffset by remember(key, listState) { mutableIntStateOf(Int.MIN_VALUE) }

    LaunchedEffect(key, listState) {
        snapshotFlow {
            Pair(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }.collect { (index, offset) ->
            val shouldEmit =
                lastIndex == Int.MIN_VALUE ||
                    index != lastIndex ||
                    kotlin.math.abs(offset - lastOffset) >= minOffsetDeltaPx

            if (shouldEmit) {
                lastIndex = index
                lastOffset = offset
                onPositionChanged(Pair(index, offset))
            }
        }
    }

    DisposableEffect(key, listState) {
        onDispose {
            val finalIndex = listState.firstVisibleItemIndex
            val finalOffset = listState.firstVisibleItemScrollOffset
            if (finalIndex != lastIndex || finalOffset != lastOffset) {
                onPositionChanged(Pair(finalIndex, finalOffset))
            }
        }
    }
}

@Composable
fun CinematicBackground(item: MetaItem?) {
    // Use the theme's actual background color
    val backgroundColor = androidx.compose.material3.MaterialTheme.colorScheme.background
    val context = LocalContext.current
    var readyImage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(item?.background, item?.poster) {
        val next = item ?: return@LaunchedEffect
        val url = next.background ?: next.poster
        // Let rapid navigation settle before decoding another large hero bitmap.
        delay(75)
        if (!url.isNullOrBlank()) {
            try {
                context.imageLoader.execute(
                    ImageRequest.Builder(context).data(url).size(1280, 720)
                        .memoryCacheKey("backdrop:$url").diskCacheKey(url)
                        .scale(coil.size.Scale.FILL).build()
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep navigation responsive even when backdrop artwork is unavailable.
            }
        }
        readyImage = url
    }
    
    // Only fade LEFT and BOTTOM edges - top/right are at screen edge
    val leftFade = Brush.horizontalGradient(
        colorStops = arrayOf(
            0.0f to backgroundColor,
            0.1f to backgroundColor.copy(0.95f),
            0.2f to backgroundColor.copy(0.85f),
            0.3f to backgroundColor.copy(0.72f),
            0.4f to backgroundColor.copy(0.55f),
            0.55f to backgroundColor.copy(0.35f),
            0.7f to backgroundColor.copy(0.18f),
            0.85f to backgroundColor.copy(0.07f),
            1.0f to Color.Transparent
        ),
        startX = 0f,
        endX = 500f
    )
    
    // Bottom fade: blend into the rows area
    val bottomFade = Brush.verticalGradient(
        colorStops = arrayOf(
            0.0f to Color.Transparent,
            0.2f to backgroundColor.copy(0.05f),
            0.35f to backgroundColor.copy(0.15f),
            0.45f to backgroundColor.copy(0.25f),
            0.55f to backgroundColor.copy(0.38f),
            0.65f to backgroundColor.copy(0.52f),
            0.75f to backgroundColor.copy(0.68f),
            0.85f to backgroundColor.copy(0.82f),
            0.92f to backgroundColor.copy(0.92f),
            1.0f to backgroundColor
        )
    )

    Box(modifier = Modifier.fillMaxSize().zIndex(0f)) {
        Box(modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(0.65f).fillMaxHeight(0.65f)) {
            AnimatedContent(
                targetState = readyImage,
                transitionSpec = {
                    (fadeIn(tween(SaabMotion.duration(240))) + slideInHorizontally(tween(SaabMotion.duration(240)), initialOffsetX = { it / 12 }))
                        .togetherWith(fadeOut(tween(SaabMotion.duration(240))) + androidx.compose.animation.slideOutHorizontally(tween(SaabMotion.duration(240)), targetOffsetX = { -it / 12 }))
                },
                label = "HeroBg"
            ) { image ->
                if (image != null) {
                    val imageRequest = remember(image) {
                        ImageRequest.Builder(context)
                            .data(image)
                            .crossfade(false)
                            .size(1280, 720)
                            .memoryCacheKey("backdrop:$image")
                            .diskCacheKey(image)
                            .scale(coil.size.Scale.FILL)
                            .build()
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = imageRequest,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        // Left and bottom edge fades only
                        Spacer(
                            modifier = Modifier
                                .fillMaxSize()
                                .drawWithCache {
                                    onDrawWithContent {
                                        drawRect(brush = leftFade)
                                        drawRect(brush = bottomFade)
                                    }
                                }
                        )
                        com.saab.tv.ui.components.NoiseOverlay()
                    }
                }
            }
        }
    }
}


@Composable
fun Badge(text: String, color: Color, textColor: Color) {
    Text(text = text, color = textColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.background(color, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
}

@Composable
private fun HomeMetaStrip(item: MetaItem) {
    val typeLabel = com.saab.tv.ui.components.localizedTitleType(item.type)
    val genreLabel = item.genres
        ?.firstOrNull()
        ?.replaceFirstChar { it.uppercase() }
        ?: stringResource(R.string.sort_filter_unknown)
    val yearLabel = extractHomePrimaryYear(item.releaseInfo)
    val valueColor = Color.White.copy(alpha = 0.92f)
    val textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text = typeLabel, style = textStyle, color = valueColor)
        HomeMetaDot()
        Text(text = genreLabel, style = textStyle, color = valueColor)
        HomeMetaDot()
        Text(text = yearLabel, style = textStyle, color = valueColor)

        item.runtime?.filter { it.isDigit() }?.toIntOrNull()?.let { mins ->
            HomeMetaDot()
            val display = com.saab.tv.ui.components.localizedRuntimeLabel(mins)
            Text(text = display, style = textStyle, color = valueColor)
        }

        item.imdbRating?.takeIf { it.isNotBlank() }?.let { rating ->
            HomeMetaDot()
            HomeImdbBadge()
            Text(text = rating, style = textStyle, color = valueColor)
        }
    }
}

@Composable
private fun HomeMetaDot() {
    Text(
        text = ".",
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        color = Color.White.copy(alpha = 0.55f)
    )
}

@Composable
private fun HomeImdbBadge() {
    Image(
        painter = painterResource(id = R.drawable.imdb_logo),
        contentDescription = "IMDb",
        modifier = Modifier.height(16.dp)
    )
}

private fun extractHomePrimaryYear(releaseInfo: String?): String {
    if (releaseInfo.isNullOrBlank()) return "----"
    return Regex("\\d{4}").find(releaseInfo)?.value ?: releaseInfo.take(4)
}
