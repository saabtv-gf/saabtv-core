package com.saab.tv.ui.watchlist

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFilterContrastTest {
    @Test
    fun selectedFilterLabelMeetsNormalTextContrastOnItsBlueFill() {
        val background = Color(0xFF1488E8)
        val foreground = libraryFilterTextColor(selected = true)

        assertTrue(contrastRatio(foreground, background) >= 4.5f)
    }

    @Test
    fun unselectedFilterKeepsWhiteLabel() {
        assertEquals(Color.White, libraryFilterTextColor(selected = false))
    }

    private fun contrastRatio(first: Color, second: Color): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
