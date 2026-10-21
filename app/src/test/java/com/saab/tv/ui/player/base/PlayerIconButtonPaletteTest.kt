package com.saab.tv.ui.player.base

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerIconButtonPaletteTest {
    @Test
    fun focusedControlsUseHighContrastColorsAndKeepTheirIdleContainer() {
        val idleContainer = Color.White.copy(alpha = 0.12f)

        val palette = playerIconButtonPalette(idleContainer)

        assertEquals(idleContainer, palette.containerColor)
        assertEquals(Color.White, palette.focusedContainerColor)
        assertEquals(Color.White, palette.contentColor)
        assertEquals(Color.Black, palette.focusedContentColor)
    }

    @Test
    fun transparentControlsKeepTransparentIdleStateAndHighContrastFocus() {
        val palette = playerIconButtonPalette(Color.Transparent)

        assertEquals(Color.Transparent, palette.containerColor)
        assertEquals(Color.White, palette.focusedContainerColor)
        assertEquals(Color.White, palette.contentColor)
        assertEquals(Color.Black, palette.focusedContentColor)
    }
}
