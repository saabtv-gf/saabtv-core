package com.saab.tv.ui.utils

import android.content.Context
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Scale

internal enum class ArtworkCacheRole(val key: String, val scale: Scale) {
    Poster("poster", Scale.FILL),
    Landscape("landscape", Scale.FILL),
    Logo("logo", Scale.FIT),
    Hub("hub", Scale.FILL)
}

internal fun artworkDecodeSizePx(width: Dp, height: Dp, density: Density): IntSize = with(density) {
    IntSize(width.roundToPx().coerceAtLeast(1), height.roundToPx().coerceAtLeast(1))
}

internal fun artworkMemoryCacheKey(role: String, url: String, size: IntSize): String =
    "$role:$url:${size.width}x${size.height}"

/** Renderer and prefetcher share the exact cache, decode-size and scale contract. */
internal fun artworkImageRequest(
    context: Context,
    url: String,
    role: ArtworkCacheRole,
    size: IntSize
): ImageRequest = ImageRequest.Builder(context)
    .data(url)
    .size(size.width, size.height)
    .scale(role.scale)
    .crossfade(false)
    .memoryCacheKey(artworkMemoryCacheKey(role.key, url, size))
    .diskCacheKey(url)
    .memoryCachePolicy(CachePolicy.ENABLED)
    .diskCachePolicy(CachePolicy.ENABLED)
    .allowHardware(true)
    .build()
