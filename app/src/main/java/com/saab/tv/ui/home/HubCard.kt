package com.saab.tv.ui.home

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Scale
import com.saab.tv.domain.HubItem
import com.saab.tv.domain.HubShape
import com.saab.tv.ui.components.cardFocusSound
import com.saab.tv.ui.theme.LocalHubRoundCorners

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
 * - Premium focus animation (scale 1.1f + border glow)
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
    onFocused: (() -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val roundCorners = LocalHubRoundCorners.current
    
    // Card dimensions based on shape
    val cardWidth = when (shape) {
        HubShape.HORIZONTAL -> 190.dp
        HubShape.VERTICAL -> 120.dp
        HubShape.SQUARE -> 140.dp
    }
    
    // Shape based on user preference
    val cardShape = if (roundCorners) RoundedCornerShape(12.dp) else RectangleShape
    val focusedCardShape = if (roundCorners) RoundedCornerShape(16.dp) else RectangleShape
    


    Box(
        modifier = modifier
            .width(cardWidth)
            .aspectRatio(shape.aspectRatio)
            .zIndex(if (isFocused) 10f else 0f)
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
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                contentColor = Color.White
            ),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(4.dp, Color.White),
                    shape = focusedCardShape
                )
            )
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Background: Either custom image or Simple Card Style
                if (hubItem.customImageUrl != null) {
                    // Custom Image with Scrim
                    val context = LocalContext.current
                    val imageRequest = remember(hubItem.customImageUrl) {
                        ImageRequest.Builder(context)
                            .data(hubItem.customImageUrl)
                            .crossfade(false)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .scale(Scale.FILL)
                            .allowHardware(true)
                            .build()
                    }
                    
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = hubItem.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(cardShape)
                    )

                } else {
                    // Simple Card Style (ViewMoreCard look)
                    // No image, just background color + centered text/icon
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(cardShape)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = hubItem.title,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                ),
                                color = if (isFocused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.9f),
                                textAlign = TextAlign.Center,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
