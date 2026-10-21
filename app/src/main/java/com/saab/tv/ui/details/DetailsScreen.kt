package com.saab.tv.ui.details

import com.saab.tv.ui.trailer.titleTrailerFocus

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import com.saab.tv.ui.theme.LocalRoundCorners
import com.saab.tv.ui.components.cardFocusSound
import com.saab.tv.ui.components.SaabMotion
import com.saab.tv.ui.components.scrollToItemUsingSaabMotion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.saab.tv.domain.AddonSubtitle
import com.saab.tv.domain.episodePlaybackId
import com.saab.tv.domain.episodeStreamId
import com.saab.tv.domain.episodeDisplayTitle
import com.saab.tv.domain.schema.canonicalPresentationKey
import com.saab.tv.domain.schema.toCanonicalProjection
import com.saab.tv.domain.schema.ArtworkRole
import com.saab.tv.data.model.stremio.MetaVideo
import com.saab.tv.data.model.stremio.Stream
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.delay
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.saab.tv.R
import com.saab.tv.ui.home.DpadRepeatGate
import com.saab.tv.ui.components.FocusPivotSpec
import com.saab.tv.ui.utils.requestFocusOrFallback
import com.saab.tv.ui.utils.requestFocusSafely
import com.saab.tv.ui.utils.requestFocusWhenAttached
import com.saab.tv.ui.navigation.LocalNavigationFocusHandoff
import com.saab.tv.ui.navigation.handoffNavigationFocus
import com.saab.tv.data.tmdb.TmdbCastInfo
import com.saab.tv.data.tmdb.TmdbCompanyInfo
import com.saab.tv.domain.schema.TitleCardProjection
import com.saab.tv.data.tmdb.TmdbVideoInfo
import kotlinx.coroutines.launch
import com.saab.tv.ui.utils.ArtworkCacheRole
import com.saab.tv.ui.utils.artworkDecodeSizePx
import com.saab.tv.ui.utils.artworkImageRequest

internal fun detailsBackdropCandidates(
    tmdbBackdrop: String?,
    canonicalBackdrop: String?,
    providerBackdrop: String?,
    canonicalPoster: String?,
    providerPoster: String?
): List<String> = sequenceOf(canonicalBackdrop, providerBackdrop, tmdbBackdrop, canonicalPoster, providerPoster)
    .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
    .distinct()
    .toList()

internal fun resolveDetailsBackdropUrl(
    tmdbBackdrop: String?,
    canonicalBackdrop: String?,
    providerBackdrop: String?,
    canonicalPoster: String?,
    providerPoster: String?
): String? = detailsBackdropCandidates(
    tmdbBackdrop, canonicalBackdrop, providerBackdrop, canonicalPoster, providerPoster
).firstOrNull()

internal fun nextDetailsBackdropIndex(currentIndex: Int, candidateCount: Int): Int? =
    (currentIndex + 1).takeIf { it in 0 until candidateCount }

@Composable
fun DetailsScreen(
    type: String,
    id: String,
    addonBaseUrl: String? = null,
    resumePlaybackHint: String? = null,
    autoStartPlayback: Boolean = false,
    onAutoResumeNeedsSelection: () -> Unit = {},
    autoSelectSource: Boolean = false,
    rememberSourceSelection: Boolean = true,
    onPlayClick: (String, String, String, String, String, String, Stream, List<AddonSubtitle>, List<Stream>, List<MetaVideo>, String, String, String) -> Unit,
    onNavigateToDetails: (type: String, id: String) -> Unit = { _, _ -> },
    onStartWatching: ((com.saab.tv.data.model.stremio.MetaItem) -> Unit)? = null,
    onNavigateToCastDetail: (personId: Int, personName: String) -> Unit = { _, _ -> },
    onNavigateToStudioDetail: (entityId: Int, entityKind: String, entityName: String, sourceType: String) -> Unit = { _, _, _, _ -> },
    onPosterResolved: (poster: String) -> Unit = {},
    onTrailerClick: (youtubeKey: String, trailerName: String) -> Unit = { _, _ -> },
    isTrailerLoading: Boolean = false,
    trailerReturnToken: Int = 0,
    trailerHostViewModel: com.saab.tv.ui.home.HomeViewModel? = null,
    currentProfile: com.saab.tv.data.model.ProfileEntity? = null,
    viewModel: DetailsViewModel = hiltViewModel(key = "details_${type}_${id}")
) {
    val manualTrailerLauncher = com.saab.tv.ui.trailer.LocalManualTrailerLauncher.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val quickActionViewModel = trailerHostViewModel ?: hiltViewModel<com.saab.tv.ui.home.HomeViewModel>()
    val quickActionScope = rememberCoroutineScope()
    var quickActionProfileId by remember { mutableStateOf(0) }
    var quickActionTarget by remember { mutableStateOf<Pair<com.saab.tv.data.model.stremio.MetaItem, Rect>?>(null) }
    var quickActionReturnFocus by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(quickActionViewModel) {
        quickActionProfileId = quickActionViewModel.activeProfileId() ?: 0
    }
    LaunchedEffect(quickActionViewModel, currentProfile) {
        quickActionViewModel.configureTmdbProfile(currentProfile)
    }
    com.saab.tv.ui.trailer.TitleTrailerHost(onOpen = { onNavigateToDetails(it.type, it.id) },
        defaultItem = state.meta.takeIf { !state.isLoading && state.contentKey == "$type:$id" },
        onEpisodes = { viewModel.openEpisodes() },
        defaultEnabled = !state.isLoadingStreams && state.sidebarState is SidebarState.Closed && !autoStartPlayback && quickActionTarget == null,
        model = quickActionViewModel,
        onStartWatching = onStartWatching) {

    LaunchedEffect(type, id, autoStartPlayback) { viewModel.loadDetails(type, id, addonBaseUrl, playbackOnly = autoStartPlayback) }

    val content = state.contentProjection
    // MetaItem is materialized only for the existing playback/action contracts below.
    val movie = state.meta
    val streamId = state.resolvedId ?: movie?.id ?: id // Resolved IMDb ID for stream/subtitle requests
    val detailPoster = content?.artwork(ArtworkRole.POSTER)?.locator?.takeIf { it.isNotBlank() }
        ?: movie?.poster.orEmpty()
    // Check contentKey to prevent stale content from the previous item flashing for one frame.
    // contentKey is set when meta loads and matches "$type:$id" of the navigation params.
    val isCurrentMovie = movie != null && !state.isLoading && state.contentKey == "$type:$id"
    val showMovieContent = isCurrentMovie

    LaunchedEffect(showMovieContent) {
        if (showMovieContent) {
            (content?.artwork(ArtworkRole.POSTER)?.locator ?: movie?.poster)?.let(onPosterResolved)
        }
    }
    val sidebarState = if (isCurrentMovie) state.sidebarState else SidebarState.Closed

    val accentColor = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground
    val lifecycleOwner = LocalLifecycleOwner.current
    var routeResumeEpoch by remember { mutableStateOf(0) }

    var showClearProgressDialog by remember { mutableStateOf(false) }
    var pendingPlaybackId by remember(type, id) { mutableStateOf(id) }
    var pendingPlaybackType by remember(type, id) { mutableStateOf(type) }
    var pendingPlaybackTitle by remember(type, id) { mutableStateOf("") }
    val autoPlayStream = state.autoPlayStream
    val addonSubtitles = state.addonSubtitles
    val availableStreams = state.availableStreams

    LaunchedEffect(autoPlayStream) {
        val stream = autoPlayStream ?: return@LaunchedEffect
        val urlToPlay = resolvePlayableUrl(stream)
        if (!urlToPlay.isNullOrEmpty()) {
            val playbackId = pendingPlaybackId.ifBlank { movie?.id ?: id }
            val playbackType = pendingPlaybackType.ifBlank { movie?.type ?: type }
            val playbackTitle = pendingPlaybackTitle.ifBlank { movie?.name ?: "" }
            onPlayClick(
                urlToPlay,
                playbackId,
                playbackType,
                playbackTitle,
                movie?.name ?: "",
                movie?.logo ?: "",
                stream,
                addonSubtitles,
                availableStreams,
                movie?.videos ?: emptyList(),
                id,
                type,
                detailPoster
            )
        }
        viewModel.consumeAutoPlayStream()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                routeResumeEpoch++
                viewModel.refreshResumeState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    com.saab.tv.ui.navigation.AppBackHandler(
        enabled = sidebarState !is SidebarState.Closed,
        priority = com.saab.tv.ui.navigation.AppBackPriority.Modal
    ) {
        viewModel.goBackInSidebar()
    }

    val firstButtonFocusRequester = remember { FocusRequester() }
    val episodesButtonFocusRequester = remember { FocusRequester() }
    val restoreFocusRequester = remember { FocusRequester() }

    // Track the previous sidebar state so we can restore focus to the
    // episodes button when the episodes sidebar closes.
    var previousSidebarState by remember { mutableStateOf<SidebarState>(SidebarState.Closed) }
    LaunchedEffect(sidebarState) {
        if (sidebarState is SidebarState.Closed && previousSidebarState is SidebarState.Episodes) {
            if (!episodesButtonFocusRequester.requestFocusWhenAttached()) {
                firstButtonFocusRequester.requestFocusSafely()
            }
        }
        previousSidebarState = sidebarState
    }
    var restoreRowKey by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreIndex by rememberSaveable { mutableStateOf(-1) }
    var restoreItemKey by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    val restoreRows = remember(state.tmdbEnrichment, state.tmdbRecommendations, state.tmdbCollection) {
        val enrichment = state.tmdbEnrichment
        val crew = enrichment?.directorMembers.orEmpty() + enrichment?.writerMembers.orEmpty()
        val cast = enrichment?.castMembers.orEmpty()
        val castKeys = buildList<String?> {
            crew.forEachIndexed { index, member -> add("crew_${member.tmdbId ?: index}") }
            if (crew.isNotEmpty() && cast.isNotEmpty()) add(null)
            cast.take(20).forEachIndexed { index, member -> add("cast_${member.tmdbId ?: index}") }
        }
        buildMap {
            if (castKeys.any { it != null }) put("tmdb_cast", castKeys)
            if (state.tmdbRecommendations.isNotEmpty()) {
                put("tmdb_recs", state.tmdbRecommendations.map { it.presentationKey })
            }
            if (state.tmdbCollection.isNotEmpty()) {
                put("tmdb_collection", state.tmdbCollection.map { it.presentationKey })
            }
        }
    }
    val restoreRowOrder = remember(state.tmdbEnrichment, state.tmdbRecommendations, state.tmdbCollection) {
        buildList {
            add("hero")
            if (restoreRows.containsKey("tmdb_cast")) add("tmdb_cast")
            if (restoreRows.containsKey("tmdb_recs")) add("tmdb_recs")
            if (restoreRows.containsKey("tmdb_collection")) add("tmdb_collection")
            if (state.tmdbEnrichment != null || state.tmdbRecommendations.isNotEmpty() || state.tmdbCollection.isNotEmpty()) {
                add("tmdb_spacer")
            }
        }
    }
    val restoreTarget = remember(
        restoreRowKey, restoreItemKey, restoreIndex, state.tmdbLoading, restoreRowOrder, restoreRows
    ) {
        resolveDetailsFocusRestoreTarget(
            rowKey = restoreRowKey,
            itemKey = restoreItemKey,
            fallbackIndex = restoreIndex,
            metadataPending = state.tmdbLoading,
            rowKeys = restoreRowOrder,
            itemsByRow = restoreRows
        )
    }

    // TMDB enrichment is supplemental: render addon details immediately and patch
    // localized metadata/recommendations into the screen as they arrive.
    val contentReady = showMovieContent
    var autoStartRequested by remember(type, id) { mutableStateOf(false) }
    LaunchedEffect(showMovieContent, autoStartPlayback, state.resumeStateReady, state.resumePlaybackId, streamId, resumePlaybackHint) {
        if (!showMovieContent || !state.resumeStateReady || !viewModel.state.value.resumeStateReady ||
            !autoStartPlayback || autoStartRequested) return@LaunchedEffect
        val readyMovie = movie ?: return@LaunchedEffect
        if (type == "series") {
            val hintIsWatched = resumePlaybackHint?.let { candidate ->
                parseSeasonEpisodeFromPlaybackId(candidate)?.let { (season, episode) ->
                    state.episodeProgressMap["S${season}:E${episode}"]?.watched == true
                } ?: false
            } ?: false
            val resumeId = com.saab.tv.ui.home.ContinueResumePolicy.playbackId(
                seriesId = id,
                continueHint = resumePlaybackHint,
                hintIsWatched = hintIsWatched,
                calculatedResumeId = state.resumePlaybackId
            )
            val episode = if (resumeId != null) {
                resolveEpisodeForPlaybackId(readyMovie.id, readyMovie.videos, resumeId)
                    ?: run {
                        com.saab.tv.AppDiagnostics.event("Resume", "Episode Target Missing", "id=$resumeId")
                        onAutoResumeNeedsSelection()
                        return@LaunchedEffect // Never pair an unknown ID with episode one's source.
                    }
            } else findFirstEpisode(readyMovie.videos) ?: return@LaunchedEffect
            autoStartRequested = true
            val playbackId = resumeId ?: episodePlaybackId(streamId, episode)
            com.saab.tv.AppDiagnostics.event("Resume", "Episode Target Resolved", "id=$playbackId season=${episode.season} episode=${episode.episode}")
            val episodeTitle = episodeDisplayTitle(episode)
            pendingPlaybackId = playbackId
            pendingPlaybackType = type
            pendingPlaybackTitle = episodeTitle
            viewModel.loadStreams(type, episodeStreamId(streamId, episode), episodeTitle,
                sourceSelectionId = playbackId, fallbackStreamId = playbackId,
                autoSelectSource = true, rememberSourceSelection = rememberSourceSelection)
        } else {
            autoStartRequested = true
            pendingPlaybackId = streamId
            pendingPlaybackType = type
            pendingPlaybackTitle = readyMovie.name
            viewModel.loadStreams(type, streamId, readyMovie.name,
                autoSelectSource = true, rememberSourceSelection = rememberSourceSelection)
        }
    }
    LaunchedEffect(autoStartPlayback, autoStartRequested, state.isLoading, state.isLoadingStreams, sidebarState) {
        if (autoStartPlayback &&
            ((!state.isLoading && movie == null) ||
                (autoStartRequested && !state.isLoadingStreams && sidebarState is SidebarState.Sources))) {
            onAutoResumeNeedsSelection()
        }
    }

    // Track whether focus is inside the hero area (any button).
    // While hero has focus, suppress vertical pivot scrolling (viewport stays fixed,
    // just like the hero carousel on the home screen).
    var heroHasFocus by remember { mutableStateOf(false) }

    // Vertical pivot for smooth row-to-row scrolling (matches home screen SimpleLayout)
    val density = LocalDensity.current
    @OptIn(ExperimentalFoundationApi::class)
    val verticalPivot = remember(density) {
        val pivotPx = with(density) { 71.dp.toPx() }
        FocusPivotSpec(
            customOffset = pivotPx,
            skipScrollProvider = { heroHasFocus },
        )
    }

    // When focus returns to the hero from a row, animate back to the top
    LaunchedEffect(heroHasFocus) {
        if (heroHasFocus && (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)) {
            listState.scrollToItemUsingSaabMotion(0)
        }
    }

    LaunchedEffect(contentReady, routeResumeEpoch, trailerReturnToken, restoreTarget) {
        if (!contentReady) return@LaunchedEffect
        when (val target = restoreTarget) {
            DetailsFocusRestoreTarget.Pending -> Unit
            DetailsFocusRestoreTarget.Hero -> {
                firstButtonFocusRequester.requestFocusWhenAttached()
                restoreRowKey = null
                restoreItemKey = null
                restoreIndex = -1
            }
            is DetailsFocusRestoreTarget.RowItem -> {
                listState.scrollToItem(target.rowIndex)
                val restored = restoreFocusRequester.requestFocusWhenAttached(maxFrameAttempts = 48)
                if (!restored) firstButtonFocusRequester.requestFocusWhenAttached()
                restoreRowKey = null
                restoreItemKey = null
                restoreIndex = -1
            }
        }
    }

    LaunchedEffect(state.detailsLoadFailed) {
        if (state.detailsLoadFailed) firstButtonFocusRequester.requestFocusWhenAttached()
    }

    // Smooth content reveal: animate alpha from 0→1 when content becomes ready
    val contentAlpha by animateFloatAsState(
        targetValue = if (contentReady) 1f else 0f,
        animationSpec = SaabMotion.navigationRevealSpec(),
        label = "content_reveal"
    )

    Box(modifier = Modifier.fillMaxSize().background(bg)) {
        // Loading sweep — solid bg with subtle light sweep while data loads
        if (!contentReady || (autoStartPlayback && sidebarState is SidebarState.Closed)) {
            com.saab.tv.ui.components.DetailsLoadingSweep()
        }
        if (!state.isLoading && state.detailsLoadFailed && !contentReady) {
            Column(
                modifier = Modifier.align(Alignment.Center).widthIn(max = 520.dp).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.details_load_error_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = textColor
                )
                Text(
                    text = stringResource(R.string.details_load_error_message),
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor.copy(alpha = 0.8f)
                )
                com.saab.tv.ui.components.SetupButton(
                    text = stringResource(R.string.common_retry),
                    primary = true,
                    modifier = Modifier.focusRequester(firstButtonFocusRequester),
                    onClick = {
                        viewModel.loadDetails(type, id, addonBaseUrl, playbackOnly = autoStartPlayback)
                    }
                )
            }
        }
        if (showMovieContent && (!autoStartPlayback || sidebarState !is SidebarState.Closed)) {
            val currentMovie = requireNotNull(movie)
            // Verified content uses canonical fields; opaque provider-only IDs retain
            // their display metadata without being promoted into a false identity.
            val currentContent = content ?: currentMovie.toCanonicalProjection()
            val displayTitle = currentContent?.title ?: currentMovie.name
            var seriesWatched by remember(type, id, quickActionProfileId) { mutableStateOf(false) }
            var seriesMarkRequested by remember(type, id, quickActionProfileId) { mutableStateOf(false) }
            LaunchedEffect(type, id, quickActionProfileId) {
                if (type != "series" || quickActionProfileId <= 0) {
                    seriesWatched = false
                } else {
                    try {
                        val isWatched = quickActionViewModel.isTitleWatched(quickActionProfileId, currentMovie)
                        if (!seriesMarkRequested) seriesWatched = isWatched
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        seriesWatched = false
                    }
                }
            }
            val backdropCandidates = remember(
                state.tmdbEnrichment?.backdrop,
                currentContent?.artwork(ArtworkRole.BACKDROP)?.locator,
                currentMovie.background,
                currentContent?.artwork(ArtworkRole.POSTER)?.locator,
                currentMovie.poster
            ) {
                detailsBackdropCandidates(
                    tmdbBackdrop = state.tmdbEnrichment?.backdrop,
                    canonicalBackdrop = currentContent?.artwork(ArtworkRole.BACKDROP)?.locator,
                    providerBackdrop = currentMovie.background,
                    canonicalPoster = currentContent?.artwork(ArtworkRole.POSTER)?.locator,
                    providerPoster = currentMovie.poster
                )
            }
            var backdropCandidateIndex by remember(type, id) { mutableIntStateOf(0) }
            LaunchedEffect(type, id, backdropCandidates) { backdropCandidateIndex = 0 }
            val bgImage = backdropCandidates.getOrNull(backdropCandidateIndex)
            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            val density = LocalDensity.current
            val backdropDecodeSize = remember(
                configuration.screenWidthDp,
                configuration.screenHeightDp,
                density.density
            ) {
                artworkDecodeSizePx(
                    configuration.screenWidthDp.dp,
                    configuration.screenHeightDp.dp,
                    density
                )
            }
            val backdropRequest = remember(context, bgImage, backdropDecodeSize) {
                bgImage?.let {
                    artworkImageRequest(context, it, ArtworkCacheRole.Landscape, backdropDecodeSize)
                }
            }
            Box(modifier = Modifier.alpha(contentAlpha)) {
            com.saab.tv.ui.components.RetainedArtworkImage(
                model = backdropRequest,
                contentDescription = "",
                contentIdentityKey = state.canonicalIdentityKey ?: "$type:$id",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(0.6f),
                onLoadError = {
                    nextDetailsBackdropIndex(backdropCandidateIndex, backdropCandidates.size)
                        ?.let { backdropCandidateIndex = it }
                },
                unavailable = {}
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0.0f to bg,
                                0.1f to bg.copy(alpha = 0.95f),
                                0.2f to bg.copy(alpha = 0.85f),
                                0.3f to bg.copy(alpha = 0.72f),
                                0.4f to bg.copy(alpha = 0.58f),
                                0.55f to bg.copy(alpha = 0.38f),
                                0.7f to bg.copy(alpha = 0.20f),
                                0.85f to bg.copy(alpha = 0.08f),
                                1.0f to Color.Transparent
                            ),
                            startX = 0f,
                            endX = 1500f
                        )
                    )
            )
            com.saab.tv.ui.components.NoiseOverlay()

            val enrichment = state.tmdbEnrichment
            val hasEnrichment = enrichment != null

            @OptIn(ExperimentalFoundationApi::class)
            CompositionLocalProvider(
                LocalBringIntoViewSpec provides verticalPivot,
                com.saab.tv.ui.components.LocalPosterFocusReturn provides { quickActionReturnFocus = it }
            ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 0.dp, bottom = 0.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp)
            ) {
            // ── Hero item (fixed height, scroll-suppressed) ──
            item(key = "hero") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(500.dp)
                    .padding(start = 48.dp, end = 48.dp, top = 60.dp, bottom = 24.dp)
                    .onFocusChanged { heroHasFocus = it.hasFocus },
                verticalArrangement = Arrangement.Bottom
            ) {
                val titleStyle = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 32.sp,
                    lineHeight = 34.sp
                )

                val titleLogo = currentContent?.artwork(ArtworkRole.LOGO)?.locator ?: currentMovie.logo
                if (!titleLogo.isNullOrEmpty()) {
                    com.saab.tv.ui.components.RetainedArtworkImage(
                        model = titleLogo,
                        contentDescription = displayTitle,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.BottomStart,
                        modifier = Modifier
                            .widthIn(max = 450.dp)
                            .heightIn(max = 90.dp),
                        contentIdentityKey = state.canonicalIdentityKey ?: "$type:$id",
                        unavailable = {
                            Text(
                                text = displayTitle,
                                style = titleStyle,
                                color = textColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                softWrap = true
                            )
                        }
                    )
                } else {
                    Text(
                        text = displayTitle,
                        style = titleStyle,
                        color = textColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = true
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                val localizedTypeLabel = com.saab.tv.ui.components.localizedTitleType(
                    currentContent?.kind?.keyPart ?: currentMovie.type
                )
                val genreLabel = (currentContent?.genres ?: currentMovie.genres.orEmpty())
                    .firstOrNull()
                    ?.replaceFirstChar { it.uppercase() }
                    ?: stringResource(R.string.sort_filter_unknown)
                val yearLabel = currentContent?.year ?: extractPrimaryYear(currentMovie.releaseInfo)

                val ageRating = enrichment?.ageRating
                val runtimeMin = enrichment?.runtimeMinutes
                    ?: currentContent?.runtimeEstimateMillis?.div(60_000L)?.toInt()
                    ?: currentMovie.runtime?.filter { it.isDigit() }?.toIntOrNull()

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = localizedTypeLabel,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = textColor.copy(alpha = 0.95f)
                    )
                    MetaDot(textColor)
                    Text(
                        text = genreLabel,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = textColor.copy(alpha = 0.95f)
                    )
                    MetaDot(textColor)
                    Text(
                        text = yearLabel,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = textColor.copy(alpha = 0.95f)
                    )

                    ageRating?.let {
                        MetaDot(textColor)
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = textColor.copy(alpha = 0.7f),
                            modifier = Modifier
                                .border(1.dp, textColor.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }

                    runtimeMin?.let {
                        MetaDot(textColor)
                        val display = com.saab.tv.ui.components.localizedRuntimeLabel(it)
                        Text(
                            text = display,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                            color = textColor.copy(alpha = 0.95f)
                        )
                    }

                    (currentContent?.rating
                        ?.takeIf { it.source == "imdb" && it.scale == "0-10" }
                        ?.value?.toString() ?: currentMovie.imdbRating?.takeIf(String::isNotBlank))
                        ?.let { rating ->
                        Spacer(modifier = Modifier.width(4.dp))
                        ImdbBadge()
                        Text(
                            text = rating,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                            color = textColor.copy(alpha = 0.95f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = currentContent?.synopsis ?: currentMovie.description.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor,
                    maxLines = 5
                )
                Spacer(modifier = Modifier.height(32.dp))

                val firstEpisode = remember(currentMovie.id, currentMovie.videos) {
                    findFirstEpisode(currentMovie.videos)
                }
                val hintedResumePlaybackId = remember(type, id, resumePlaybackHint, state.episodeProgressMap, state.isMovieWatched) {
                    when (type) {
                        "series" -> com.saab.tv.ui.home.ContinueResumePolicy.playbackId(
                            seriesId = id,
                            continueHint = resumePlaybackHint,
                            hintIsWatched = resumePlaybackHint?.let { candidate ->
                                parseSeasonEpisodeFromPlaybackId(candidate)?.let { (season, episode) ->
                                    state.episodeProgressMap["S${season}:E${episode}"]?.watched == true
                                } ?: false
                            } ?: false,
                            calculatedResumeId = null
                        )
                        else -> resumePlaybackHint?.takeIf { it == id && !state.isMovieWatched }
                    }
                }
                val resumePlaybackId = hintedResumePlaybackId ?: state.resumePlaybackId
                val resumeEpisode = remember(currentMovie.id, currentMovie.videos, resumePlaybackId) {
                    if (type == "series") {
                        resolveEpisodeForPlaybackId(currentMovie.id, currentMovie.videos, resumePlaybackId)
                    } else {
                        null
                    }
                }
                val parsedResumeSeasonEpisode = remember(resumePlaybackId) {
                    parseSeasonEpisodeFromPlaybackId(resumePlaybackId)
                }
                val firstEpisodeSeason = firstEpisode?.season?.takeIf { it > 0 } ?: 1
                val firstEpisodeNumber = firstEpisode?.episode?.takeIf { it > 0 } ?: 1

                // No onNavigateDown — Compose's default DOWN navigation
                // handles hero→row transitions reliably after disposal/recomposition.

                val isInWatchlist by viewModel.isInWatchlist.collectAsStateWithLifecycle()

                fun openSourcePicker() {
                    if (type == "series") {
                        val episode = resumeEpisode ?: firstEpisode ?: return
                        val selectionId = resumePlaybackId ?: episodePlaybackId(streamId, episode)
                        val episodeStreamId = episodeStreamId(streamId, episode)
                        val episodeTitle = when {
                            resumePlaybackId != null && resumeEpisode != null -> episodeDisplayTitle(resumeEpisode)
                            resumePlaybackId != null && parsedResumeSeasonEpisode != null ->
                                "S${parsedResumeSeasonEpisode.first}:E${parsedResumeSeasonEpisode.second} - ${currentMovie.name}"
                            resumePlaybackId != null -> currentMovie.name
                            else -> episodeDisplayTitle(episode)
                        }
                        pendingPlaybackId = selectionId
                        pendingPlaybackType = type
                        pendingPlaybackTitle = episodeTitle
                        viewModel.loadStreams(
                            type,
                            episodeStreamId,
                            episodeTitle,
                            sourceSelectionId = selectionId,
                            fallbackStreamId = selectionId,
                            forceSourcePicker = true,
                            autoSelectSource = false,
                            rememberSourceSelection = false
                        )
                    } else {
                        pendingPlaybackId = streamId
                        pendingPlaybackType = type
                        pendingPlaybackTitle = currentMovie.name
                        viewModel.loadStreams(
                            type,
                            streamId,
                            currentMovie.name,
                            forceSourcePicker = true,
                            autoSelectSource = false,
                            rememberSourceSelection = false
                        )
                    }
                }

                if (type == "series") {
                    val resumeSeason = resumeEpisode?.season?.takeIf { it > 0 } ?: parsedResumeSeasonEpisode?.first
                    val resumeNumber = resumeEpisode?.episode?.takeIf { it > 0 } ?: parsedResumeSeasonEpisode?.second
                    val playNextEpisode = state.resumeIsNextEpisode && hintedResumePlaybackId == null
                    val playLabel = when {
                        resumePlaybackId == null -> stringResource(
                            R.string.details_action_play_episode,
                            firstEpisodeSeason,
                            firstEpisodeNumber
                        )
                        else -> when (seriesResumeActionKind(resumeSeason, resumeNumber, playNextEpisode)) {
                            SeriesResumeActionKind.PLAY_NEXT_EPISODE -> stringResource(
                                R.string.details_action_play_next_episode_numbered,
                                requireNotNull(resumeSeason),
                                requireNotNull(resumeNumber)
                            )
                            SeriesResumeActionKind.PLAY_NEXT -> stringResource(R.string.details_action_play_next_episode)
                            SeriesResumeActionKind.RESUME_EPISODE -> stringResource(
                                R.string.details_action_resume_episode_numbered,
                                requireNotNull(resumeSeason),
                                requireNotNull(resumeNumber)
                            )
                            SeriesResumeActionKind.RESUME -> stringResource(R.string.details_action_resume)
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.onPreviewKeyEvent { event ->
                            if (com.saab.tv.ui.trailer.InlineTrailerAnchor.session != null) return@onPreviewKeyEvent false
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                // Redirect focus to the Play button before the system
                                // resolves the Down target, so the below-hero content
                                // always receives focus from the same horizontal position.
                                firstButtonFocusRequester.requestFocusSafely()
                            }
                            false
                        }
                    ) {
                        com.saab.tv.ui.components.DetailActionButton(
                            label = playLabel,
                            icon = Icons.Default.PlayArrow,
                            modifier = Modifier.focusRequester(firstButtonFocusRequester),
                            onClick = {
                                if (!state.resumeStateReady) return@DetailActionButton
                                val ep = resumeEpisode ?: firstEpisode ?: return@DetailActionButton
                                val trackId = resumePlaybackId ?: episodePlaybackId(streamId, ep)
                                val epStreamId = episodeStreamId(streamId, ep)
                                val epTitle = when {
                                    resumePlaybackId != null && resumeEpisode != null -> episodeDisplayTitle(resumeEpisode)
                                    resumePlaybackId != null && parsedResumeSeasonEpisode != null ->
                                        "S${parsedResumeSeasonEpisode.first}:E${parsedResumeSeasonEpisode.second} - ${currentMovie.name}"
                                    resumePlaybackId != null -> currentMovie.name
                                    else -> episodeDisplayTitle(ep)
                                }
                                pendingPlaybackId = trackId
                                pendingPlaybackType = type
                                pendingPlaybackTitle = epTitle
                                viewModel.loadStreams(type, epStreamId, epTitle, sourceSelectionId = trackId,
                                    fallbackStreamId = trackId, autoSelectSource = autoSelectSource,
                                    rememberSourceSelection = rememberSourceSelection)
                            }
                        )

                        com.saab.tv.ui.components.DetailActionButton(
                            label = stringResource(R.string.details_action_sources),
                            icon = Icons.Default.SwapHoriz,
                            onClick = ::openSourcePicker
                        )

                        com.saab.tv.ui.components.DetailActionButton(
                            label = stringResource(R.string.details_action_episodes),
                            icon = Icons.AutoMirrored.Filled.List,
                            modifier = Modifier.focusRequester(episodesButtonFocusRequester),
                            onClick = { viewModel.openEpisodes() }
                        )

                        val seriesTrailer = state.trailer
                        if (seriesTrailer != null) {
                            com.saab.tv.ui.components.DetailActionButton(
                                label = stringResource(R.string.details_action_trailer),
                                icon = Icons.Default.Videocam,
                                onClick = { manualTrailerLauncher?.invoke(currentMovie, seriesTrailer.key to seriesTrailer.name, { viewModel.openEpisodes() }, onStartWatching)
                                    ?: onTrailerClick(seriesTrailer.key, seriesTrailer.name) }
                            )
                        }

                        com.saab.tv.ui.components.DetailActionButton(
                            label = stringResource(
                                if (seriesWatched) R.string.episode_watched else R.string.episode_mark_watched
                            ),
                            icon = if (seriesWatched) Icons.Default.Check else Icons.Default.Add,
                            isActive = seriesWatched,
                            onClick = {
                                if (!seriesWatched && quickActionProfileId > 0) {
                                    seriesMarkRequested = true
                                    seriesWatched = true
                                    quickActionViewModel.markTitleWatched(quickActionProfileId, currentMovie)
                                }
                            }
                        )

                        com.saab.tv.ui.components.DetailActionButton(
                            label = stringResource(
                                if (isInWatchlist) R.string.details_action_watchlisted else R.string.details_action_add_watchlist
                            ),
                            icon = if (isInWatchlist) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            isActive = isInWatchlist,
                            onClick = { viewModel.toggleWatchlist() }
                        )

                        if (resumePlaybackId != null) {
                            com.saab.tv.ui.components.DetailActionButton(
                                label = stringResource(R.string.details_action_clear_progress),
                                icon = Icons.Default.Close,
                                onClick = { showClearProgressDialog = true }
                            )
                        }
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.onPreviewKeyEvent { event ->
                            if (com.saab.tv.ui.trailer.InlineTrailerAnchor.session != null) return@onPreviewKeyEvent false
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                firstButtonFocusRequester.requestFocusSafely()
                            }
                            false
                        }
                    ) {
                        com.saab.tv.ui.components.DetailActionButton(
                            label = stringResource(
                                if (resumePlaybackId != null) R.string.details_action_resume else R.string.details_action_play_movie
                            ),
                            icon = Icons.Default.PlayArrow,
                            modifier = Modifier.focusRequester(firstButtonFocusRequester),
                            onClick = {
                                pendingPlaybackId = streamId
                                pendingPlaybackType = type
                                pendingPlaybackTitle = currentMovie.name
                                viewModel.loadStreams(type, streamId, currentMovie.name, autoSelectSource = autoSelectSource, rememberSourceSelection = rememberSourceSelection)
                            }
                        )

                        com.saab.tv.ui.components.DetailActionButton(
                            label = stringResource(R.string.details_action_sources),
                            icon = Icons.Default.SwapHoriz,
                            onClick = ::openSourcePicker
                        )

                        val movieTrailer = state.trailer
                        if (movieTrailer != null) {
                            com.saab.tv.ui.components.DetailActionButton(
                                label = stringResource(R.string.details_action_trailer),
                                icon = Icons.Default.Videocam,
                                onClick = { manualTrailerLauncher?.invoke(currentMovie, movieTrailer.key to movieTrailer.name, null, onStartWatching)
                                    ?: onTrailerClick(movieTrailer.key, movieTrailer.name) }
                            )
                        }

                        com.saab.tv.ui.components.DetailActionButton(
                            label = stringResource(
                                if (state.isMovieWatched) R.string.episode_watched else R.string.episode_mark_watched
                            ),
                            icon = if (state.isMovieWatched) Icons.Default.Check else Icons.Default.Add,
                            isActive = state.isMovieWatched,
                            onClick = { viewModel.toggleMovieWatched() }
                        )

                        com.saab.tv.ui.components.DetailActionButton(
                            label = stringResource(
                                if (isInWatchlist) R.string.details_action_watchlisted else R.string.details_action_add_watchlist
                            ),
                            icon = if (isInWatchlist) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            isActive = isInWatchlist,
                            onClick = { viewModel.toggleWatchlist() }
                        )

                        if (resumePlaybackId != null) {
                            com.saab.tv.ui.components.DetailActionButton(
                                label = stringResource(R.string.details_action_clear_progress),
                                icon = Icons.Default.Close,
                                onClick = { showClearProgressDialog = true }
                            )
                        }

                        // Works around a Compose focus-tree bug where requestFocus()
                        // silently fails when a container has a single focusable child.
                        if (resumePlaybackId == null && !isInWatchlist) {
                            Spacer(modifier = Modifier
                                .size(0.dp)
                                .onFocusChanged {
                                    if (it.isFocused) firstButtonFocusRequester.requestFocusSafely()
                                }
                                .focusable()
                            )
                        }
                    }
                }
            }
            } // hero item

            // ── TMDB Enrichment Sections ──
            if (hasEnrichment || state.tmdbRecommendations.isNotEmpty() || state.tmdbCollection.isNotEmpty()) {
                val castMembers = enrichment?.castMembers.orEmpty()
                val directorMembers = enrichment?.directorMembers.orEmpty()
                val writerMembers = enrichment?.writerMembers.orEmpty()
                val tmdbRecommendations = state.tmdbRecommendations
                val tmdbCollection = state.tmdbCollection

                val leadingCrew = directorMembers + writerMembers
                // Modifier applied to the first TMDB section so Up always returns to the Play button
                var firstSectionClaimed = false
                fun firstSectionModifier(): Modifier {
                    if (firstSectionClaimed) return Modifier
                    firstSectionClaimed = true
                    return Modifier.onPreviewKeyEvent { event ->
                    if (com.saab.tv.ui.trailer.InlineTrailerAnchor.session != null) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                            firstButtonFocusRequester.requestFocusSafely()
                            true
                        } else false
                    }
                }

                if (castMembers.isNotEmpty() || leadingCrew.isNotEmpty()) {
                    item(key = "tmdb_cast") {
                        val titleRes = if (leadingCrew.isNotEmpty() && castMembers.isNotEmpty()) R.string.details_cast_and_crew
                            else if (leadingCrew.isNotEmpty()) R.string.details_crew
                            else R.string.details_cast
                        Column(modifier = firstSectionModifier().padding(top = 28.dp)) {
                            SectionHeader(stringResource(titleRes), textColor, Modifier.padding(start = 48.dp))
                            Spacer(modifier = Modifier.height(10.dp))
                            CastRow(
                                leadingCrew, castMembers, textColor,
                                onPersonClick = { personId, personName, rowIndex, itemKey ->
                                    restoreRowKey = "tmdb_cast"
                                    restoreIndex = rowIndex
                                    restoreItemKey = itemKey
                                    onNavigateToCastDetail(personId, personName)
                                },
                                restoreIndex = (restoreTarget as? DetailsFocusRestoreTarget.RowItem)
                                    ?.takeIf { restoreRowKey == "tmdb_cast" }?.itemIndex ?: -1,
                                restoreFocusRequester = if (restoreRowKey == "tmdb_cast") restoreFocusRequester else null
                            )
                        }
                    }
                }



                if (tmdbRecommendations.isNotEmpty()) {
                    item(key = "tmdb_recs") {
                        Column(modifier = Modifier.padding(top = 28.dp)) {
                            SectionHeader(stringResource(R.string.details_more_like_this), textColor, Modifier.padding(start = 48.dp))
                            Spacer(modifier = Modifier.height(10.dp))
                                            RecommendationRow(
                                        tmdbRecommendations, accentColor,
                                        rowKey = "tmdb_recs",
                                        profileId = quickActionProfileId,
                                        watchedViewModel = quickActionViewModel,
                            onItemClick = { navType, navId, rowKey, itemKey, index ->
                                restoreRowKey = rowKey
                                restoreIndex = index
                                restoreItemKey = itemKey
                                onNavigateToDetails(navType, navId)
                            },
                            onLongClick = { item, bounds -> quickActionTarget = item to bounds },
                            restoreIndex = (restoreTarget as? DetailsFocusRestoreTarget.RowItem)
                                ?.takeIf { restoreRowKey == "tmdb_recs" }?.itemIndex ?: -1,
                                        restoreFocusRequester = if (restoreRowKey == "tmdb_recs") restoreFocusRequester else null
                                    )
                        }
                    }
                }

                val collectionName = state.tmdbCollectionName
                if (tmdbCollection.isNotEmpty() && collectionName != null) {
                    item(key = "tmdb_collection") {
                        Column(modifier = Modifier.padding(top = 28.dp)) {
                            SectionHeader(collectionName, textColor, Modifier.padding(start = 48.dp))
                            Spacer(modifier = Modifier.height(10.dp))
                                            RecommendationRow(
                                            tmdbCollection, accentColor,
                                            rowKey = "tmdb_collection",
                                            profileId = quickActionProfileId,
                                            watchedViewModel = quickActionViewModel,
                                            onItemClick = { navType, navId, rowKey, itemKey, index ->
                                                restoreRowKey = rowKey
                                                restoreIndex = index
                                                restoreItemKey = itemKey
                                                onNavigateToDetails(navType, navId)
                                            },
                                            onLongClick = { item, bounds -> quickActionTarget = item to bounds },
                                            restoreIndex = (restoreTarget as? DetailsFocusRestoreTarget.RowItem)
                                                ?.takeIf { restoreRowKey == "tmdb_collection" }?.itemIndex ?: -1,
                                            restoreFocusRequester = if (restoreRowKey == "tmdb_collection") restoreFocusRequester else null
                                        )
                        }
                    }
                }

                item(key = "tmdb_spacer") { Spacer(modifier = Modifier.height(48.dp)) }
            }

            } // LazyColumn
            } // CompositionLocalProvider verticalPivot
            } // contentAlpha Box
        }

        GlassSidebar(
            state = sidebarState,
            isLoadingSources = state.isLoadingStreams,
            canSelectPartialSources = state.canSelectPartialSources,
            sourceLanguagePreferences = state.sourceLanguagePreferences,
            currentEpisodeId = state.lastPlayedEpisodeId ?: state.resumePlaybackId,
            episodeProgressMap = state.episodeProgressMap,
            episodeEnrichmentMap = state.episodeEnrichmentMap,
            onToggleWatched = { episode -> viewModel.toggleEpisodeWatched(episode) },
            onDismiss = { viewModel.closeSidebar() },
            onBack = { viewModel.goBackInSidebar() },
            onEpisodeSelected = { episode ->
                val trackId = episodePlaybackId(streamId, episode)
                val epStreamId = episodeStreamId(streamId, episode)
                val epTitle = episodeDisplayTitle(episode)
                com.saab.tv.AppDiagnostics.event("Episodes", "Details Selection",
                    "season=${episode.season} episode=${episode.episode} nativeIdMatches=${epStreamId == trackId}")
                pendingPlaybackId = trackId
                pendingPlaybackType = type
                pendingPlaybackTitle = epTitle
                viewModel.loadStreams(type, epStreamId, epTitle, sourceSelectionId = trackId,
                    fallbackStreamId = trackId, autoSelectSource = autoSelectSource,
                    rememberSourceSelection = rememberSourceSelection)
            },
            onSourceSelected = { stream ->
                viewModel.closeSidebar()
                val playbackId = pendingPlaybackId.ifBlank { movie?.id ?: id }
                val urlToPlay = resolvePlayableUrl(stream)
                if (!urlToPlay.isNullOrEmpty()) {
                    val playbackType = pendingPlaybackType.ifBlank { movie?.type ?: type }
                    val playbackTitle = pendingPlaybackTitle.ifBlank { movie?.name ?: "" }
                    onPlayClick(
                        urlToPlay,
                        playbackId,
                        playbackType,
                        playbackTitle,
                        movie?.name ?: "",
                        movie?.logo ?: "",
                        stream,
                        addonSubtitles,
                        availableStreams,
                        movie?.videos ?: emptyList(),
                        id,
                        type,
                        detailPoster
                    )
                }
            },
            onAudioLanguageSelected = viewModel::promoteAudioLanguagePreference
        )

        quickActionTarget?.let { (item, bounds) ->
            com.saab.tv.ui.home.CatalogQuickActionsPopup(
                item = item,
                bounds = bounds,
                profileId = quickActionProfileId,
                viewModel = quickActionViewModel,
                onTrailerClick = onTrailerClick,
                onDismiss = {
                    quickActionTarget = null
                    quickActionScope.launch {
                        if (quickActionReturnFocus?.requestFocusWhenAttached() != true) {
                            firstButtonFocusRequester.requestFocusSafely()
                        }
                    }
                }
            )
        }

        // Centered loading spinner for auto-resolve paths (remembered source, auto-select)
        if ((state.isLoadingStreams && sidebarState is SidebarState.Closed) || isTrailerLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = accentColor)
            }
        }

        // Clear progress confirmation dialog
        if (showClearProgressDialog) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { showClearProgressDialog = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 400.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(bg)
                            .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            stringResource(R.string.details_clear_progress_title),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = textColor
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.details_clear_progress_message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor.copy(0.7f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(Modifier.height(20.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                        ) {
                            val cancelFocusRequester = remember { FocusRequester() }

                            DialogButton(
                                text = stringResource(R.string.common_cancel),
                                modifier = Modifier.width(120.dp).focusRequester(cancelFocusRequester),
                                onClick = { showClearProgressDialog = false }
                            )
                            DialogButton(
                                text = stringResource(R.string.details_clear_progress_action),
                                isDestructive = true,
                                modifier = Modifier.width(120.dp),
                                onClick = {
                                    showClearProgressDialog = false
                                    viewModel.confirmClearProgress()
                                }
                            )

                            LaunchedEffect(Unit) {
                                kotlinx.coroutines.delay(200)
                                cancelFocusRequester.requestFocusSafely()
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

/**
 * TV-style icon button that expands to reveal a text label on focus.
 * Mirrors the TopNavItem bubble-expand pattern.
 */
@Composable
private fun DialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDestructive: Boolean = false
) {
    com.saab.tv.ui.components.SetupButton(text, onClick, modifier.height(50.dp), destructive = isDestructive)
}

@Composable
private fun MetaDot(textColor: Color) {
    Text(
        text = ".",
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        color = textColor.copy(alpha = 0.55f)
    )
}

@Composable
private fun ImdbBadge() {
    Image(
        painter = painterResource(id = R.drawable.imdb_logo),
        contentDescription = "IMDb",
        modifier = Modifier.height(20.dp)
    )
}

// ── TMDB Section Components ──

@Composable
private fun SectionHeader(title: String, textColor: Color, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = textColor.copy(alpha = 0.9f),
        modifier = modifier
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CastRow(
    leadingCrew: List<TmdbCastInfo>,
    cast: List<TmdbCastInfo>,
    textColor: Color,
    onPersonClick: (personId: Int, personName: String, rowIndex: Int, itemKey: String) -> Unit = { _, _, _, _ -> },
    restoreIndex: Int = -1,
    restoreFocusRequester: FocusRequester? = null
) {
    val authorizeNavigationHandoff = LocalNavigationFocusHandoff.current
    val rowState = rememberLazyListState()
    LaunchedEffect(restoreIndex, leadingCrew, cast) {
        val hasDivider = leadingCrew.isNotEmpty() && cast.isNotEmpty()
        val rowItemCount = leadingCrew.size + (if (hasDivider) 1 else 0) + cast.take(20).size
        if (restoreIndex in 0 until rowItemCount) rowState.scrollToItem(restoreIndex)
    }
    val repeatGate = remember { DpadRepeatGate(horizontalRepeatIntervalMs = 150L) }
    val startPad = 48.dp
    val rowHasCardFocus = remember { mutableStateOf(false) }
    var focusedIndex by remember(restoreIndex) { mutableStateOf(restoreIndex.coerceAtLeast(0)) }

    com.saab.tv.ui.components.FocusShelf(
        state = rowState,
        focused = rowHasCardFocus,
        width = 96.dp,
        height = 110.dp,
        startPadding = startPad,
        focusedItemIndex = focusedIndex,
        tag = "details-cast-focus-indicator",
        modifier = Modifier.onFocusChanged { if (!it.hasFocus) rowHasCardFocus.value = false },
        viewportHeight = 110.dp,
        indicatorAlignment = Alignment.TopStart,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        contentPadding = PaddingValues(start = startPad),
        rowModifier = Modifier.fillMaxWidth().height(110.dp).focusGroup()
    ) {
            val hasDivider = leadingCrew.isNotEmpty() && cast.isNotEmpty()
            val castOffset = leadingCrew.size + if (hasDivider) 1 else 0

            // Leading crew (directors, writers)
            itemsIndexed(leadingCrew, key = { i, it -> "crew_${it.tmdbId ?: i}" }) { index, member ->
                Box(modifier = Modifier.onPreviewKeyEvent {
                    if (repeatGate.shouldConsume(it)) return@onPreviewKeyEvent true
                    if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && index == 0) {
                        handoffNavigationFocus(it, authorizeNavigationHandoff)
                    } else false
                }) {
                    CastCard(
                        member, textColor,
                        modifier = if (restoreFocusRequester != null && index == restoreIndex) Modifier.focusRequester(restoreFocusRequester) else Modifier,
                        onFocused = { focusedIndex = index; rowHasCardFocus.value = true }
                    ) {
                        member.tmdbId?.let { id ->
                            onPersonClick(id, member.name, index, "crew_${member.tmdbId ?: index}")
                        }
                    }
                }
            }

            // Vertical divider between crew and cast
            if (hasDivider) {
                item(key = "cast_divider") {
                    Box(
                        modifier = Modifier.height(110.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(72.dp)
                                .background(Color.White.copy(alpha = 0.3f))
                        )
                    }
                }
            }

            // Regular cast
            itemsIndexed(cast.take(20), key = { i, it -> "cast_${it.tmdbId ?: i}" }) { index, member ->
                val isFirstOverall = leadingCrew.isEmpty() && index == 0
                val flatIndex = castOffset + index
                Box(modifier = Modifier.onPreviewKeyEvent {
                    if (repeatGate.shouldConsume(it)) return@onPreviewKeyEvent true
                    if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && isFirstOverall) {
                        handoffNavigationFocus(it, authorizeNavigationHandoff)
                    } else false
                }) {
                    CastCard(
                        member, textColor,
                        modifier = if (restoreFocusRequester != null && flatIndex == restoreIndex) Modifier.focusRequester(restoreFocusRequester) else Modifier,
                        onFocused = { focusedIndex = flatIndex; rowHasCardFocus.value = true }
                    ) {
                        member.tmdbId?.let { id ->
                            onPersonClick(id, member.name, flatIndex, "cast_${member.tmdbId ?: index}")
                        }
                    }
                }
            }
    }
}


@Composable
internal fun CastCard(
    member: TmdbCastInfo,
    textColor: Color,
    modifier: Modifier = Modifier,
    onFocused: (() -> Unit)? = null,
    onClick: () -> Unit = {}
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    // Wider focusable area (96dp) with 80dp visual content centered within.
    // The extra width replaces LazyRow spacing and shifts focus centers so
    // Compose's geometric search picks the correct item when navigating
    // between rows with different card widths (cast 80dp vs trailer 190dp).
    Box(
        modifier = modifier
            .width(96.dp)
            .height(110.dp)
            .cardFocusSound()
            .onFocusChanged { if (it.isFocused) onFocused?.invoke() }
            .clickable(interactionSource = interactionSource, indication = null) { onClick() },
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(80.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(0.08f))
            ) {
                Text(
                    text = com.saab.tv.ui.components.initialsForPerson(member.name),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (isFocused) Color.White else textColor.copy(alpha = 0.72f),
                    modifier = Modifier.align(Alignment.Center)
                )
                if (member.photo != null) {
                    AsyncImage(
                        model = member.photo,
                        contentDescription = member.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = member.name,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = if (isFocused) Color.White else textColor.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            member.character?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = textColor.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StudioRow(
    studios: List<TmdbCompanyInfo>,
    textColor: Color,
    accentColor: Color,
    onStudioClick: (tmdbId: Int, name: String) -> Unit = { _, _ -> },
    restoreIndex: Int = -1,
    restoreFocusRequester: FocusRequester? = null
) {
    val authorizeNavigationHandoff = LocalNavigationFocusHandoff.current
    val rowState = rememberLazyListState()
    val repeatGate = remember { DpadRepeatGate(horizontalRepeatIntervalMs = 150L) }
    val startPad = 48.dp
    val rowHasCardFocus = remember { mutableStateOf(false) }
    var focusedIndex by remember(restoreIndex) { mutableStateOf(restoreIndex.coerceAtLeast(0)) }

    com.saab.tv.ui.components.FocusShelf(
        state = rowState,
        focused = rowHasCardFocus,
        width = 140.dp,
        height = 56.dp,
        startPadding = startPad,
        focusedItemIndex = focusedIndex,
        tag = "details-studio-focus-indicator",
        modifier = Modifier.onFocusChanged { if (!it.hasFocus) rowHasCardFocus.value = false },
        viewportHeight = 56.dp,
        indicatorAlignment = Alignment.TopStart,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(start = startPad),
        rowModifier = Modifier.fillMaxWidth().height(56.dp).focusGroup()
    ) {
            itemsIndexed(studios, key = { _, it -> "${it.tmdbId}:${it.name}" }) { index, studio ->
                Box(modifier = Modifier.onPreviewKeyEvent {
                    if (repeatGate.shouldConsume(it)) return@onPreviewKeyEvent true
                    if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && index == 0) {
                        handoffNavigationFocus(it, authorizeNavigationHandoff)
                    } else false
                }) {
                    StudioChip(
                        studio, textColor, accentColor,
                        modifier = if (restoreFocusRequester != null && index == restoreIndex) Modifier.focusRequester(restoreFocusRequester) else Modifier,
                        onFocused = { focusedIndex = index; rowHasCardFocus.value = true }
                    ) {
                        studio.tmdbId?.let { id -> onStudioClick(id, studio.name) }
                    }
                }
            }
    }
}

@Composable
internal fun StudioChip(
    studio: TmdbCompanyInfo,
    textColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onFocused: (() -> Unit)? = null,
    onClick: () -> Unit = {}
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .width(140.dp)
            .height(56.dp)
            .cardFocusSound()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) accentColor else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged { if (it.isFocused) onFocused?.invoke() }
            .clickable(interactionSource = interactionSource, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (studio.logo != null) {
            AsyncImage(
                model = studio.logo,
                contentDescription = studio.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(
                text = studio.name,
                style = MaterialTheme.typography.labelMedium,
                color = if (isFocused) accentColor else Color.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RecommendationRow(
    items: List<TitleCardProjection>,
    accentColor: Color,
    rowKey: String = "",
    profileId: Int,
    watchedViewModel: com.saab.tv.ui.home.HomeViewModel,
    onItemClick: (type: String, id: String, rowKey: String, itemKey: String, index: Int) -> Unit = { _, _, _, _, _ -> },
    onLongClick: (com.saab.tv.data.model.stremio.MetaItem, Rect) -> Unit = { _, _ -> },
    restoreIndex: Int = -1,
    restoreFocusRequester: FocusRequester? = null,
    artworkPrefetchDispatcher: com.saab.tv.ui.components.ArtworkPrefetchDispatcher? = null
) {
    val authorizeNavigationHandoff = LocalNavigationFocusHandoff.current
    val enrichedItems by androidx.compose.runtime.remember(watchedViewModel) {
        watchedViewModel.state.map { it.enrichedMeta }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = emptyMap())
    val rowState = rememberLazyListState()
    LaunchedEffect(restoreIndex, items) {
        if (restoreIndex in items.indices) rowState.scrollToItem(restoreIndex)
    }
    val context = LocalContext.current
    val artworkPrefetcher = remember(context, artworkPrefetchDispatcher) {
        artworkPrefetchDispatcher ?: com.saab.tv.ui.components.ArtworkPrefetchDispatcher.forCoil(context)
    }
    val artworkItems = remember(items) { items.map { it.asMetaItem() } }
    val landscapeCards = com.saab.tv.ui.components.LocalTitleCardShape.current == "landscape"
    val cardWidth = if (landscapeCards) 190.dp else 120.dp
    val focusFrame = com.saab.tv.ui.components.focusFrameSize(cardWidth, landscapeCards)
    val rowHasCardFocus = remember(rowKey) { mutableStateOf(false) }
    var focusedIndex by remember(rowKey) { mutableStateOf(restoreIndex) }
    val repeatGate = remember { DpadRepeatGate(horizontalRepeatIntervalMs = 150L) }
    val startPad = 48.dp

    com.saab.tv.ui.components.ArtworkPrefetchEffect(
        items = artworkItems,
        enrichedItems = enrichedItems,
        listState = rowState,
        focusedItemKey = artworkItems.getOrNull(focusedIndex)?.canonicalPresentationKey(),
        landscapeCards = landscapeCards,
        dispatcher = artworkPrefetcher,
        posterSize = if (landscapeCards) null else androidx.compose.ui.unit.DpSize(120.dp, 180.dp),
        onPrepareItem = { if (landscapeCards) watchedViewModel.ensureTmdbEnrichment(it) }
    )

    Column(
        modifier = Modifier.fillMaxWidth().onFocusChanged {
            if (!it.hasFocus) rowHasCardFocus.value = false
        }
    ) {
        com.saab.tv.ui.components.FocusShelf(
            state = rowState,
            focused = rowHasCardFocus,
            width = focusFrame.width,
            height = focusFrame.height,
            startPadding = startPad,
            focusedItemIndex = focusedIndex,
            tag = "details-recommendation-focus-indicator",
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
                    itemsIndexed(items, key = { _, it -> it.presentationKey }) { index, item ->
                        Box(modifier = Modifier.onPreviewKeyEvent {
                            if (repeatGate.shouldConsume(it)) return@onPreviewKeyEvent true
                            if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && index == 0) {
                                handoffNavigationFocus(it, authorizeNavigationHandoff)
                            } else false
                        }) {
                            RecommendationCard(
                                item, accentColor, profileId, watchedViewModel, enrichedItems,
                                modifier = if (restoreFocusRequester != null && index == restoreIndex) Modifier.focusRequester(restoreFocusRequester) else Modifier,
                                onFocused = {
                                    focusedIndex = index
                                    rowHasCardFocus.value = true
                                },
                                onClick = {
                                    onItemClick(item.kind.keyPart, item.sourceId, rowKey, item.presentationKey, index)
                                },
                                onLongClick = onLongClick
                            )
                        }
                    }
        }
    }
}

@Composable
private fun RecommendationCard(
    projection: TitleCardProjection,
    accentColor: Color,
    profileId: Int,
    watchedViewModel: com.saab.tv.ui.home.HomeViewModel,
    enrichedItems: Map<String, com.saab.tv.data.model.stremio.MetaItem>,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongClick: (com.saab.tv.data.model.stremio.MetaItem, Rect) -> Unit = { _, _ -> }
) {
    val meta = remember(projection) { projection.asMetaItem() }
    val landscapeCards = com.saab.tv.ui.components.LocalTitleCardShape.current == "landscape"
    LaunchedEffect(meta.type, meta.id, landscapeCards, profileId) {
        watchedViewModel.ensureWatchedAlias(profileId, meta)
    }
    val watchedIds = com.saab.tv.ui.components.LocalWatchedIds.current
    val previewItem = enrichedItems[meta.canonicalPresentationKey()] ?: meta
    val cardProjection = remember(previewItem, projection) {
        previewItem.toCanonicalProjection() ?: projection
    }
    // Watched IDs are already observed once by the screen host and delivered to
    // all cards through the composition local. Re-querying Room here once per
    // card (and again on every badge-flow update) made large recommendation rows
    // generate an N+1 database-read burst during Details composition.
    val isWatched = meta.id in watchedIds
    com.saab.tv.ui.components.SaabTvCard(
        title = projection.title,
        posterUrl = projection.artwork(com.saab.tv.domain.schema.ArtworkRole.POSTER)?.locator,
        previewItem = previewItem,
        canonicalProjection = cardProjection,
        isWatched = isWatched,
        normalWidth = 120.dp,
        normalHeight = 180.dp,
        showFocusOutline = false,
        modifier = modifier.titleTrailerFocus(meta),
        onFocused = onFocused,
        onClick = onClick,
        onLongClick = { bounds -> onLongClick(meta, bounds) }
    )
}

@Composable
private fun RecommendationPosterCard(
    name: String,
    previewItem: com.saab.tv.data.model.stremio.MetaItem? = null,
    poster: String?,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    com.saab.tv.ui.components.SaabTvCard(title = name, posterUrl = poster,
        previewItem = previewItem, normalWidth = 120.dp, normalHeight = 180.dp,
        modifier = modifier, onClick = onClick)

}

private fun extractPrimaryYear(releaseInfo: String?): String {
    if (releaseInfo.isNullOrBlank()) return "----"
    return Regex("\\d{4}").find(releaseInfo)?.value ?: releaseInfo.take(4)
}

private fun findFirstEpisode(videos: List<MetaVideo>?): MetaVideo? {
    if (videos.isNullOrEmpty()) return null

    val numbered = videos.filter { it.season > 0 && it.episode > 0 }
    val candidates = if (numbered.isNotEmpty()) numbered else videos
    return candidates.minWithOrNull(
        compareBy<MetaVideo>({ if (it.season > 0) it.season else Int.MAX_VALUE })
            .thenBy { if (it.episode > 0) it.episode else Int.MAX_VALUE }
            .thenBy { it.title }
    )
}


private fun resolveEpisodeForPlaybackId(
    seriesId: String,
    videos: List<MetaVideo>?,
    playbackId: String?
): MetaVideo? {
    val targetId = playbackId ?: return null
    val episodeList = videos ?: return null
    // Exact match (new format: seriesId:season:episode)
    episodeList.firstOrNull { episodePlaybackId(seriesId, it) == targetId }?.let { return it }
    // Fallback: match by season/episode numbers (handles old-format entries)
    val parsed = parseSeasonEpisodeFromPlaybackId(targetId) ?: return null
    return episodeList.firstOrNull { it.season == parsed.first && it.episode == parsed.second }
}

private fun parseSeasonEpisodeFromPlaybackId(playbackId: String?): Pair<Int, Int>? {
    val id = playbackId ?: return null
    val parts = id.split(":")
    if (parts.size < 3) return null
    val season = parts[parts.lastIndex - 1].toIntOrNull() ?: return null
    val episode = parts.last().toIntOrNull() ?: return null
    if (season <= 0 || episode <= 0) return null
    return season to episode
}

private val TORRENT_TRACKERS = listOf(
    // HTTP trackers (TCP — work even when UDP is blocked)
    "http://tracker.opentrackr.org:1337/announce",
    "http://tracker.openbittorrent.com:80/announce",
    "http://tracker1.bt.moack.co.kr:80/announce",
    "http://tracker.gbitt.info:80/announce",
    // UDP trackers (fallback)
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.stealth.si:80/announce",
    "udp://tracker.openbittorrent.com:6969/announce",
    "udp://exodus.desync.com:6969/announce"
)

private fun resolvePlayableUrl(stream: com.saab.tv.data.model.stremio.Stream): String? {
    if (!stream.url.isNullOrEmpty()) return stream.url
    if (!stream.infoHash.isNullOrEmpty()) {
        val trackerParams = TORRENT_TRACKERS.joinToString("") {
            "&tr=${java.net.URLEncoder.encode(it, "UTF-8")}"
        }
        return "magnet:?xt=urn:btih:${stream.infoHash}&dn=Video${trackerParams}"
    }
    return null
}
