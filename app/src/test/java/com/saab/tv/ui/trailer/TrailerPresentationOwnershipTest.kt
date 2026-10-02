package com.saab.tv.ui.trailer

import org.junit.Assert.*
import org.junit.Test

class TrailerPresentationOwnershipTest {
    @Test fun clearingInlineControlsDoesNotDropFullscreenPresentation() {
        val owner = Any()
        try {
            InlineTrailerAnchor.fullscreen(owner, true)
            InlineTrailerAnchor.clearInlineSession(owner)
            assertTrue(InlineTrailerAnchor.fullscreenActive)
            InlineTrailerAnchor.fullscreen(owner, true)
            assertTrue(InlineTrailerAnchor.fullscreenActive)
            InlineTrailerAnchor.clearSession(owner)
            assertFalse(InlineTrailerAnchor.fullscreenActive)
        } finally { InlineTrailerAnchor.clearSession(owner) }
    }

    @Test fun disposingAnOldHostCannotDismissTheNewHost() {
        val old = Any()
        val current = Any()
        try {
            InlineTrailerAnchor.fullscreen(old, true)
            InlineTrailerAnchor.fullscreen(current, true)
            InlineTrailerAnchor.clearSession(old)
            assertTrue(InlineTrailerAnchor.fullscreenActive)
        } finally { InlineTrailerAnchor.clearSession(current) }
    }
}
