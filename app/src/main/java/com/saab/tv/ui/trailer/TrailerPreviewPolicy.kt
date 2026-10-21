package com.saab.tv.ui.trailer

import com.saab.tv.data.profile.TrailerPreviewSettings

internal object TrailerPreviewPolicy {
    const val DEFAULT_START_POSITION_MS = 10_000L

    fun initialStartPositionMs(currentPositionMs: Long): Long =
        currentPositionMs.takeIf { it > 0L } ?: DEFAULT_START_POSITION_MS

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

    fun volume(muted: Boolean): Float = if (muted) 0f else 1f
}

/** Rejects late extraction results after focus, route, or preview ownership changes. */
internal class TrailerPreviewRequestGate {
    data class Request internal constructor(val generation: Long, val presentationKey: String)

    private var generation = 0L
    private var currentPresentationKey: String? = null

    fun begin(presentationKey: String?, allowed: Boolean): Request? {
        generation += 1
        currentPresentationKey = presentationKey.takeIf { allowed && !it.isNullOrBlank() }
        val current = currentPresentationKey ?: return null
        return Request(generation, current)
    }

    fun invalidate() {
        generation += 1
        currentPresentationKey = null
    }

    fun accepts(request: Request): Boolean =
        request.generation == generation && request.presentationKey == currentPresentationKey
}
