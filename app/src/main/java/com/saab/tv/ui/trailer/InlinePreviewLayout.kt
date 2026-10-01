package com.saab.tv.ui.trailer

/** Overlay placement never changes shelf sizes or grid spans. */
internal object InlinePreviewLayout {
    data class Bounds(val left: Float, val top: Float, val width: Float, val height: Float)

    fun bounds(anchor: Bounds?, viewportWidth: Float, viewportHeight: Float, density: Float): Bounds {
        val margin = 16f * density
        val footer = 72f * density
        val width = minOf(360f * density, (viewportWidth - margin * 2).coerceAtLeast(1f),
            (viewportHeight - margin * 2 - footer).coerceAtLeast(1f) * 16f / 9f)
        val height = width * 9f / 16f + footer
        val centerX = anchor?.let { it.left + it.width / 2 } ?: (viewportWidth / 2)
        val centerY = anchor?.let { it.top + it.height / 2 } ?: (viewportHeight / 2)
        val left = (centerX - width / 2).coerceIn(margin, (viewportWidth - width - margin).coerceAtLeast(margin))
        val top = (centerY - height / 2).coerceIn(margin, (viewportHeight - height - margin).coerceAtLeast(margin))
        return Bounds(left, top, width, height)
    }

    // An unavailable anchor must never override a user's inline preference.
    fun isInline(presentation: String, explicitlyExpanded: Boolean): Boolean =
        presentation != "fullscreen" && !explicitlyExpanded
}
