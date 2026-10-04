package com.saab.tv.ui.trailer

import org.junit.Assert.*
import org.junit.Test

class TrailerPreviewPolicyTest {
    @Test fun searchNeverAutoplaysOnHover() {
        assertFalse(TrailerPreviewPolicy.allowsHover(true, false))
        assertFalse(TrailerPreviewPolicy.allowsHover(true, true))
    }
    @Test fun continueWatchingNeverAutoplaysOnHover() {
        assertFalse(TrailerPreviewPolicy.allowsHover(false, true))
    }
    @Test fun normalCataloguesAllowHoverPreviews() {
        assertTrue(TrailerPreviewPolicy.allowsHover(false, false))
    }
    @Test fun detailsAutoplayIsCancelledByAnyUserActivityButBrowsePreviewsAreUnaffected() {
        assertTrue(TrailerPreviewPolicy.allowsDetailsAutoplay(false, false))
        assertTrue(TrailerPreviewPolicy.allowsDetailsAutoplay(false, true))
        assertTrue(TrailerPreviewPolicy.allowsDetailsAutoplay(true, false))
        assertFalse(TrailerPreviewPolicy.allowsDetailsAutoplay(true, true))
    }
    @Test fun disabledAndBackgroundedPreviewsNeverStart() {
        assertFalse(TrailerPreviewPolicy.canStart(false, true, "movie:a", "movie:a", null, null))
        assertFalse(TrailerPreviewPolicy.canStart(true, false, "movie:a", "movie:a", null, null))
    }
    @Test fun backDoesNotRestartOriginalAfterNavigatingPreviewStrip() {
        assertFalse(TrailerPreviewPolicy.canStart(true, true, "movie:a", "movie:a", "movie:b", "movie:a"))
    }
    @Test fun newPosterFocusRearmsPreview() {
        assertTrue(TrailerPreviewPolicy.canStart(true, true, "movie:c", "movie:c", "movie:b", "movie:a"))
    }
    @Test fun keyboardOrMissingPosterCannotStartPreview() {
        assertFalse(TrailerPreviewPolicy.canStart(true, true, null, null, null, null))
    }
}
