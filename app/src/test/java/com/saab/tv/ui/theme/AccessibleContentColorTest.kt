package com.saab.tv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibleContentColorTest {
    @Test
    fun builtInPrimaryAndDestructiveColorsMeetNormalTextContrast() {
        DefaultThemes.ALL.forEach { theme ->
            assertContrastAtLeast(
                accessibleContentColor(Color(theme.primaryColor.toInt())),
                Color(theme.primaryColor.toInt()),
                4.5f,
                "${theme.name} primary"
            )
            assertContrastAtLeast(
                accessibleContentColor(Color(theme.errorColor.toInt())),
                Color(theme.errorColor.toInt()),
                4.5f,
                "${theme.name} error"
            )
        }
    }

    @Test
    fun customDarkAndLightAccentsChooseOppositeReadableForegrounds() {
        assertEquals(Color.White, accessibleContentColor(Color(0xFF102B52)))
        assertEquals(Color.Black, accessibleContentColor(Color(0xFFFFEA00)))
    }

    private fun assertContrastAtLeast(foreground: Color, background: Color, minimum: Float, label: String) {
        val foregroundLuminance = foreground.luminance()
        val backgroundLuminance = background.luminance()
        val ratio = (maxOf(foregroundLuminance, backgroundLuminance) + 0.05f) /
            (minOf(foregroundLuminance, backgroundLuminance) + 0.05f)
        assertTrue("$label contrast was $ratio", ratio >= minimum)
    }
}
