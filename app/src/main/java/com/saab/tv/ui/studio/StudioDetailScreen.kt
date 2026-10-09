package com.saab.tv.ui.studio

import com.saab.tv.ui.trailer.titleTrailerFocus

import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import com.saab.tv.ui.theme.LocalRoundCorners
import com.saab.tv.ui.components.cardFocusSound
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.saab.tv.data.tmdb.TmdbDiscoverRail
import com.saab.tv.data.tmdb.TmdbEntityDetail
import com.saab.tv.data.tmdb.TmdbMetaPreview
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.saab.tv.ui.home.DpadRepeatGate
import com.saab.tv.ui.home.FocusPivotSpec

@Composable
fun StudioDetailScreen(
    entityId: Int,
    entityKind: String,
    entityName: String,
    sourceType: String = "movie",
    onBackPress: () -> Unit = {},
    onNavigateToDetails: (type: String, id: String) -> Unit = { _, _ -> },
    viewModel: StudioDetailViewModel = hiltViewModel()
) {
    com.saab.tv.ui.trailer.TitleTrailerHost(onOpen = { onNavigateToDetails(it.type, it.id) }) {

    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val bg = MaterialTheme.colorScheme.background
    val accentColor = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onBackground

    androidx.activity.compose.BackHandler { onBackPress() }

    Box(modifier = Modifier.fillMaxSize().background(bg)) {
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
                val initialFocusRequester = remember { FocusRequester() }
                var restoreRowKey by rememberSaveable { mutableStateOf<String?>(null) }
                var restoreIndex by rememberSaveable { mutableStateOf(-1) }

                androidx.compose.runtime.LaunchedEffect(Unit) {
                    if (restoreRowKey != null && restoreIndex >= 0) {
                        runCatching { restoreFocusRequester.requestFocus() }
                        restoreRowKey = null
                        restoreIndex = -1
                    } else {
                        runCatching { initialFocusRequester.requestFocus() }
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
                    initialFocusRequester = initialFocusRequester
                )
            }
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
    initialFocusRequester: FocusRequester
) {
    val density = LocalDensity.current
    val verticalPivotPx = remember(density) { with(density) { 45.dp.toPx() } }
    val verticalPivot = remember(verticalPivotPx) {
        FocusPivotSpec(
            customOffset = verticalPivotPx,
            stiffnessProvider = { Spring.StiffnessMediumLow }
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
                                initialFocusRequester = if (railIndex == 0) initialFocusRequester else null
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
            val kindLabel = if (entity.kind == "network") "Network" else "Production Company"
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
private fun DiscoverRailSection(
    rail: TmdbDiscoverRail,
    accentColor: Color,
    textColor: Color,
    onItemClick: (type: String, id: String, index: Int) -> Unit,
    onLoadMore: () -> Unit,
    restoreIndex: Int = -1,
    restoreFocusRequester: FocusRequester? = null,
    initialFocusRequester: FocusRequester? = null
) {
    val density = LocalDensity.current
    val repeatGate = remember { DpadRepeatGate(horizontalRepeatIntervalMs = 150L) }
    val startPad = 48.dp
    val paddingPx = remember(density) { with(density) { startPad.toPx() } }
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val endPadding = (screenWidth - startPad - 120.dp).coerceAtLeast(120.dp)
    val rowState = rememberLazyListState()

    val pivotSpec = remember(paddingPx) {
        FocusPivotSpec(
            customOffset = paddingPx,
            stiffnessProvider = { Spring.StiffnessMediumLow }
        )
    }

    // Rail title
    val mediaLabel = if (rail.mediaType == "tv") "TV Shows" else "Movies"
    val typeLabel = when (rail.railType) {
        "top_rated" -> "Top Rated"
        "recent" -> "Recent"
        else -> "Popular"
    }

    // Auto-load more when near end
    val lastVisible = rowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
    if (lastVisible >= rail.items.size - 4 && rail.hasMore) {
        androidx.compose.runtime.LaunchedEffect(lastVisible) { onLoadMore() }
    }

    Column {
        Text(
            text = "$mediaLabel · $typeLabel",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = textColor.copy(alpha = 0.9f),
            modifier = Modifier.padding(start = 48.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        CompositionLocalProvider(LocalBringIntoViewSpec provides pivotSpec) {
            LazyRow(
                state = rowState,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(start = startPad, end = endPadding)
            ) {
                itemsIndexed(rail.items, key = { i, it -> "${it.tmdbId}_$i" }) { index, item ->
                    Box(modifier = Modifier.onPreviewKeyEvent {
                        if (repeatGate.shouldConsume(it)) return@onPreviewKeyEvent true
                        if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && index == 0) true else false
                    }) {
                        PosterCard(
                            item = item,
                            accentColor = accentColor,
                            modifier = when {
                                restoreFocusRequester != null && index == restoreIndex -> Modifier.focusRequester(restoreFocusRequester)
                                initialFocusRequester != null && index == 0 -> Modifier.focusRequester(initialFocusRequester)
                                else -> Modifier
                            }
                        ) {
                            val stremioType = if (item.type == "series") "series" else "movie"
                            onItemClick(stremioType, "tmdb:${item.tmdbId}", index)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterCard(
    item: TmdbMetaPreview,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val meta = com.saab.tv.data.model.stremio.MetaItem(
        id = "tmdb:${item.tmdbId}", type = if (item.type == "tv") "series" else item.type,
        name = item.name, poster = item.poster)
    com.saab.tv.ui.components.SaabTvCard(
        title = item.name, posterUrl = item.poster, previewItem = meta,
        normalWidth = 120.dp, normalHeight = 180.dp,
        modifier = modifier.titleTrailerFocus(meta), onClick = onClick)
}
