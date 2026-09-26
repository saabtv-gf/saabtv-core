package com.saab.tv.ui.player.base

/** Reserved namespace; a container's numeric track ID must never identify an addon subtitle. */
internal fun externalSubtitleTrackId(subtitleId: String): String {
    val clean = subtitleId.trim().ifBlank { "unknown" }
    return if (clean.startsWith("s:external:")) clean else "s:external:${clean.removePrefix("s:")}"
}

internal fun matchExternalSubtitleTrackId(formatId: String?, externalIds: Set<String>): String? {
    val id = formatId?.trim() ?: return null
    if (id in externalIds) return id
    // Merged media sources can prepend numeric child-source indices to Format.id.
    return externalIds.firstOrNull { candidate ->
        id.endsWith(":$candidate") && id.removeSuffix(candidate).matches(Regex("(?:[0-9]+:)+"))
    }
}
