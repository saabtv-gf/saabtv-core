package com.saab.tv.ui.components
import com.saab.tv.ui.trailer.trailerAnchor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.compose.material3.Text
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Scale
import com.saab.tv.ui.theme.LocalRoundCorners

/**
 * ============================================================================
 * SAAB TV CARD - Netflix-Grade Optimized Media Card
 * ============================================================================
 * 
 * Optimizations applied:
 * 1. AsyncImage instead of SubcomposeAsyncImage (reduces recomposition)
 * 2. Simple zIndex switch instead of per-card animation (reduces CPU overhead)
 * 3. Hardware layer only when focused (GPU acceleration where needed)
 * 4. Fixed poster size for consistent cache hits
 * 5. Minimal crossfade for smooth transitions without jank
 * ============================================================================
 */
@OptIn(ExperimentalTvMaterial3Api::class)
val LocalWatchedIds = compositionLocalOf { emptySet<String>() }

internal fun shouldShowWatchedBadge(
    explicitWatched: Boolean,
    itemId: String?,
    watchedIds: Set<String>,
    enabled: Boolean = true
): Boolean = enabled && (explicitWatched || (itemId != null && itemId in watchedIds))

internal data class SeriesCardStackLayer(
    val widthFraction: Float,
    val heightFraction: Float,
    val xOffsetDp: Float,
    val yOffsetDp: Float,
    val alpha: Float
)

internal val seriesCardStackLayers = listOf(
    SeriesCardStackLayer(.95f, .98f, 16f, 8f, .45f),
    SeriesCardStackLayer(.96f, .97f, 8f, 4f, .70f)
)

internal fun usesSeriesPosterStack(type: String?): Boolean = type == "series" || type == "tv"

/** Lightweight decorative stack shared by every series use of SaabTvCard. */
@Composable
internal fun BoxScope.SeriesTitleCardStack(
    cardShape: androidx.compose.ui.graphics.Shape,
    isFocused: Boolean
) {
    seriesCardStackLayers.forEach { layer ->
        val alpha by animateFloatAsState(
            targetValue = (layer.alpha + if (isFocused) .1f else 0f).coerceAtMost(.9f),
            animationSpec = tween(180),
            label = "series-stack-alpha"
        )
        Box(
            modifier = Modifier.align(Alignment.Center)
                .fillMaxWidth(layer.widthFraction)
                .fillMaxHeight(layer.heightFraction)
                .offset(x = layer.xOffsetDp.dp, y = layer.yOffsetDp.dp)
                .graphicsLayer { this.alpha = alpha }
                .clip(cardShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, Color(0xFF8AA3C7).copy(alpha = .25f), cardShape)
        )
    }
}

@Composable
internal fun WatchedBadge(modifier: Modifier = Modifier) {
    val badgeColor = MaterialTheme.colorScheme.primary
    Box(modifier.size(28.dp)) {
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

@Composable
fun SaabTvCard(
    title: String,
    posterUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    normalWidth: androidx.compose.ui.unit.Dp = 140.dp,
    normalHeight: androidx.compose.ui.unit.Dp = 210.dp,
    previewItem: com.saab.tv.data.model.stremio.MetaItem? = null,
    progress: Float = 0f,
    isWatched: Boolean = false,
    hasNewEpisode: Boolean = false,
    onFocused: (() -> Unit)? = null,
    onLongClick: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null,
    enableWatchedBadge: Boolean = true
) {
    val showWatchedBadge = shouldShowWatchedBadge(
        isWatched, previewItem?.id, LocalWatchedIds.current, enabled = enableWatchedBadge
    )
    val isSeriesCard = usesSeriesPosterStack(previewItem?.type)
    var isFocused by remember { mutableStateOf(false) }
    val ownRequester = remember { FocusRequester() }
    val rememberReturnFocus = LocalPosterFocusReturn.current
    var longPressHandled by remember { mutableStateOf(false) }
    val cardCoordinates = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val roundCorners = LocalRoundCorners.current

    // Shape based on user preference
    val cardShape = if (roundCorners) RoundedCornerShape(12.dp) else RectangleShape
    val focusedCardShape = cardShape
    // Stable geometry on focus; the border is the selection indicator.
    Box(
        modifier = modifier.trailerAnchor(previewItem)
            .width(normalWidth)
            .aspectRatio(normalWidth.value / normalHeight.value)
            .onGloballyPositioned { cardCoordinates[0] = it }
            .zIndex(if (isFocused) 10f else 0f)
            .graphicsLayer { clip = false }
    ) {
        if (isSeriesCard && isFocused) {
            Box(
                Modifier.align(Alignment.Center)
                    .fillMaxWidth(.98f)
                    .fillMaxHeight(.98f)
                    .offset(x = 3.dp, y = 2.dp)
                    .blur(6.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = .2f), cardShape)
            )
        }
        if (isSeriesCard) {
            SeriesTitleCardStack(cardShape, isFocused)
        }
        Surface(
            onClick = onClick,
            modifier = Modifier
                .align(Alignment.Center)
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
                        2.dp,
                        Color.White
                    ),
                    shape = focusedCardShape
                )
            )
        ) {
            val context = LocalContext.current
            val imageRequest = remember(posterUrl) {
                ImageRequest.Builder(context)
                    .data(posterUrl)
                    .crossfade(false)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .scale(Scale.FILL)
                    .size(280, 420)
                    .allowHardware(true)
                    .build()
            }
            Box(modifier = Modifier.fillMaxSize()) {
                // AsyncImage is lighter than SubcomposeAsyncImage - no subcomposition overhead
                AsyncImage(
                    model = imageRequest,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(cardShape)
                        .background(MaterialTheme.colorScheme.surface)
                )

                // Watched badge — corner triangle with checkmark
                if (showWatchedBadge) WatchedBadge(Modifier.align(Alignment.TopEnd))

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
