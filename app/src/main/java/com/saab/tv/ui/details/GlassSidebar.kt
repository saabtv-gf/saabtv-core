package com.saab.tv.ui.details

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.saab.tv.ui.home.DpadRepeatGate
import com.saab.tv.data.model.stremio.MetaVideo
import com.saab.tv.data.model.stremio.Stream
import com.saab.tv.data.stream.StreamDisplayFormatter
import com.saab.tv.data.stream.StreamScoreCalculator
import com.saab.tv.data.stream.StreamLanguageSelector
import com.saab.tv.data.tmdb.TmdbEpisodeEnrichment
import com.saab.tv.domain.episodeMatchesPlaybackId
import com.saab.tv.domain.normalizeEpisodeList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// --- STATE & UTILS ---

sealed class SidebarState {
    data object Closed : SidebarState()
    data class Episodes(val videos: List<MetaVideo>) : SidebarState()
    data class Sources(
        val streamTitle: String,
        val streams: List<Stream>?,
        val selectedStreamId: String? = null,
        val showBestLanguageOptions: Boolean = false
    ) : SidebarState()
}

// Handles Back key for sidebar dismissal. Left trapping is handled per-item via focusProperties.
private fun Modifier.dpadNavigation(onBack: () -> Unit, trapLeft: Boolean = true, repeatGate: DpadRepeatGate? = null) = this.onPreviewKeyEvent {
    if (repeatGate?.shouldConsume(it) == true) return@onPreviewKeyEvent true
    when {
        trapLeft && it.key == Key.DirectionLeft && it.type == KeyEventType.KeyDown -> true
        it.key == Key.Back && it.type == KeyEventType.KeyUp -> { onBack(); true }
        else -> false
    }
}

// --- MAIN SIDEBAR ---

@Composable
fun GlassSidebarScaffold(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    panelWidth: Dp = 500.dp,
    panelPadding: PaddingValues = PaddingValues(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 0.dp),
    overlayAlpha: Float = 0.4f,
    opaquePanel: Boolean = false,
    enter: EnterTransition = slideInHorizontally { it },
    exit: ExitTransition = slideOutHorizontally { it },
    content: @Composable ColumnScope.() -> Unit
) {
    if (visible) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = overlayAlpha))
                .clickable(onClick = onDismiss)
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = enter,
        exit = exit,
        modifier = modifier.fillMaxSize()
    ) {
        val panelBackground = if (opaquePanel) {
            Modifier.background(MaterialTheme.colorScheme.background)
        } else {
            Modifier.background(
                Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0.0f to Color(0xCC000000),
                        0.15f to Color(0xD2000000),
                        0.3f to Color(0xD9000000),
                        0.45f to Color(0xE1000000),
                        0.6f to Color(0xE9000000),
                        0.75f to Color(0xF0000000),
                        0.88f to Color(0xF6000000),
                        1.0f to Color(0xFA000000)
                    )
                )
            )
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .width(panelWidth)
                    .then(panelBackground)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(panelPadding)
            ) {
                content()
            }
        }
    }
}

@Composable
fun GlassSidebar(
    state: SidebarState,
    sourceLanguagePreferences: List<String> = emptyList(),
    currentEpisodeId: String? = null,
    episodeProgressMap: Map<String, DetailsViewModel.EpisodeProgress> = emptyMap(),
    episodeEnrichmentMap: Map<String, TmdbEpisodeEnrichment> = emptyMap(),
    onToggleWatched: (MetaVideo) -> Unit = {},
    onEpisodeSelected: (MetaVideo) -> Unit,
    onSourceSelected: (Stream) -> Unit,
    onLanguageSourceSelected: (Stream) -> Unit = onSourceSelected,
    onBack: () -> Unit,
    onDismiss: () -> Unit
) {
    val isVisible = state !is SidebarState.Closed
    val focusRequester = remember { FocusRequester() }
    val episodesListState = rememberLazyListState()
    var savedSeason by remember { mutableStateOf<Int?>(null) }
    var savedIndex by remember { mutableIntStateOf(0) }
    var previousState by remember { mutableStateOf<SidebarState>(SidebarState.Closed) }
    var sourceLanguageFilter by remember { mutableStateOf<String?>(null) }

    // When sidebar opens with episodes, auto-navigate to the currently playing episode,
    // reset to the beginning if freshly opened from details (Closed→Episodes),
    // or preserve position when returning from sources (Sources→Episodes).
    LaunchedEffect(state, currentEpisodeId) {
        if (state is SidebarState.Sources && previousState !is SidebarState.Sources) {
            sourceLanguageFilter = null
        }
        if (state is SidebarState.Episodes) {
            if (currentEpisodeId != null) {
                val seasonMap = normalizeEpisodeList(state.videos).groupBy { it.season }
                var matched = false
                for ((season, eps) in seasonMap) {
                    val idx = eps.indexOfFirst { ep -> episodeMatchesPlaybackId(null, currentEpisodeId, ep) }
                    if (idx >= 0) {
                        savedSeason = season
                        savedIndex = idx
                        matched = true
                        break
                    }
                }
                if (!matched && previousState is SidebarState.Closed) {
                    savedSeason = seasonMap.keys.minOrNull()
                    savedIndex = 0
                }
            } else if (previousState is SidebarState.Closed) {
                // Fresh open from details screen — reset to first episode
                savedSeason = null
                savedIndex = 0
                runCatching { episodesListState.scrollToItem(0) }
            }
            // Sources→Episodes: keep savedSeason/savedIndex from the episode click
        }
        previousState = state
    }

    val focusMode = when (state) {
        SidebarState.Closed -> "closed"
        is SidebarState.Episodes -> "episodes"
        is SidebarState.Sources -> "sources"
    }
    val sourceContentReady = (state as? SidebarState.Sources)?.streams != null
    LaunchedEffect(focusMode, sourceContentReady) {
        if (isVisible) { delay(200); runCatching { focusRequester.requestFocus() } }
    }

    val isEpisodes = state is SidebarState.Episodes
    GlassSidebarScaffold(
        visible = isVisible,
        onDismiss = onDismiss,
        panelWidth = 620.dp,
        opaquePanel = state is SidebarState.Sources
    ) {
        Crossfade(targetState = state, label = "Sidebar") { current ->
            when (current) {
                is SidebarState.Episodes -> EpisodesContent(
                    videos = current.videos,
                    listState = episodesListState,
                    savedSeason = savedSeason,
                    savedIndex = savedIndex,
                    currentEpisodeId = currentEpisodeId,
                    episodeProgressMap = episodeProgressMap,
                    episodeEnrichmentMap = episodeEnrichmentMap,
                    onToggleWatched = onToggleWatched,
                    focusRequester = focusRequester,
                    onEpisodeClick = { ep, s, i -> savedSeason = s; savedIndex = i; onEpisodeSelected(ep) },
                    onSeasonChange = { savedSeason = it },
                    onDismiss = onDismiss
                )
                is SidebarState.Sources -> SourcesContent(
                    title = current.streamTitle,
                    streams = current.streams,
                    sourceLanguagePreferences = sourceLanguagePreferences,
                    selectedStreamId = current.selectedStreamId,
                    showBestLanguageOptions = current.showBestLanguageOptions,
                    languageFilter = sourceLanguageFilter,
                    onLanguageFilterChanged = { sourceLanguageFilter = it },
                    focusRequester = focusRequester,
                    onSourceClick = onSourceSelected,
                    onLanguageSourceClick = onLanguageSourceSelected,
                    onBack = onBack
                )
                else -> {}
                }
            }
        }
}

// --- CONTENT: SEASONS/EPISODES ---

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EpisodesContent(
    videos: List<MetaVideo>,
    listState: LazyListState,
    savedSeason: Int?,
    savedIndex: Int,
    currentEpisodeId: String? = null,
    episodeProgressMap: Map<String, DetailsViewModel.EpisodeProgress> = emptyMap(),
    episodeEnrichmentMap: Map<String, TmdbEpisodeEnrichment> = emptyMap(),
    onToggleWatched: (MetaVideo) -> Unit = {},
    focusRequester: FocusRequester,
    onEpisodeClick: (MetaVideo, Int, Int) -> Unit,
    onSeasonChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val seasons = remember(videos) { normalizeEpisodeList(videos).groupBy { it.season }.toSortedMap() }
    var selectedSeason by remember(savedSeason, videos) {
        mutableIntStateOf(savedSeason?.takeIf(seasons::containsKey) ?: seasons.keys.minOrNull() ?: 1)
    }
    val episodes = seasons[selectedSeason] ?: emptyList()

    val tabRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope() // Needed for manual scrolling
    val repeatGate = remember { DpadRepeatGate(horizontalRepeatIntervalMs = 150L, verticalRepeatIntervalMs = 200L) }

    LaunchedEffect(selectedSeason) { onSeasonChange(selectedSeason) }

    // Scroll to the saved episode when the sidebar opens (or back to top when savedIndex is 0)
    LaunchedEffect(savedIndex, selectedSeason) {
        if (episodes.isNotEmpty() && savedIndex in episodes.indices) {
            runCatching { listState.scrollToItem(savedIndex) }
        }
    }

    Column {
        Text("More Episodes", style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.padding(bottom = 16.dp))

        if (seasons.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                itemsIndexed(seasons.keys.toList()) { idx, num ->
                    SeasonTab(
                        number = num,
                        isSelected = num == selectedSeason,
                        modifier = Modifier
                            .then(if (idx == 0) Modifier.onPreviewKeyEvent {
                                if (repeatGate.shouldConsume(it)) return@onPreviewKeyEvent true
                                it.key == Key.DirectionLeft && it.type == KeyEventType.KeyDown
                            } else Modifier.onPreviewKeyEvent { repeatGate.shouldConsume(it) })
                            .then(if (num == selectedSeason) Modifier.focusRequester(tabRequester) else Modifier)
                            .focusProperties { up = FocusRequester.Cancel },
                        onClick = {
                            selectedSeason = num
                            // Only reset scroll to top when manually changing seasons
                            scope.launch { listState.scrollToItem(0) }
                        }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(
            state = listState, // Using the hoisted state
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.dpadNavigation(onDismiss, trapLeft = false, repeatGate = repeatGate)
        ) {
            if (episodes.isEmpty()) item { Text("No episodes found.", color = Color.Gray) }
            else {
                itemsIndexed(episodes, key = { index, ep -> "${ep.season}:${ep.episode}:${ep.id}:$index" }) { index, ep ->
                    // Focus Logic: Attach requester to saved index; Attach 'Up' navigation to first index
                    val isTarget = index == (if (savedIndex in episodes.indices) savedIndex else 0)
                    val mod = Modifier
                        .then(if (isTarget) Modifier.focusRequester(focusRequester) else Modifier)
                        .then(if (index == 0) Modifier.focusProperties { up = if (seasons.isNotEmpty()) tabRequester else FocusRequester.Cancel } else Modifier)

                    val isCurrentEpisode = episodeMatchesPlaybackId(null, currentEpisodeId, ep)
                    val epKey = "S${ep.season}:E${ep.episode}"
                    val epProgress = episodeProgressMap[epKey]
                    val epEnrichment = episodeEnrichmentMap[epKey]
                    EpisodeItem(
                        episode = ep,
                        isPlaying = isCurrentEpisode,
                        progress = epProgress?.progress,
                        isWatched = epProgress?.watched ?: false,
                        enrichment = epEnrichment,
                        onToggleWatched = { onToggleWatched(ep) },
                        thumbnailModifier = mod,
                        listState = listState,
                        onClick = { onEpisodeClick(ep, selectedSeason, index) }
                    )
                }
            }
        }
    }
}

// --- CONTENT: SOURCES ---

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SourcesContent(
    title: String,
    streams: List<Stream>?,
    sourceLanguagePreferences: List<String> = emptyList(),
    selectedStreamId: String? = null,
    showBestLanguageOptions: Boolean = false,
    languageFilter: String? = null,
    onLanguageFilterChanged: (String?) -> Unit = {},
    focusRequester: FocusRequester,
    onSourceClick: (Stream) -> Unit,
    onLanguageSourceClick: (Stream) -> Unit = onSourceClick,
    onBack: () -> Unit
) {
    // The percentage printed on each card is the final picker contract. Do not
    // let an older profile sort mode or addon response order contradict it.
    val actualStreams = remember(streams, sourceLanguagePreferences) {
        StreamScoreCalculator.sortDescending(streams.orEmpty(), sourceLanguagePreferences)
    }
    var addonFilter by remember { mutableStateOf("All Addons") }

    val addonNames = remember(actualStreams) {
        listOf("All Addons") + actualStreams.mapNotNull { it.name?.substringAfter("[")?.substringBefore("]") }.distinct()
    }

    val addonFilteredStreams = remember(actualStreams, addonFilter) {
        actualStreams.filter { stream ->
            addonFilter == "All Addons" || stream.name?.contains(addonFilter) == true
        }
    }

    val availableLanguages = remember(addonFilteredStreams, showBestLanguageOptions) {
        if (showBestLanguageOptions) StreamLanguageSelector.availableLanguages(addonFilteredStreams)
        else emptyList()
    }

    val filtered = remember(addonFilteredStreams, languageFilter) {
        if (languageFilter == null) addonFilteredStreams
        else addonFilteredStreams.filter { StreamLanguageSelector.hasLanguage(it, languageFilter) }
    }

    LaunchedEffect(availableLanguages, languageFilter) {
        if (languageFilter != null && availableLanguages.none { it.code == languageFilter }) {
            onLanguageFilterChanged(null)
        }
    }

    val selectedIndex = remember(filtered, selectedStreamId) {
        if (selectedStreamId == null) 0
        else {
            val idx = filtered.indexOfFirst { s ->
                (s.url ?: s.addonTransportUrl) == selectedStreamId
            }
            if (idx >= 0) idx else 0
        }
    }

    val listState = rememberLazyListState()
    val repeatGate = remember { DpadRepeatGate(verticalRepeatIntervalMs = 200L) }

    LaunchedEffect(selectedIndex, filtered) {
        if (filtered.isNotEmpty() && selectedIndex > 0) {
            runCatching { listState.scrollToItem(selectedIndex) }
        }
    }

    LaunchedEffect(languageFilter, addonFilter) {
        if (filtered.isNotEmpty()) runCatching { listState.scrollToItem(0) }
    }

    Column {
        Text("Select Source", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))

        if (streams == null) {
            Box(
                Modifier.fillMaxSize()
                    .focusRequester(focusRequester)
                    .dpadNavigation(onBack)
                    .focusable(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text("Loading streams...", color = Color.LightGray)
                }
            }
        } else {
            if (showBestLanguageOptions) {
                Text(
                    "Source Language",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.78f)
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BestLanguageButton(
                        label = "All",
                        isSelected = languageFilter == null,
                        blockLeft = true,
                        onClick = { onLanguageFilterChanged(null) },
                        modifier = Modifier.focusRequester(focusRequester)
                    )
                    availableLanguages.forEach { language ->
                        BestLanguageButton(
                            label = language.label,
                            isSelected = languageFilter == language.code,
                            onClick = {
                                onLanguageFilterChanged(language.code)
                                StreamLanguageSelector.bestAvailable(addonFilteredStreams, language.code)
                                    ?.let(onLanguageSourceClick)
                            }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            FilterDropdown(
                currentValue = addonFilter,
                options = addonNames,
                modifier = Modifier
                    .then(
                        if (!showBestLanguageOptions) {
                            Modifier.focusProperties { up = FocusRequester.Cancel }
                        } else Modifier
                    )
                    .onPreviewKeyEvent { it.key == Key.DirectionLeft && it.type == KeyEventType.KeyDown },
                onSelect = { addonFilter = it }
            )
            Spacer(Modifier.height(16.dp))
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.dpadNavigation(onBack, repeatGate = repeatGate)
            ) {
                if (filtered.isEmpty()) item {
                    Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                        Text("No streams found.", color = Color.Gray, style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    itemsIndexed(filtered, key = { index, s -> "${index}_${s.url ?: s.addonTransportUrl ?: index}" }) { index, s ->
                        RawSourceItem(
                            contentTitle = title,
                            stream = s,
                            sourceLanguagePreferences = sourceLanguagePreferences,
                            isPlaying = index == selectedIndex && selectedStreamId != null,
                            modifier = if (!showBestLanguageOptions && index == selectedIndex) {
                                Modifier.focusRequester(focusRequester)
                            } else Modifier
                        ) { onSourceClick(s) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BestLanguageButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    blockLeft: Boolean = false
) {
    var isFocused by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = when {
                isFocused -> primary
                isSelected -> primary.copy(alpha = 0.28f)
                else -> Color.White.copy(alpha = 0.08f)
            },
            contentColor = if (isFocused) MaterialTheme.colorScheme.onPrimary else Color.White
        ),
        border = if (isSelected && !isFocused) {
            BorderStroke(1.dp, primary.copy(alpha = 0.75f))
        } else null,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        modifier = modifier
            .onFocusChanged { isFocused = it.isFocused }
            .onPreviewKeyEvent {
                blockLeft && it.key == Key.DirectionLeft && it.type == KeyEventType.KeyDown
            }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
        )
    }
}

// --- ITEMS & UI COMPONENTS ---

@Composable
fun FilterDropdown(currentValue: String, options: List<String>, modifier: Modifier, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }

    // Size calculation for symmetry
    var rowWidth by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    val primary = MaterialTheme.colorScheme.primary

    Box(modifier) {
        Button(
            onClick = { expanded = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isFocused) primary else Color.White.copy(0.05f),
                contentColor = if (isFocused) MaterialTheme.colorScheme.onPrimary else Color.LightGray
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused }
                .onSizeChanged {
                    // Capture width of the button to apply to dropdown
                    rowWidth = with(density) { it.width.toDp() }
                }
                .border(
                    if (isFocused) 3.dp else 1.dp,
                    if (isFocused) Color.White else Color.White.copy(alpha = 0.14f),
                    RoundedCornerShape(8.dp)
                ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                currentValue,
                color = if (isFocused) MaterialTheme.colorScheme.onPrimary else Color.LightGray,
                fontWeight = if (isFocused) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Default.ArrowDropDown,
                null,
                tint = if (isFocused) MaterialTheme.colorScheme.onPrimary else Color.Gray
            )
        }

        // HACK: DropdownMenu in standard M3 doesn't support 'containerColor' param easily.
        // It uses the internal Surface color. To get transparency, we override the theme
        // strictly for this block to force the Surface to be transparent.
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(surface = Color.Transparent),
            shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(8.dp))
        ) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .width(rowWidth) // Enforce symmetry
                    // Apply the glass gradient here manually
                    .background(
                        brush = Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0.0f to Color(0xCC000000),
                                0.15f to Color(0xD2000000),
                                0.3f to Color(0xD9000000),
                                0.45f to Color(0xE1000000),
                                0.6f to Color(0xE9000000),
                                0.75f to Color(0xF0000000),
                                0.88f to Color(0xF6000000),
                                1.0f to Color(0xFA000000)
                            )
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                    // Clip ensures the background respects the rounded shape
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            ) {
                // Calculate which item should be focused based on currentValue
                val selectedIndex = remember(options, currentValue) { options.indexOf(currentValue).coerceAtLeast(0) }

                // Create a FocusRequester for every item so we can target the specific one
                val itemRequesters = remember(options.size) { List(options.size) { FocusRequester() } }

                // Trigger focus on the selected item when the menu opens
                LaunchedEffect(expanded, selectedIndex, itemRequesters) {
                    if (!expanded || itemRequesters.isEmpty()) return@LaunchedEffect
                    delay(100)
                    runCatching { itemRequesters[selectedIndex].requestFocus() }
                }

                options.forEachIndexed { index, opt ->
                    var isItemFocused by remember { mutableStateOf(false) }

                    DropdownMenuItem(
                        text = {
                            Text(
                                text = opt,
                                color = if (isItemFocused) primary else Color.White
                            )
                        },
                        onClick = { onSelect(opt); expanded = false },
                        modifier = Modifier
                            .focusRequester(itemRequesters[index])
                            .onFocusChanged { isItemFocused = it.isFocused }
                    )
                }
            }
        }
    }
}

@Composable
fun SeasonTab(number: Int, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier.clip(RoundedCornerShape(4.dp))
            .background(if (isFocused) Color.White else if (isSelected) primary else Color.DarkGray)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick).focusable().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text("Season $number", color = if (isSelected || isFocused) Color.Black else Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EpisodeItem(
    episode: MetaVideo,
    isPlaying: Boolean = false,
    progress: Float? = null,
    isWatched: Boolean = false,
    enrichment: TmdbEpisodeEnrichment? = null,
    onToggleWatched: () -> Unit = {},
    thumbnailModifier: Modifier = Modifier,
    listState: LazyListState? = null,
    onClick: () -> Unit
) {
    var thumbnailFocused by remember { mutableStateOf(false) }
    var buttonFocused by remember { mutableStateOf(false) }
    val isFocused = thumbnailFocused || buttonFocused
    val primary = MaterialTheme.colorScheme.primary
    val thumbnailRequester = remember { FocusRequester() }
    val buttonRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    // Counteract BringIntoView scroll when the mark button gets focus
    LaunchedEffect(buttonFocused) {
        if (buttonFocused && listState != null) {
            val savedIndex = listState.firstVisibleItemIndex
            val savedOffset = listState.firstVisibleItemScrollOffset
            delay(50) // Wait for BringIntoView to execute
            listState.scrollToItem(savedIndex, savedOffset) // Scroll back
        }
    }

    // Use TMDB data when available, fall back to addon data
    val title = enrichment?.title
        ?: episode.title.takeIf { it.isNotBlank() && it != "Episode" }
        ?: "Episode ${episode.episode}"
    val overview = enrichment?.overview ?: episode.overview
    val thumbnail = enrichment?.thumbnail ?: episode.thumbnail
    val runtime = enrichment?.runtimeMinutes
    val releaseDate = remember(enrichment?.airDate, episode.released) {
        enrichment?.airDate ?: episode.released?.take(10)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Thumbnail
        Box(
            thumbnailModifier
                .width(200.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .border(
                    if (thumbnailFocused) 2.dp else 0.dp,
                    if (thumbnailFocused) primary else Color.Transparent,
                    RoundedCornerShape(6.dp)
                )
                .focusRequester(thumbnailRequester)
                .focusProperties { left = FocusRequester.Cancel; right = buttonRequester }
                .onFocusChanged { thumbnailFocused = it.isFocused }
                .clickable(onClick = onClick)
                .focusable()
        ) {
            if (thumbnail != null) {
                AsyncImage(
                    thumbnail, null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(Modifier.fillMaxSize().background(Color.White.copy(0.08f)))
            }

            // Darken thumbnail for watched episodes
            if (isWatched) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(0.4f)))
            }

            // S:E label at bottom-left
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(0.7f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    "S${episode.season} : E${episode.episode}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }

            // Progress bar floating near bottom of thumbnail
            if (progress != null && progress > 0f && !isWatched) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(Color.White.copy(0.3f))
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(primary)
                    )
                }
            }
        }

        Spacer(Modifier.width(14.dp))

        // Episode info with mark-as-watched button at bottom-right
        Box(Modifier.weight(1f)) {
            Column {
                // Title
                Text(
                    title,
                    color = when {
                        isWatched -> Color.White.copy(0.4f)
                        isFocused -> Color.White
                        else -> Color.White.copy(0.85f)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )

                // Synopsis
                if (!overview.isNullOrBlank()) {
                    Text(
                        overview,
                        color = Color.White.copy(0.5f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 4,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                // Runtime + release date + status
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (runtime != null) {
                        Text(
                            "(${runtime} min)",
                            color = Color.White.copy(0.4f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (releaseDate != null) {
                        Text(
                            releaseDate,
                            color = Color.White.copy(0.35f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (isPlaying) {
                        Text(
                            "Playing",
                            style = MaterialTheme.typography.labelSmall,
                            color = primary
                        )
                    }
                }
            }

            // Mark as watched button — top-right
            WatchedToggleButton(
                isWatched = isWatched,
                isFocused = buttonFocused,
                focusRequester = buttonRequester,
                thumbnailRequester = thumbnailRequester,
                onFocusChanged = { buttonFocused = it },
                onClick = onToggleWatched,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WatchedToggleButton(
    isWatched: Boolean,
    isFocused: Boolean,
    focusRequester: FocusRequester,
    thumbnailRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary

    val targetWidth = if (isFocused) if (isWatched) 100.dp else 138.dp else 24.dp
    val animatedWidth by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "watchedBtnWidth"
    )

    val bgColor = when {
        isWatched && isFocused -> primary.copy(0.2f)
        isWatched -> primary.copy(0.1f)
        isFocused -> Color.White.copy(0.15f)
        else -> Color.White.copy(0.08f)
    }
    val borderColor = when {
        isFocused -> primary
        isWatched -> primary.copy(0.4f)
        else -> Color.White.copy(0.15f)
    }
    val iconColor = when {
        isWatched -> primary
        isFocused -> Color.White
        else -> Color.White.copy(0.5f)
    }

    Row(
        modifier = modifier
            .width(animatedWidth)
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .focusRequester(focusRequester)
            .focusProperties { left = thumbnailRequester; right = FocusRequester.Cancel }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionUp || event.key == Key.DirectionDown)) {
                    // Move to thumbnail first, then let the system handle up/down to adjacent episodes
                    thumbnailRequester.requestFocus()
                    false // Don't consume — let the key propagate from the thumbnail
                } else false
            }
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            if (isWatched) "✓" else "+",
            color = iconColor,
            style = MaterialTheme.typography.labelMedium
        )

        if (isFocused) {
            Spacer(Modifier.width(4.dp))
            Text(
                if (isWatched) "Watched" else "Mark as watched",
                color = if (isWatched) primary else Color.White,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

@Composable
fun RawSourceItem(
    contentTitle: String,
    stream: Stream,
    sourceLanguagePreferences: List<String> = emptyList(),
    isPlaying: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    val display = remember(contentTitle, stream) { StreamDisplayFormatter.format(contentTitle, stream) }
    val scorePercent = remember(stream, sourceLanguagePreferences) {
        StreamScoreCalculator.scoreLabel(stream, sourceLanguagePreferences)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = if (isFocused) Color.White.copy(0.1f) else Color.White.copy(0.05f)),
        modifier = modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused }
            .border(if (isFocused) 3.dp else 0.dp, if (isFocused) primary else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    display.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isFocused) Color.White else Color.LightGray,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isPlaying) {
                    Text(
                        "Playing",
                        style = MaterialTheme.typography.labelSmall,
                        color = primary,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                Text(
                    "Score $scorePercent%",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (isFocused) MaterialTheme.colorScheme.onPrimary else primary,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .background(
                            color = if (isFocused) primary else primary.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
            Text(
                display.details,
                style = MaterialTheme.typography.labelSmall,
                color = if (isFocused) primary else Color.Gray,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
