package com.saab.tv.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import kotlin.random.Random

private const val NOISE_SIZE = 128

/**
 * Creates a reusable tiled noise bitmap (ALPHA_8, 128x128).
 * Generated once per composition and cached.
 */
@Composable
fun rememberNoiseBitmap(): Bitmap {
    return remember {
        val bitmap = Bitmap.createBitmap(NOISE_SIZE, NOISE_SIZE, Bitmap.Config.ALPHA_8)
        val pixels = IntArray(NOISE_SIZE * NOISE_SIZE)
        val random = Random(42) // Fixed seed for deterministic noise
        for (i in pixels.indices) {
            // Random alpha value, packed into ARGB format for setPixels
            val alpha = random.nextInt(256)
            pixels[i] = alpha shl 24
        }
        bitmap.setPixels(pixels, 0, NOISE_SIZE, 0, 0, NOISE_SIZE, NOISE_SIZE)
        bitmap
    }
}

/**
 * Draws a subtle tiled noise overlay to break up gradient banding.
 * Uses ~1.5-2% opacity so it's invisible on good panels but smooths
 * out banding on 6-bit and 8-bit displays.
 */
@Composable
fun NoiseOverlay(modifier: Modifier = Modifier, alpha: Float = 0.018f) {
    val noiseBitmap = rememberNoiseBitmap()
    val brush = remember(noiseBitmap) {
        ShaderBrush(ImageShader(noiseBitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        // One repeated shader draw replaces hundreds of individual tile draws
        // while two backdrops are composited during a transition.
        drawRect(brush = brush, alpha = alpha.coerceIn(0f, 1f))
    }
}
