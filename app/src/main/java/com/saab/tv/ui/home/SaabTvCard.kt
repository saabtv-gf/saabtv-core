package com.saab.tv.ui.components
import com.saab.tv.ui.trailer.trailerAnchor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.compose.material3.Text
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import com.saab.tv.R
import com.saab.tv.ui.theme.LocalRoundCorners
import com.saab.tv.ui.theme.SaabVisualTokens
import com.saab.tv.domain.schema.ArtworkRole
import com.saab.tv.domain.schema.ContentKind
import com.saab.tv.domain.schema.TitleCardProjection
import com.saab.tv.domain.schema.toCanonicalProjection
import com.saab.tv.ui.components.RetainedArtworkImage
import com.saab.tv.ui.components.artworkContentIdentityKey
import com.saab.tv.ui.utils.ArtworkCacheRole
import com.saab.tv.ui.utils.artworkDecodeSizePx
import com.saab.tv.ui.utils.artworkImageRequest

/**
 * ============================================================================
 * SAAB TV CARD - Netflix-Grade Optimized Media Card
 * ============================================================================
 * 
 * Optimizations applied:
 * 1. Retains the last successful artwork during replacement loads without subcomposition
 * 2. Simple zIndex switch instead of per-card animation (reduces CPU overhead)
 * 3. Hardware layer only when focused (GPU acceleration where needed)
 * 4. Fixed poster size for consistent cache hits
 * 5. Minimal crossfade for smooth transitions without jank
 * ============================================================================
 */
@OptIn(ExperimentalTvMaterial3Api::class)
val LocalWatchedIds = compositionLocalOf { emptySet<String>() }
val LocalWatchlistIds = compositionLocalOf { emptySet<String>() }
val LocalTitleCardShape = compositionLocalOf { "poster" }

internal fun shouldShowWatchedBadge(
    explicitWatched: Boolean,
    itemId: String?,
    watchedIds: Set<String>,
    enabled: Boolean = true,
    canonicalIdentityKey: String? = null
): Boolean = enabled && (explicitWatched ||
    (itemId != null && itemId in watchedIds) ||
    (!canonicalIdentityKey.isNullOrBlank() && canonicalIdentityKey in watchedIds))

internal fun shouldShowWatchlistBadge(
    itemId: String?,
    watchlistIds: Set<String>,
    canonicalIdentityKey: String? = null
): Boolean = (itemId != null && itemId in watchlistIds) ||
    (!canonicalIdentityKey.isNullOrBlank() && canonicalIdentityKey in watchlistIds)

/** Makes visible title-card status badges available to screen readers on the card itself. */
@Composable
internal fun titleCardStateDescription(watched: Boolean, watchlisted: Boolean): String? {
    val watchedLabel = if (watched) stringResource(R.string.title_badge_watched) else null
    val watchlistedLabel = if (watchlisted) stringResource(R.string.title_badge_watchlisted) else null
    return when {
        watchedLabel != null && watchlistedLabel != null ->
            stringResource(R.string.title_badge_combined_state, watchedLabel, watchlistedLabel)
        watchedLabel != null -> watchedLabel
        else -> watchlistedLabel
    }
}

internal fun resolveCardProjection(
    canonicalProjection: TitleCardProjection?,
    previewItem: com.saab.tv.data.model.stremio.MetaItem?
): TitleCardProjection? = canonicalProjection ?: previewItem?.toCanonicalProjection()

internal fun resolveCardArtworkUrl(
    projection: TitleCardProjection?,
    role: ArtworkRole,
    providerFallback: String?
): String? = projection?.artwork(role)?.locator?.takeIf(String::isNotBlank)
    ?: providerFallback?.takeIf(String::isNotBlank)

/** Opens context actions on release, so the hold gesture cannot activate the first action. */
internal class QuickActionLongPressHandler {
    private var centerPressStartedAtMs: Long? = null

    fun handleKey(
        key: Key,
        type: KeyEventType,
        repeatCount: Int,
        eventTimeMs: Long,
        downTimeMs: Long,
        onOpen: () -> Unit
    ): Boolean {
        if (key != Key.Enter && key != Key.DirectionCenter) return false
        if (type == KeyEventType.KeyDown) {
            if (centerPressStartedAtMs == null) {
                centerPressStartedAtMs = if (repeatCount > 0) downTimeMs
                else eventTimeMs.takeIf { it > 0L } ?: downTimeMs
            }
            if (repeatCount > 0) {
                return true
            }
            return false
        }
        if (type == KeyEventType.KeyUp) {
            val pressStartedAt = centerPressStartedAtMs ?: return false
            val heldForMs = (eventTimeMs - pressStartedAt).coerceAtLeast(0L)
            val shouldOpen = heldForMs >= LONG_PRESS_THRESHOLD_MS
            centerPressStartedAtMs = null
            if (shouldOpen) {
                onOpen()
                return true
            }
        }
        return false
    }

    fun reset() {
        centerPressStartedAtMs = null
    }

    private companion object {
        const val LONG_PRESS_THRESHOLD_MS = 500L
    }
}

internal data class SeriesCardStackLayer(
    val widthFraction: Float,
    val heightFraction: Float,
    val xOffsetDp: Float,
    val yOffsetDp: Float,
    val fillAlpha: Float,
    val borderAlpha: Float,
    val shadowElevationDp: Float
)

internal val seriesCardStackLayers = listOf(
    // Preserve the visible layered-series cue while keeping two opaque cards
    // tightly packed behind the cover. Landscape uses a scaled-down offset.
    SeriesCardStackLayer(.95f, .98f, 16f, 8f, 1f, .18f, 3f),
    SeriesCardStackLayer(.96f, .97f, 8f, 4f, 1f, .25f, 2f)
)

internal fun usesSeriesPosterStack(type: String?, canonicalKind: ContentKind? = null): Boolean =
    canonicalKind == ContentKind.SERIES || type == "series" || type == "tv"

/** Lightweight decorative stack shared by every series use of SaabTvCard. */
@Composable
internal fun BoxScope.SeriesTitleCardStack(
    cardShape: androidx.compose.ui.graphics.Shape,
    landscape: Boolean = false
) {
    seriesCardStackLayers.forEach { layer ->
        Box(
            modifier = Modifier.align(Alignment.Center)
                .fillMaxWidth(layer.widthFraction)
                .fillMaxHeight(layer.heightFraction)
                .offset(
                    x = if (landscape) (layer.xOffsetDp * .65f).dp else layer.xOffsetDp.dp,
                    y = if (landscape) (layer.yOffsetDp * .65f).dp else layer.yOffsetDp.dp
                )
                .shadow(layer.shadowElevationDp.dp, cardShape, clip = false)
                .clip(cardShape)
                // Keep stack cards opaque so the title artwork never bleeds through;
                // use a small native shadow for depth instead of blurring duplicate art.
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = layer.fillAlpha))
                .border(1.dp, Color(0xFF8AA3C7).copy(alpha = layer.borderAlpha), cardShape)
        )
    }
}

@Composable
internal fun WatchlistBadge(modifier: Modifier = Modifier) {
    val badgeColor = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.title_badge_watchlisted)
    Canvas(modifier.size(28.dp).semantics { contentDescription = description }) {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(0f, size.height)
            close()
        }
        drawPath(path, badgeColor)
        val bookmark = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * .19f, size.height * .15f)
            lineTo(size.width * .55f, size.height * .15f)
            lineTo(size.width * .55f, size.height * .68f)
            lineTo(size.width * .37f, size.height * .57f)
            lineTo(size.width * .19f, size.height * .68f)
            close()
        }
        drawPath(bookmark, Color.White)
    }
}

@Composable
internal fun TitleInitialArtwork(title: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(
            androidx.compose.ui.graphics.Brush.linearGradient(
                listOf(Color(0xFF1A2944), Color(0xFF26395D), Color(0xFF101722))
            )
        ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initialsForPerson(title),
            color = Color.White.copy(alpha = .92f),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
        )
    }
}

@Composable
internal fun WatchedBadge(modifier: Modifier = Modifier) {
    val badgeColor = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.title_badge_watched)
    Box(modifier.size(28.dp).clearAndSetSemantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxSize()) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width, 0f)
                lineTo(0f, 0f)
                lineTo(size.width, size.height)
                close()
            }
            drawPath(path, badgeColor)
        }
        Text(
            "✓",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 2.dp)
        )
    }
}

/** Keeps title-state markers identical in poster and landscape card variants. */
@Composable
internal fun BoxScope.TitleCardStatusBadges(
    watched: Boolean,
    watchlisted: Boolean
) {
    if (watchlisted) WatchlistBadge(Modifier.align(Alignment.TopStart))
    if (watched) WatchedBadge(Modifier.align(Alignment.TopEnd))
}

@Composable
fun SaabTvCard(
    title: String,
    posterUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    normalWidth: androidx.compose.ui.unit.Dp = 140.dp,
    normalHeight: androidx.compose.ui.unit.Dp = 210.dp,
    previewItem: com.saab.tv.data.model.stremio.MetaItem? = null,
    canonicalProjection: TitleCardProjection? = null,
    progress: Float = 0f,
    isWatched: Boolean = false,
    hasNewEpisode: Boolean = false,
    onFocused: (() -> Unit)? = null,
    onLongClick: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null,
    enableWatchedBadge: Boolean = true,
    landscapeWidth: androidx.compose.ui.unit.Dp = 190.dp,
    forceLandscape: Boolean = false,
    showFocusOutline: Boolean = true,
    focusRequester: FocusRequester? = null
) {
    // Provider DTOs remain the compatibility/action shape; canonical projection owns
    // the title and role-correct artwork used for drawing the shared card.
    val projection = remember(previewItem, canonicalProjection) {
        resolveCardProjection(canonicalProjection, previewItem)
    }
    val displayTitle = projection?.title?.takeIf(String::isNotBlank)
        ?: previewItem?.name?.takeIf(String::isNotBlank)
        ?: title
    val displayPoster = resolveCardArtworkUrl(
        projection, ArtworkRole.POSTER,
        previewItem?.poster?.takeIf(String::isNotBlank) ?: posterUrl
    )
    val displayBackdrop = resolveCardArtworkUrl(projection, ArtworkRole.BACKDROP, previewItem?.background)
    val displayLogo = resolveCardArtworkUrl(projection, ArtworkRole.LOGO, previewItem?.logo)
    val displayItem = remember(previewItem, projection, displayTitle, displayPoster, displayBackdrop, displayLogo) {
        (previewItem ?: projection?.asMetaItem())?.copy(
            name = displayTitle,
            poster = displayPoster,
            background = displayBackdrop,
            logo = displayLogo
        )
    }
    if (forceLandscape || LocalTitleCardShape.current == "landscape") {
        SaabTvLandscapeCard(
            title = displayTitle,
            backdropUrl = displayBackdrop,
            logoUrl = displayLogo,
            onClick = onClick,
            modifier = modifier,
            previewItem = displayItem,
            canonicalProjection = projection,
            progress = progress,
            isWatched = isWatched,
            hasNewEpisode = hasNewEpisode,
            onFocused = onFocused,
            onLongClick = onLongClick,
            enableWatchedBadge = enableWatchedBadge,
            cardWidth = landscapeWidth,
            showFocusOutline = showFocusOutline,
            focusRequester = focusRequester
        )
        return
    }
    val showWatchedBadge = shouldShowWatchedBadge(
        isWatched, previewItem?.id, LocalWatchedIds.current, enabled = enableWatchedBadge,
        canonicalIdentityKey = projection?.presentationKey
    )
    val showWatchlistBadge = shouldShowWatchlistBadge(
        previewItem?.id, LocalWatchlistIds.current, projection?.presentationKey
    )
    val cardStateDescription = titleCardStateDescription(showWatchedBadge, showWatchlistBadge)
    val isSeriesCard = usesSeriesPosterStack(previewItem?.type, projection?.kind)
    val ownRequester = remember { FocusRequester() }
    val rememberReturnFocus = LocalPosterFocusReturn.current
    val longPressHandler = remember(previewItem?.id) { QuickActionLongPressHandler() }
    val cardCoordinates = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val roundCorners = LocalRoundCorners.current
    val density = LocalDensity.current
    val posterDecodeSize = remember(normalWidth, normalHeight, density.density) {
        artworkDecodeSizePx(normalWidth, normalHeight, density)
    }

    // Shape based on user preference
    val cardShape = if (roundCorners) RoundedCornerShape(12.dp) else RectangleShape
    val focusedCardShape = cardShape
    // Stable geometry on focus; the border is the selection indicator.
    Box(
        modifier = modifier.trailerAnchor(displayItem)
            .width(normalWidth)
            .aspectRatio(normalWidth.value / normalHeight.value)
            .onGloballyPositioned { cardCoordinates[0] = it }
            .graphicsLayer { clip = false }
    ) {
        if (isSeriesCard) {
            SeriesTitleCardStack(cardShape)
        }
        Surface(
            onClick = onClick,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize()
                .then(focusRequester?.let(Modifier::focusRequester) ?: Modifier)
                .focusRequester(ownRequester)
                .semantics(mergeDescendants = true) {
                    contentDescription = displayTitle
                    cardStateDescription?.let { stateDescription = it }
                }
                .onPreviewKeyEvent { event ->
                    onLongClick?.let { openActions ->
                        longPressHandler.handleKey(
                            event.key, event.type, event.nativeKeyEvent.repeatCount,
                            event.nativeKeyEvent.eventTime, event.nativeKeyEvent.downTime
                        ) {
                            openActions(cardCoordinates[0]?.boundsInWindow() ?: androidx.compose.ui.geometry.Rect.Zero)
                        }
                    } ?: false
                }
                .cardFocusSound()
                .onFocusChanged {
                    if (it.isFocused) rememberReturnFocus(ownRequester)
                    if (!it.isFocused) longPressHandler.reset()
                    if (it.isFocused) onFocused?.invoke()
                },
            shape = ClickableSurfaceDefaults.shape(
                shape = cardShape,
                focusedShape = focusedCardShape
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                contentColor = Color.White
            ),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(
                        if (showFocusOutline) SaabVisualTokens.focusOutlineWidth else 0.dp,
                        if (showFocusOutline) SaabVisualTokens.focusColor else Color.Transparent
                    ),
                    shape = focusedCardShape
                )
            )
        ) {
            val context = LocalContext.current
            val imageRequest = remember(displayPoster, posterDecodeSize) {
                displayPoster?.takeIf(String::isNotBlank)?.let {
                    artworkImageRequest(context, it, ArtworkCacheRole.Poster, posterDecodeSize)
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                RetainedArtworkImage(
                    model = imageRequest,
                    contentDescription = displayTitle,
                    contentScale = ContentScale.Crop,
                    contentIdentityKey = artworkContentIdentityKey(
                        stableContentKey = projection?.presentationKey
                            ?: displayItem?.let { "${it.type}:${it.id}" },
                        artworkUrl = displayPoster,
                        title = displayTitle
                    ),
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(cardShape)
                        .background(MaterialTheme.colorScheme.surface),
                    unavailable = { TitleInitialArtwork(displayTitle) }
                )

                TitleCardStatusBadges(
                    watched = showWatchedBadge,
                    watchlisted = showWatchlistBadge
                )

                // New episode badge for next-up items
                if (hasNewEpisode) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "+1",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }
                }

                // Progress bar overlay for Continue Watching items
                if (progress > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 5.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(Color.White.copy(0.3f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }
    }
}
