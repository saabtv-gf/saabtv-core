package com.saab.tv.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShelfFocusIndicatorDrawTest {
    @Test fun focusFramePaintsRoundedAndSquareOutlines() {
        val unfocused = drawFrame(focused = false, rounded = true)
        val rounded = drawFrame(focused = true, rounded = true)
        val square = drawFrame(focused = true, rounded = false)

        assertEquals("unfocused frame draws no outline", 0, unfocused.whitePixelCount())
        assertTrue("rounded frame paints a white outline", rounded.whitePixelCount() > 0)
        assertTrue("square frame paints a white outline", square.whitePixelCount() > 0)
        assertTrue("rounded corners leave the extreme corner transparent", rounded.toPixelMap()[0, 0].alpha == 0f)
        assertEquals("square outline reaches the extreme corner", Color.White, square.toPixelMap()[0, 0])
    }

    private fun drawFrame(focused: Boolean, rounded: Boolean): ImageBitmap {
        val image = ImageBitmap(100, 60, ImageBitmapConfig.Argb8888)
        val canvas = Canvas(image)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = canvas,
            size = Size(100f, 60f)
        ) {
            drawShelfFocusIndicator(focused, rounded)
        }
        return image
    }

    private fun ImageBitmap.whitePixelCount(): Int {
        val pixels = toPixelMap()
        return (0 until height).sumOf { y -> (0 until width).count { x -> pixels[x, y] == Color.White } }
    }
}
