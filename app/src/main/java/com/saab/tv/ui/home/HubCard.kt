package com.saab.tv.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.saab.tv.domain.HubItem
import com.saab.tv.domain.HubShape
import com.saab.tv.ui.components.RetainedArtworkImage
import com.saab.tv.ui.components.SaabMotion
import com.saab.tv.ui.components.cardFocusSound
import com.saab.tv.ui.theme.LocalHubRoundCorners
import com.saab.tv.ui.utils.ArtworkCacheRole
import com.saab.tv.ui.utils.artworkDecodeSizePx
import com.saab.tv.ui.utils.artworkImageRequest

internal fun hubCardWidth(shape: HubShape): Dp = when (shape) {
    HubShape.HORIZONTAL -> 190.dp
    HubShape.VERTICAL -> 120.dp
    HubShape.SQUARE -> 140.dp
}

internal fun hubCardArtworkSizePx(shape: HubShape, density: Density) =
    artworkDecodeSizePx(
        hubCardWidth(shape),
        hubCardWidth(shape) / shape.aspectRatio,
        density
    )

internal fun hubCardArtworkUrl(url: String?): String? = url?.takeIf(String::isNotBlank)

/**
 * ============================================================================
 * HUB CARD - Premium Category Card for Hub Rows
 * ============================================================================
 * 
 * A stylish, focusable card representing a category in a Hub Row.
 * Supports three aspect ratios (Horizontal, Vertical, Square) and
 * displays either a custom image with scrim overlay or a gradient fallback.
 * 
 * Features:
 * - Dynamic aspect ratio based on HubShape
 * - Custom image loading with bottom scrim for title readability
 * - Gradient fallback when no image is provided
 * - Stable geometry with a high-contrast focus outline
 * - Netflix-grade visual styling
 * ============================================================================
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HubCard(
    hubItem: HubItem,
    shape: HubShape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: (() -> Unit)? = null,
    showFocusBorder: Boolean = true
) {
    var isFocused by remember { mutableStateOf(false) }
    val roundCorners = LocalHubRoundCorners.current
    
    // Card dimensions based on shape
    val cardWidth = hubCardWidth(shape)
    val density = LocalDensity.current
    val artworkSize = remember(shape, density.density) {
        hubCardArtworkSizePx(shape, density)
    }
    
    // Shape based on user preference
    val cardShape = if (roundCorners) RoundedCornerShape(12.dp) else RectangleShape
    val focusedCardShape = cardShape
    


    Box(
        modifier = modifier
            .width(cardWidth)
            .aspectRatio(shape.aspectRatio)
            .graphicsLayer { clip = false }
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxSize()
                .cardFocusSound()
                .onFocusChanged {
                    isFocused = it.isFocused
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
                focusedBorder = if (showFocusBorder) {
                    Border(border = BorderStroke(2.dp, Color.White), shape = focusedCardShape)
                } else {
                    Border(border = BorderStroke(0.dp, Color.Transparent), shape = focusedCardShape)
                }
            )
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Background: Either custom image or Simple Card Style
                val customImageUrl = hubCardArtworkUrl(hubItem.customImageUrl)
                if (customImageUrl != null) {
                    // Custom Image with Scrim
                    val context = LocalContext.current
                    val imageRequest = remember(context, customImageUrl, artworkSize) {
                        artworkImageRequest(context, customImageUrl, ArtworkCacheRole.Hub, artworkSize)
                    }
                    
                    RetainedArtworkImage(
                        model = imageRequest,
                        contentDescription = hubItem.title,
                        contentScale = ContentScale.Crop,
                        contentIdentityKey = hubItem.id,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(cardShape),
                        unavailable = {
                            HubCardFallback(
                                title = hubItem.title,
                                isFocused = isFocused,
                                modifier = Modifier.fillMaxSize().clip(cardShape)
                            )
                        }
                    )

                } else {
                    HubCardFallback(
                        title = hubItem.title,
                        isFocused = isFocused,
                        modifier = Modifier.fillMaxSize().clip(cardShape)
                    )
                }
            }
        }
    }
}

@Composable
private fun HubCardFallback(title: String, isFocused: Boolean, modifier: Modifier = Modifier) {
    val titleColor by animateColorAsState(
        targetValue = if (isFocused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.9f),
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "hub-card-title-color"
    )
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                ),
                color = titleColor,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
