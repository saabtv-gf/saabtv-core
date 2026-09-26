package com.saab.tv.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.IntSize
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
    val imageBitmap = remember(noiseBitmap) { noiseBitmap.asImageBitmap() }

    Canvas(modifier = modifier.fillMaxSize()) {
        val tileW = NOISE_SIZE
        val tileH = NOISE_SIZE
        val cols = (size.width / tileW).toInt() + 1
        val rows = (size.height / tileH).toInt() + 1

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                drawImage(
                    image = imageBitmap,
                    dstOffset = androidx.compose.ui.unit.IntOffset(col * tileW, row * tileH),
                    dstSize = IntSize(tileW, tileH),
                    alpha = alpha
                )
            }
        }
    }
}
