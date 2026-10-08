package com.saab.tv.ui.trailer

import com.saab.tv.data.profile.TrailerPreviewSettings

internal object TrailerPreviewPolicy {
    fun allowsHover(inSearch: Boolean, inContinueWatching: Boolean): Boolean =
        !inSearch && !inContinueWatching

    fun allowsDetailsAutoplay(isDetailsPage: Boolean, userActivityDetected: Boolean): Boolean =
        !isDetailsPage || !userActivityDetected

    fun detailsAutoplaySettings(settings: TrailerPreviewSettings): TrailerPreviewSettings =
        settings.copy(enabled = true, muted = false, presentation = "fullscreen")

    fun settingsForPreview(settings: TrailerPreviewSettings, isDetailsTitle: Boolean): TrailerPreviewSettings =
        if (isDetailsTitle) detailsAutoplaySettings(settings) else settings

    fun canStart(enabled: Boolean, foreground: Boolean, target: String?, origin: String?,
                 dismissed: String?, dismissedOrigin: String?): Boolean =
        enabled && foreground && target != null && origin != null &&
            target != dismissed && origin != dismissedOrigin
}
