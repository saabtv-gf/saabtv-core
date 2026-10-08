package com.saab.tv.ui.home

import com.saab.tv.data.model.stremio.MetaItem

/** Selects the freshest complete metadata for the currently focused card. */
internal object HomePreviewMetadataPolicy {
    fun shouldWarmFocusedMetadata(isRapidFocus: Boolean, elapsedMs: Long, minimumIntervalMs: Long): Boolean =
        !isRapidFocus || elapsedMs >= minimumIntervalMs

    fun select(
        current: MetaItem,
        enriched: MetaItem?,
        hero: MetaItem?,
        row: MetaItem?,
        history: MetaItem?
    ): MetaItem = enriched ?: hero ?: row ?: history ?: current
}
