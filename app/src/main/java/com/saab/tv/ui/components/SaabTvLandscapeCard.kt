package com.saab.tv.ui.components
import com.saab.tv.ui.trailer.trailerAnchor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Scale
import com.saab.tv.ui.theme.LocalRoundCorners

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
    progress: Float = 0f,
    isWatched: Boolean = false,
    hasNewEpisode: Boolean = false,
    onFocused: (() -> Unit)? = null,
    onLongClick: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null,
    enableWatchedBadge: Boolean = true,
    cardWidth: Dp = 190.dp,
    showFocusOutline: Boolean = true
) {
    val landscapeImageUrl = landscapeArtworkUrl(backdropUrl)
    var artworkUnavailable by remember(landscapeImageUrl) {
        mutableStateOf(shouldShowLandscapeInitialArtwork(backdropUrl))
    }
    val showWatchedBadge = shouldShowWatchedBadge(
        isWatched, previewItem?.id, LocalWatchedIds.current, enabled = enableWatchedBadge
    )
    val showWatchlistBadge = shouldShowWatchlistBadge(previewItem?.id, LocalWatchlistIds.current)
    var isFocused by remember { mutableStateOf(false) }
    val ownRequester = remember { FocusRequester() }
    val rememberReturnFocus = LocalPosterFocusReturn.current
    var longPressHandled by remember { mutableStateOf(false) }
    val cardCoordinates = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val roundCorners = LocalRoundCorners.current

    val cardShape = if (roundCorners) RoundedCornerShape(12.dp) else RectangleShape
    val focusedCardShape = cardShape

    Box(
        modifier = modifier.trailerAnchor(previewItem)
            .width(cardWidth)
            .aspectRatio(16f / 9f)
            .onGloballyPositioned { cardCoordinates[0] = it }
            .zIndex(if (isFocused) 10f else 0f)
            .graphicsLayer { clip = false }
    ) {
        if (usesSeriesPosterStack(previewItem?.type)) {
            SeriesTitleCardStack(cardShape, isFocused, landscape = true)
        }
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(ownRequester)
                .onPreviewKeyEvent { event ->
                    if (onLongClick == null || (event.key != Key.Enter && event.key != Key.DirectionCenter)) false
                    else if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount > 0) {
                        if (!longPressHandled) onLongClick(cardCoordinates[0]?.boundsInWindow() ?: androidx.compose.ui.geometry.Rect.Zero)
                        longPressHandled = true
                        true
                    } else if (event.type == KeyEventType.KeyUp && longPressHandled) {
                        longPressHandled = false
                        true
                    } else false
                }
                .cardFocusSound()
                .onFocusChanged {
                    isFocused = it.isFocused
                    if (it.isFocused) rememberReturnFocus(ownRequester)
                    if (!it.isFocused) longPressHandled = false
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
                        if (showFocusOutline) 2.dp else 0.dp,
                        if (showFocusOutline) Color.White else Color.Transparent
                    ),
                    shape = focusedCardShape
                )
            )
        ) {
            val context = LocalContext.current
            val imageRequest = remember(landscapeImageUrl) {
                ImageRequest.Builder(context)
                    .data(landscapeImageUrl)
                    .crossfade(70)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .scale(Scale.FILL)
                    .size(380, 214) // 2x card size for crisp rendering on high-DPI
                    .memoryCacheKey(landscapeImageUrl?.let { "landscape:$it" })
                    .diskCacheKey(landscapeImageUrl?.let { "landscape:$it" })
                    .allowHardware(true)
                    .build()
            }
            val logoRequest = remember(logoUrl) {
                ImageRequest.Builder(context)
                    .data(logoUrl)
                    .size(300, 90)
                    .memoryCacheKey(logoUrl?.let { "logo:$it" })
                    .diskCacheKey(logoUrl?.let { "logo:$it" })
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .allowHardware(true)
                    .build()
            }

            Box(modifier = Modifier.fillMaxSize()) {
                // Backdrop/poster image
                AsyncImage(
                    model = imageRequest,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    onState = { state ->
                        if (state is coil.compose.AsyncImagePainter.State.Error) {
                            artworkUnavailable = shouldShowLandscapeInitialArtwork(
                                backdropUrl, loadFailed = true
                            )
                        }
                        if (state is coil.compose.AsyncImagePainter.State.Success) artworkUnavailable = false
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(cardShape)
                        .background(MaterialTheme.colorScheme.surface)
                )

                if (artworkUnavailable) TitleInitialArtwork(title)

                if (showWatchedBadge) WatchedBadge(Modifier.align(Alignment.TopEnd))
                if (showWatchlistBadge) WatchlistBadge(Modifier.align(Alignment.TopStart))

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
                    if (!logoUrl.isNullOrEmpty()) {
                        SubcomposeAsyncImage(
                            model = logoRequest,
                            contentDescription = title,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.BottomStart,
                            modifier = Modifier
                                .widthIn(max = 150.dp)
                                .heightIn(max = 45.dp),
                            error = {
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

internal fun shouldShowLandscapeInitialArtwork(
    backdropUrl: String?,
    loadFailed: Boolean = false
): Boolean = landscapeArtworkUrl(backdropUrl) == null || loadFailed
