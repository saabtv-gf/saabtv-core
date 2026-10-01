package com.saab.tv.ui.trailer

import org.junit.Assert.*
import org.junit.Test

class InlinePreviewLayoutTest {
    @Test fun inlineDoesNotDependOnAnchorAvailability() {
        assertTrue(InlinePreviewLayout.isInline("inline", false))
        val b = InlinePreviewLayout.bounds(null, 960f, 540f, 1f)
        assertEquals(360f, b.width, 0.01f)
        assertEquals(480f, b.left + b.width / 2, 0.01f)
    }
    @Test fun fullscreenActionAndPreferenceExplicitlyLeaveInlineMode() {
        assertFalse(InlinePreviewLayout.isInline("inline", true))
        assertFalse(InlinePreviewLayout.isInline("fullscreen", false))
        assertTrue(InlinePreviewLayout.isInline("inline", false)) // New title starts inline again.
    }
    @Test fun overlayStaysInsideScreenAndHasLandscapeVideoAboveFooter() {
        for (x in listOf(0f, 900f)) {
            val b = InlinePreviewLayout.bounds(InlinePreviewLayout.Bounds(x, 500f, 140f, 210f), 960f, 540f, 1f)
            assertTrue(b.left >= 16f && b.top >= 16f)
            assertTrue(b.left + b.width <= 944f && b.top + b.height <= 524f)
            assertEquals(16f / 9f, b.width / (b.height - 72f), 0.001f)
        }
    }
}
