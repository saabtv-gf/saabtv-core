package com.saab.tv.ui.navigation

import com.saab.tv.data.model.stremio.MetaItem

/** One selected catalog identity copied into the shell's details/playback route state. */
internal data class CatalogDetailsSelection(
    val contentId: String,
    val contentType: String,
    val title: String,
    val poster: String,
    val background: String,
    val logo: String,
    val addonBaseUrl: String?,
    val playbackId: String,
    val playbackType: String,
    val playbackTitle: String,
    val playbackPoster: String,
    val returnDestination: ShellView = ShellView.Menu
)

internal fun MetaItem.toCatalogDetailsSelection(returnDestination: ShellView = ShellView.Menu): CatalogDetailsSelection = CatalogDetailsSelection(
    contentId = id,
    contentType = type,
    title = name,
    poster = poster.orEmpty(),
    background = background.orEmpty(),
    logo = logo.orEmpty(),
    addonBaseUrl = addonBaseUrl,
    playbackId = id,
    playbackType = type,
    playbackTitle = name,
    playbackPoster = poster.orEmpty(),
    returnDestination = returnDestination
)

internal fun MetaItem.toCatalogPlaybackSelection(currentView: ShellView, previousView: ShellView): CatalogDetailsSelection =
    toCatalogDetailsSelection(BrowseReturnPolicy.forPlayback(currentView, previousView))

internal fun catalogDetailsRoute(
    type: String,
    id: String,
    addonBaseUrl: String? = null,
    resumePlaybackHint: String? = null
): String {
    fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
    return "detail/${encode(type)}/${encode(id)}?addon=${encode(addonBaseUrl.orEmpty())}&resume=${encode(resumePlaybackHint.orEmpty())}"
}

internal fun MetaItem.trailerHandoffRoute(
    currentType: String,
    currentId: String,
    currentAddonBaseUrl: String? = null,
    currentResumePlaybackHint: String? = null
): String? {
    val selectedAddon = addonBaseUrl?.takeIf(String::isNotBlank)
    val currentAddon = currentAddonBaseUrl?.takeIf(String::isNotBlank)
    val selectedResume = resumePlaybackId?.takeIf(String::isNotBlank)
    val currentResume = currentResumePlaybackHint?.takeIf(String::isNotBlank)
    if (type.equals(currentType, ignoreCase = true) && id == currentId &&
        selectedAddon == currentAddon && selectedResume == currentResume
    ) return null
    return catalogDetailsRoute(type, id, selectedAddon, selectedResume)
}

internal fun nestedCatalogDetailsSelection(type: String, id: String): CatalogDetailsSelection =
    MetaItem(id = id, type = type, name = "").toCatalogDetailsSelection()
