package com.saab.tv.ui.trailer

internal object TrailerPreviewPolicy {
    fun allowsHover(inSearch: Boolean, inContinueWatching: Boolean): Boolean =
        !inSearch && !inContinueWatching

    fun canStart(enabled: Boolean, foreground: Boolean, target: String?, origin: String?,
                 dismissed: String?, dismissedOrigin: String?): Boolean =
        enabled && foreground && target != null && origin != null &&
            target != dismissed && origin != dismissedOrigin
}
