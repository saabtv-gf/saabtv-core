package com.saab.tv.ui.trailer

import com.saab.tv.data.profile.TrailerPreviewSettings
import org.junit.Assert.*
import org.junit.Test

class TrailerPreviewPolicyTest {
    @Test fun newTrailerPreviewsSkipFirstTenSecondsButRenditionFallbackResumesInPlace() {
        assertEquals(10_000L, TrailerPreviewPolicy.initialStartPositionMs(0L))
        assertEquals(18_500L, TrailerPreviewPolicy.initialStartPositionMs(18_500L))
    }
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
    @Test fun detailsAutoplayForcesSoundAndIgnoresTheGlobalEnabledToggle() {
        val effective = TrailerPreviewPolicy.detailsAutoplaySettings(
            TrailerPreviewSettings(enabled = false, delaySeconds = 10, muted = true, presentation = "inline")
        )
        assertTrue(effective.enabled)
        assertFalse(effective.muted)
        assertEquals("fullscreen", effective.presentation)
        assertEquals(10, effective.delaySeconds)
    }
    @Test fun recommendationCardsKeepGlobalTrailerPresentationAndMuteSettings() {
        val global = TrailerPreviewSettings(enabled = true, delaySeconds = 8, muted = true, presentation = "inline")
        assertEquals(global, TrailerPreviewPolicy.settingsForPreview(global, isDetailsTitle = false))
    }
    @Test fun onlyTheDetailsTitleTrailerIsForcedFullscreen() {
        val global = TrailerPreviewSettings(enabled = false, delaySeconds = 10, muted = true, presentation = "inline")
        val detailsTitle = TrailerPreviewPolicy.settingsForPreview(global, isDetailsTitle = true)
        assertTrue(detailsTitle.enabled)
        assertFalse(detailsTitle.muted)
        assertEquals("fullscreen", detailsTitle.presentation)
        assertEquals(10, detailsTitle.delaySeconds)
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

    @Test fun volumeTracksMuteStateInsteadOfOnlyChangingTheButtonLabel() {
        assertEquals(0f, TrailerPreviewPolicy.volume(muted = true), 0f)
        assertEquals(1f, TrailerPreviewPolicy.volume(muted = false), 0f)
    }

    @Test fun fullscreenPromotionKeepsTheActivePreviewRequestCurrent() {
        val gate = TrailerPreviewRequestGate()
        val request = gate.begin("series:related", allowed = true)!!

        // Presentation changes are not identity changes; promotion must not
        // cause extraction/player creation to restart.
        assertTrue(gate.accepts(request))
    }

    @Test fun routeOrFocusChangeRejectsLateTrailerExtraction() {
        val gate = TrailerPreviewRequestGate()
        val original = gate.begin("movie:old", allowed = true)!!
        val next = gate.begin("movie:new", allowed = true)!!

        assertFalse(gate.accepts(original))
        assertTrue(gate.accepts(next))
    }

    @Test fun disablingOrDismissingPreviewInvalidatesPendingResolution() {
        val gate = TrailerPreviewRequestGate()
        val request = gate.begin("movie:one", allowed = true)!!
        gate.begin("movie:one", allowed = false)
        assertFalse(gate.accepts(request))

        val next = gate.begin("movie:one", allowed = true)!!
        gate.invalidate()
        assertFalse(gate.accepts(next))
    }

    @Test fun missingTargetInvalidatesAnEarlierRequest() {
        val gate = TrailerPreviewRequestGate()
        val request = gate.begin("movie:one", allowed = true)!!

        assertNull(gate.begin(null, allowed = true))
        assertFalse(gate.accepts(request))
    }
}
