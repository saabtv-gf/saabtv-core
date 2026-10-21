package com.saab.tv.ui.components
import com.saab.tv.ui.trailer.trailerAnchor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import com.saab.tv.domain.schema.ArtworkRole
import com.saab.tv.domain.schema.TitleCardProjection
import androidx.tv.material3.Text
import com.saab.tv.ui.theme.LocalRoundCorners
import com.saab.tv.ui.theme.SaabVisualTokens
import com.saab.tv.ui.utils.ArtworkCacheRole
import com.saab.tv.ui.utils.artworkDecodeSizePx
import com.saab.tv.ui.utils.artworkImageRequest

/**
 * ============================================================================
 * SAAB TV LANDSCAPE CARD - Continue Watching Landscape Mode
 * ============================================================================
 *
 * Displays a 16:9 landscape card with:
 * - Landscape hero/backdrop image (never stretches a portrait poster)
 * - Gradient scrim at bottom for readability
 * - Logo overlay in bottom-left (falls back to text title)
 * - Progress bar at bottom
 *
 * Matches horizontal hub card sizing (190dp wide, 16:9 aspect).
 * ============================================================================
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SaabTvLandscapeCard(
    title: String,
    backdropUrl: String?,
    logoUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    previewItem: com.saab.tv.data.model.stremio.MetaItem? = null,
    canonicalProjection: TitleCardProjection? = null,
    progress: Float = 0f,
    isWatched: Boolean = false,
    hasNewEpisode: Boolean = false,
    onFocused: (() -> Unit)? = null,
    onLongClick: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null,
    enableWatchedBadge: Boolean = true,
    cardWidth: Dp = 190.dp,
    showFocusOutline: Boolean = true,
    focusRequester: FocusRequester? = null
) {
    val resolvedProjection = remember(canonicalProjection, previewItem) {
        resolveCardProjection(canonicalProjection, previewItem)
    }
    val resolvedBackdropUrl = resolveCardArtworkUrl(
        resolvedProjection,
        ArtworkRole.BACKDROP,
        backdropUrl?.takeIf(String::isNotBlank) ?: previewItem?.background
    )
    val resolvedLogoUrl = resolveCardArtworkUrl(
        resolvedProjection,
        ArtworkRole.LOGO,
        logoUrl?.takeIf(String::isNotBlank) ?: previewItem?.logo
    )
    val landscapeImageUrl = landscapeArtworkUrl(resolvedBackdropUrl)
    val canonicalIdentityKey = resolvedProjection?.presentationKey
    val showWatchedBadge = shouldShowWatchedBadge(
        isWatched, previewItem?.id, LocalWatchedIds.current, enabled = enableWatchedBadge,
        canonicalIdentityKey = canonicalIdentityKey
    )
    val showWatchlistBadge = shouldShowWatchlistBadge(
        previewItem?.id, LocalWatchlistIds.current, canonicalIdentityKey
    )
    val cardStateDescription = titleCardStateDescription(showWatchedBadge, showWatchlistBadge)
    val ownRequester = remember { FocusRequester() }
    val rememberReturnFocus = LocalPosterFocusReturn.current
    val longPressHandler = remember(previewItem?.id) { QuickActionLongPressHandler() }
    val cardCoordinates = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val roundCorners = LocalRoundCorners.current

    val cardShape = if (roundCorners) RoundedCornerShape(12.dp) else RectangleShape
    val focusedCardShape = cardShape
    val density = LocalDensity.current
    val landscapeDecodeSize = remember(cardWidth, density.density) {
        artworkDecodeSizePx(cardWidth, cardWidth * (9f / 16f), density)
    }
    val logoDecodeSize = remember(density.density) {
        artworkDecodeSizePx(150.dp, 45.dp, density)
    }

    Box(
        modifier = modifier.trailerAnchor(previewItem)
            .width(cardWidth)
            .aspectRatio(16f / 9f)
            .onGloballyPositioned { cardCoordinates[0] = it }
            .graphicsLayer { clip = false }
    ) {
        if (usesSeriesPosterStack(previewItem?.type, resolvedProjection?.kind)) {
            SeriesTitleCardStack(cardShape, landscape = true)
        }
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxSize()
                .then(focusRequester?.let(Modifier::focusRequester) ?: Modifier)
                .focusRequester(ownRequester)
                .semantics(mergeDescendants = true) {
                    contentDescription = title
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
            val imageRequest = remember(landscapeImageUrl, landscapeDecodeSize) {
                landscapeImageUrl?.let {
                    artworkImageRequest(context, it, ArtworkCacheRole.Landscape, landscapeDecodeSize)
                }
            }
            val logoRequest = remember(resolvedLogoUrl, logoDecodeSize) {
                resolvedLogoUrl?.takeIf(String::isNotBlank)?.let {
                    artworkImageRequest(context, it, ArtworkCacheRole.Logo, logoDecodeSize)
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                // Backdrop/poster image
                RetainedArtworkImage(
                    model = landscapeImageUrl?.let { imageRequest },
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    contentIdentityKey = artworkContentIdentityKey(
                        stableContentKey = canonicalIdentityKey ?: previewItem?.let { "${it.type}:${it.id}" },
                        artworkUrl = landscapeImageUrl,
                        title = title
                    ),
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(cardShape)
                        .background(MaterialTheme.colorScheme.surface),
                    unavailable = { TitleInitialArtwork(title) }
                )

                TitleCardStatusBadges(
                    watched = showWatchedBadge,
                    watchlisted = showWatchlistBadge
                )

                // Bottom gradient scrim for logo/text readability
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.55f)
                        .align(Alignment.BottomStart)
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to Color.Transparent,
                                    0.15f to Color.Black.copy(alpha = 0.05f),
                                    0.3f to Color.Black.copy(alpha = 0.15f),
                                    0.45f to Color.Black.copy(alpha = 0.30f),
                                    0.6f to Color.Black.copy(alpha = 0.48f),
                                    0.75f to Color.Black.copy(alpha = 0.64f),
                                    0.88f to Color.Black.copy(alpha = 0.77f),
                                    1.0f to Color.Black.copy(alpha = 0.85f)
                                )
                            )
                        )
                )

                // Logo or text title in bottom-left
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(
                            start = 10.dp,
                            bottom = if (progress > 0f) 12.dp else 6.dp
                        )
                ) {
                    if (!resolvedLogoUrl.isNullOrEmpty()) {
                        RetainedArtworkImage(
                            model = logoRequest,
                            contentDescription = title,
                            contentScale = ContentScale.Fit,
                            contentIdentityKey = artworkContentIdentityKey(
                                stableContentKey = canonicalIdentityKey ?: previewItem?.let { "${it.type}:${it.id}" },
                                artworkUrl = resolvedLogoUrl,
                                title = title
                            ),
                            alignment = Alignment.BottomStart,
                            modifier = Modifier
                                .widthIn(max = 150.dp)
                                .heightIn(max = 45.dp),
                            unavailable = {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    ),
                                    color = Color.White,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    } else {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            ),
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // New episode badge
                if (hasNewEpisode) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        androidx.compose.material3.Text(
                            "+1",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }
                }

                // Progress bar overlay
                if (progress > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 5.dp)
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

/** Landscape cards must use landscape artwork; absent backdrops use initials, not a portrait poster. */
internal fun landscapeArtworkUrl(backdropUrl: String?): String? =
    backdropUrl?.takeIf(String::isNotBlank)
