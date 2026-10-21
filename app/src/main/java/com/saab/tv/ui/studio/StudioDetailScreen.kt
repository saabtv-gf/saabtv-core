package com.saab.tv.ui.studio

import com.saab.tv.ui.trailer.titleTrailerFocus

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import com.saab.tv.ui.components.cardFocusSound
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.saab.tv.R
import com.saab.tv.data.tmdb.TmdbDiscoverRail
import com.saab.tv.data.tmdb.TmdbEntityDetail
import com.saab.tv.domain.schema.TitleCardProjection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.saab.tv.ui.home.DpadRepeatGate
import com.saab.tv.ui.components.FocusPivotSpec
import com.saab.tv.ui.components.ArtworkPrefetchDispatcher
import com.saab.tv.ui.components.ArtworkPrefetchEffect
import com.saab.tv.ui.components.LocalTitleCardShape
import com.saab.tv.domain.schema.canonicalPresentationKey
import com.saab.tv.domain.schema.toCanonicalProjection
import com.saab.tv.ui.utils.requestFocusOrFallback
import com.saab.tv.ui.utils.requestFocusSafely
import com.saab.tv.ui.navigation.LocalNavigationFocusHandoff
import com.saab.tv.ui.navigation.handoffNavigationFocus
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun StudioDetailScreen(
    entityId: Int,
    entityKind: String,
    entityName: String,
    sourceType: String = "movie",
    onBackPress: () -> Unit = {},
    onNavigateToDetails: (type: String, id: String) -> Unit = { _, _ -> },
    currentProfile: com.saab.tv.data.model.ProfileEntity? = null,
    viewModel: StudioDetailViewModel = hiltViewModel(),
    previewViewModel: com.saab.tv.ui.home.HomeViewModel = hiltViewModel()
) {
    val initialFocusRequester = remember { FocusRequester() }
    val quickActionScope = androidx.compose.runtime.rememberCoroutineScope()
    var quickActionTarget by remember { mutableStateOf<Pair<com.saab.tv.data.model.stremio.MetaItem, Rect>?>(null) }
    var quickActionReturnFocus by remember { mutableStateOf<FocusRequester?>(null) }
    var quickActionProfileId by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(previewViewModel) {
        quickActionProfileId = previewViewModel.activeProfileId() ?: 0
    }

    com.saab.tv.ui.trailer.TitleTrailerHost(
        onOpen = { onNavigateToDetails(it.type, it.id) },
        model = previewViewModel
    ) {

    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val enrichedItems by remember(previewViewModel) {
        previewViewModel.state.map { it.enrichedMeta }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = emptyMap())
    val bg = MaterialTheme.colorScheme.background
    val accentColor = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onBackground

    androidx.compose.runtime.LaunchedEffect(previewViewModel, currentProfile) {
        previewViewModel.configureTmdbProfile(currentProfile)
    }

    com.saab.tv.ui.navigation.AppBackHandler(priority = com.saab.tv.ui.navigation.AppBackPriority.Route) { onBackPress() }

    Box(modifier = Modifier.fillMaxSize().background(bg)) {
        CompositionLocalProvider(
            com.saab.tv.ui.components.LocalPosterFocusReturn provides { quickActionReturnFocus = it }
        ) {
        when (val state = uiState) {
            is StudioDetailState.Loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = accentColor
                )
            }
            is StudioDetailState.Error -> {
                Text(
                    text = state.message,
                    color = textColor.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            is StudioDetailState.Success -> {
                val restoreFocusRequester = remember { FocusRequester() }
                var restoreRowKey by rememberSaveable { mutableStateOf<String?>(null) }
                var restoreIndex by rememberSaveable { mutableStateOf(-1) }

                androidx.compose.runtime.LaunchedEffect(Unit) {
                    if (restoreRowKey != null && restoreIndex >= 0) {
                        restoreFocusRequester.requestFocusOrFallback(initialFocusRequester)
                        restoreRowKey = null
                        restoreIndex = -1
                    } else {
                        initialFocusRequester.requestFocusSafely()
                    }
                }

                StudioContent(
                    entity = state.entity,
                    rails = state.rails,
                    bg = bg,
                    accentColor = accentColor,
                    textColor = textColor,
                    onNavigateToDetails = { type, id, rowKey, index ->
                        restoreRowKey = rowKey
                        restoreIndex = index
                        onNavigateToDetails(type, id)
                    },
                    onLoadMore = { mediaType, railType ->
                        viewModel.loadMoreRail(mediaType, railType)
                    },
                    restoreRowKey = restoreRowKey,
                    restoreIndex = restoreIndex,
                    restoreFocusRequester = restoreFocusRequester,
                    initialFocusRequester = initialFocusRequester,
                    enrichedItems = enrichedItems,
                    previewViewModel = previewViewModel,
                    onQuickAction = { item, bounds -> quickActionTarget = item to bounds }
                )
            }
        }
        }

        quickActionTarget?.let { (item, bounds) ->
            com.saab.tv.ui.home.CatalogQuickActionsPopup(
                item = item,
                bounds = bounds,
                profileId = quickActionProfileId,
                viewModel = previewViewModel,
                onTrailerClick = { _, _ -> },
                onDismiss = {
                    quickActionTarget = null
                    quickActionScope.launch {
                        androidx.compose.runtime.withFrameNanos { }
                        if (quickActionReturnFocus?.requestFocusSafely() != true) {
                            // A watched action may remove this card; return to the first live target.
                            initialFocusRequester.requestFocusSafely()
                        }
                    }
                }
            )
        }
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StudioContent(
    entity: TmdbEntityDetail,
    rails: List<TmdbDiscoverRail>,
    bg: Color,
    accentColor: Color,
    textColor: Color,
    onNavigateToDetails: (type: String, id: String, rowKey: String, index: Int) -> Unit,
    onLoadMore: (mediaType: String, railType: String) -> Unit,
    restoreRowKey: String?,
    restoreIndex: Int,
    restoreFocusRequester: FocusRequester,
    initialFocusRequester: FocusRequester,
    enrichedItems: Map<String, com.saab.tv.data.model.stremio.MetaItem>,
    previewViewModel: com.saab.tv.ui.home.HomeViewModel,
    onQuickAction: (com.saab.tv.data.model.stremio.MetaItem, Rect) -> Unit
) {
    val density = LocalDensity.current
    val landscapeCards = LocalTitleCardShape.current == "landscape"
    val verticalPivotPx = remember(density) { with(density) { 45.dp.toPx() } }
    val verticalPivot = remember(verticalPivotPx) {
        FocusPivotSpec(
            customOffset = verticalPivotPx,
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Fixed hero section (40%)
        Box(
            modifier = Modifier
                .weight(0.40f)
                .fillMaxWidth()
                .padding(start = 48.dp, end = 48.dp, top = 48.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            HeroSection(entity, accentColor, textColor)
        }

        // Scrollable rails section (60%)
        Box(modifier = Modifier.weight(0.60f)) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides verticalPivot) {
                LazyColumn(
                    state = rememberLazyListState(),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 5.dp, bottom = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    rails.forEachIndexed { railIndex, rail ->
                        val rowKey = "${rail.mediaType}_${rail.railType}"
                        item(key = rowKey) {
                            DiscoverRailSection(
                                rail = rail,
                                accentColor = accentColor,
                                textColor = textColor,
                                onItemClick = { type, id, index ->
                                    onNavigateToDetails(type, id, rowKey, index)
                                },
                                onLoadMore = { onLoadMore(rail.mediaType, rail.railType) },
                                restoreIndex = if (restoreRowKey == rowKey) restoreIndex else -1,
                                restoreFocusRequester = if (restoreRowKey == rowKey) restoreFocusRequester else null,
                                initialFocusRequester = if (railIndex == 0) initialFocusRequester else null,
                                enrichedItems = enrichedItems,
                                onPrepareItem = { if (landscapeCards) previewViewModel.ensureTmdbEnrichment(it) },
                                onQuickAction = onQuickAction
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroSection(entity: TmdbEntityDetail, accentColor: Color, textColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Logo or fallback name
        if (entity.logo != null) {
            Box(
                modifier = Modifier
                    .width(200.dp)
                    .height(100.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                AsyncImage(
                    model = entity.logo,
                    contentDescription = entity.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            Text(
                text = entity.name,
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = textColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Info column
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val kindLabel = stringResource(
                if (entity.kind == "network") R.string.studio_entity_network
                else R.string.studio_entity_production_company
            )
            Text(
                text = kindLabel,
                style = MaterialTheme.typography.bodySmall,
                color = accentColor
            )

            val metaParts = listOfNotNull(
                entity.originCountry,
                entity.headquarters
            )
            if (metaParts.isNotEmpty()) {
                Text(
                    text = metaParts.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor.copy(alpha = 0.6f)
                )
            }

            entity.description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor.copy(alpha = 0.7f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DiscoverRailSection(
    rail: TmdbDiscoverRail,
    accentColor: Color,
    textColor: Color,
    onItemClick: (type: String, id: String, index: Int) -> Unit,
    onLoadMore: () -> Unit,
    restoreIndex: Int = -1,
    restoreFocusRequester: FocusRequester? = null,
    initialFocusRequester: FocusRequester? = null,
    artworkPrefetchDispatcher: ArtworkPrefetchDispatcher? = null,
    enrichedItems: Map<String, com.saab.tv.data.model.stremio.MetaItem> = emptyMap(),
    onPrepareItem: (com.saab.tv.data.model.stremio.MetaItem) -> Unit = {},
    onQuickAction: (com.saab.tv.data.model.stremio.MetaItem, Rect) -> Unit = { _, _ -> }
) {
    val authorizeNavigationHandoff = LocalNavigationFocusHandoff.current
    val repeatGate = remember { DpadRepeatGate(horizontalRepeatIntervalMs = 150L) }
    val startPad = 48.dp
    val rowState = rememberLazyListState()
    val artworkItems = remember(rail.items) { rail.items.map { it.asMetaItem() } }
    val landscapeCards = LocalTitleCardShape.current == "landscape"
    val focusFrame = com.saab.tv.ui.components.focusFrameSize(if (landscapeCards) 190.dp else 120.dp, landscapeCards)
    val rowHasCardFocus = remember { mutableStateOf(false) }
    var focusedIndex by remember(rail) { mutableIntStateOf(restoreIndex.coerceAtLeast(0)) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val artworkDispatcher = remember(context, artworkPrefetchDispatcher) {
        artworkPrefetchDispatcher ?: ArtworkPrefetchDispatcher.forCoil(context)
    }
    var focusedArtworkKey by remember { mutableStateOf<String?>(null) }
    ArtworkPrefetchEffect(
        items = artworkItems,
        enrichedItems = enrichedItems,
        listState = rowState,
        focusedItemKey = focusedArtworkKey,
        landscapeCards = landscapeCards,
        dispatcher = artworkDispatcher,
        posterSize = if (landscapeCards) null else androidx.compose.ui.unit.DpSize(120.dp, 180.dp),
        onPrepareItem = onPrepareItem
    )

    // Rail title
    val mediaLabel = stringResource(if (rail.mediaType == "tv") R.string.nav_series else R.string.nav_movies)
    val typeLabel = stringResource(
        when (rail.railType) {
            "top_rated" -> R.string.studio_rail_top_rated
            "recent" -> R.string.studio_rail_recent
            else -> R.string.studio_rail_popular
        }
    )

    // Observe scrolling outside composition; reading layoutInfo here recomposed
    // the entire rail on every scroll/layout update.
    androidx.compose.runtime.LaunchedEffect(
        rail.mediaType, rail.railType, rail.items.size, rail.currentPage, rail.hasMore, rowState
    ) {
        if (!rail.hasMore || rail.currentPage >= 3) return@LaunchedEffect
        var requestedForPage = false
        snapshotFlow { rowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (!requestedForPage && lastVisible >= 0 && lastVisible >= rail.items.size - 4) {
                    requestedForPage = true
                    onLoadMore()
                }
            }
    }

    Column(
        modifier = Modifier.fillMaxWidth().onFocusChanged {
            if (!it.hasFocus) rowHasCardFocus.value = false
        }
    ) {
        Text(
            text = "$mediaLabel · $typeLabel",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = textColor.copy(alpha = 0.9f),
            modifier = Modifier.padding(start = 48.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        com.saab.tv.ui.components.FocusShelf(
            state = rowState,
            focused = rowHasCardFocus,
            width = focusFrame.width,
            height = focusFrame.height,
            startPadding = startPad,
            focusedItemIndex = focusedIndex,
            tag = "studio-discover-focus-indicator",
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
                    itemsIndexed(rail.items, key = { _, it -> it.presentationKey }) { index, item ->
                        Box(modifier = Modifier.onPreviewKeyEvent {
                            if (repeatGate.shouldConsume(it)) return@onPreviewKeyEvent true
                            if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && index == 0) {
                                handoffNavigationFocus(it, authorizeNavigationHandoff)
                            } else false
                        }) {
                            PosterCard(
                                item = item,
                                accentColor = accentColor,
                                enrichedItems = enrichedItems,
                                onFocused = {
                                    focusedArtworkKey = artworkItems[index].canonicalPresentationKey()
                                    focusedIndex = index
                                    rowHasCardFocus.value = true
                                },
                                showFocusOutline = false,
                                onLongClick = { bounds -> onQuickAction(item.asMetaItem(), bounds) },
                                modifier = when {
                                    restoreFocusRequester != null && index == restoreIndex -> Modifier.focusRequester(restoreFocusRequester)
                                    initialFocusRequester != null && index == 0 -> Modifier.focusRequester(initialFocusRequester)
                                    else -> Modifier
                                }
                            ) {
                                onItemClick(item.kind.keyPart, item.sourceId, index)
                            }
                        }
                    }
        }
    }
}

@Composable
private fun PosterCard(
    item: TitleCardProjection,
    accentColor: Color,
    enrichedItems: Map<String, com.saab.tv.data.model.stremio.MetaItem> = emptyMap(),
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    showFocusOutline: Boolean = true,
    onLongClick: (Rect) -> Unit = {},
    onClick: () -> Unit
) {
    val sourceMeta = item.asMetaItem()
    val previewItem = enrichedItems[sourceMeta.canonicalPresentationKey()] ?: sourceMeta
    val cardProjection = remember(previewItem, item) {
        previewItem.toCanonicalProjection() ?: item
    }
    com.saab.tv.ui.components.SaabTvCard(
        title = cardProjection.title,
        posterUrl = cardProjection.artwork(com.saab.tv.domain.schema.ArtworkRole.POSTER)?.locator,
        previewItem = previewItem,
        canonicalProjection = cardProjection,
        normalWidth = 120.dp, normalHeight = 180.dp, onFocused = onFocused,
        modifier = modifier.titleTrailerFocus(previewItem), showFocusOutline = showFocusOutline, onClick = onClick,
        onLongClick = onLongClick)
}
