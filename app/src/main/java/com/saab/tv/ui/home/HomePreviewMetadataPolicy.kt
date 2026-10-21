package com.saab.tv.ui.home

import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.CategoryRow
import com.saab.tv.domain.HomeRowItem
import com.saab.tv.domain.schema.CatalogCardProjection
import com.saab.tv.domain.schema.CanonicalFieldResolver
import com.saab.tv.domain.schema.CanonicalTextField
import com.saab.tv.domain.schema.MetadataSource
import com.saab.tv.domain.schema.TextFieldCandidate
import com.saab.tv.domain.schema.canonicalPresentationKey
import com.saab.tv.domain.schema.metadataAuthorityRank
import com.saab.tv.domain.schema.metadataSource

internal data class HomePreviewMetadataIndex(
    val rowsByKey: Map<String, MetaItem>
)

/** Indexes the large catalog source once; small history/hero lists stay scan-on-demand. */
internal fun homePreviewMetadataIndex(
    rows: List<HomeRowItem>
): HomePreviewMetadataIndex = HomePreviewMetadataIndex(
    rowsByKey = firstItemByCanonicalKey(
        rows.asSequence().filterIsInstance<CategoryRow>()
            .flatMap { row -> row.items.asSequence().map(CatalogCardProjection::asActionItem) }
    )
)

private fun firstItemByCanonicalKey(items: Sequence<MetaItem>): Map<String, MetaItem> = buildMap {
    items.forEach { item ->
        val key = item.canonicalPresentationKey()
        if (key !in this) put(key, item)
    }
}

/** Selects the freshest complete metadata for the currently focused card. */
internal object HomePreviewMetadataPolicy {
    data class InitialArtworkUrls(val poster: String? = null, val backdrop: String? = null, val logo: String? = null)

    /** Prefetch the same assets the selected card layout will render, avoiding wasted poster downloads in landscape mode. */
    fun initialArtwork(item: MetaItem, isLandscape: Boolean): InitialArtworkUrls =
        if (isLandscape) InitialArtworkUrls(backdrop = item.background, logo = item.logo)
        else InitialArtworkUrls(poster = item.poster)

    fun shouldWarmFocusedMetadata(isRapidFocus: Boolean, elapsedMs: Long, minimumIntervalMs: Long): Boolean =
        !isRapidFocus || elapsedMs >= minimumIntervalMs

    fun select(
        current: MetaItem,
        enriched: MetaItem?,
        hero: MetaItem?,
        row: MetaItem?,
        history: MetaItem?
    ): MetaItem {
        val eligible = listOfNotNull(
            hero?.let { it to it.metadataSource() },
            row?.let { it to it.metadataSource() },
            history?.let { it to it.metadataSource() },
            current to current.metadataSource()
        ).filter { (item, _) -> item.canonicalPresentationKey() == current.canonicalPresentationKey() }
        val candidates = eligible.mapIndexedNotNull { order, (item, source) ->
            listOfNotNull(
                candidate(CanonicalTextField.TITLE, item.name, source, order),
                candidate(CanonicalTextField.SYNOPSIS, item.description, source, order),
                candidate(CanonicalTextField.POSTER, item.poster, source, order),
                candidate(CanonicalTextField.BACKDROP, item.background, source, order),
                candidate(CanonicalTextField.LOGO, item.logo, source, order)
            )
        }.flatten().toMutableList()
        // `enriched` is a merged snapshot, not a raw provider record. Only changed values
        // were supplied by its fallback source, whose origin is retained on the item.
        enriched?.takeIf { it.canonicalPresentationKey() == current.canonicalPresentationKey() }?.let { item ->
            val order = eligible.size
            val fallbackSource = item.metadataSource().takeIf { it == MetadataSource.CINEMETA }
                ?: MetadataSource.TMDB
            listOfNotNull(
                candidate(CanonicalTextField.TITLE, item.name.takeIf { it != current.name }, fallbackSource, order),
                candidate(CanonicalTextField.SYNOPSIS, item.description.takeIf { it != current.description }, fallbackSource, order),
                candidate(CanonicalTextField.POSTER, item.poster.takeIf { it != current.poster }, fallbackSource, order),
                candidate(CanonicalTextField.BACKDROP, item.background.takeIf { it != current.background }, fallbackSource, order),
                candidate(CanonicalTextField.LOGO, item.logo.takeIf { it != current.logo }, fallbackSource, order)
            ).forEach(candidates::add)
        }
        val now = System.currentTimeMillis()
        fun selected(field: CanonicalTextField, fallback: String?): String? {
            val selection = CanonicalFieldResolver.select(
                field, candidates, requestedLanguage = null, nowMillis = now
            )
            return if (selection == null) fallback else selection.value
        }
        val titleSelection = CanonicalFieldResolver.select(
            CanonicalTextField.TITLE, candidates, requestedLanguage = null, nowMillis = now
        )
        val title = if (titleSelection == null) current.name else titleSelection.value

        fun <T : Any> selectValue(value: (MetaItem) -> T?, isUsable: (T) -> Boolean): T? {
            val providerValues = eligible.mapIndexedNotNull { order, (item, source) ->
                value(item)?.takeIf(isUsable)?.let { SourcedValue(it, source, order) }
            }
            val enrichedItem = enriched?.takeIf { it.canonicalPresentationKey() == current.canonicalPresentationKey() }
            val fallbackSource = if (enrichedItem?.metadataSource() == MetadataSource.CINEMETA) {
                MetadataSource.CINEMETA
            } else {
                MetadataSource.TMDB
            }
            val fallbackValue = enrichedItem
                ?.let { item -> value(item)?.takeIf(isUsable)?.takeIf { it != value(current) }
                    ?.let { enrichedValue -> SourcedValue(enrichedValue, fallbackSource, eligible.size) } }
            return (providerValues + listOfNotNull(fallbackValue))
                .minWithOrNull(compareBy<SourcedValue<T>> { -metadataAuthorityRank(it.source) }.thenBy { it.order })
                ?.value
        }

        val merged = current.copy(
            name = title,
            description = selected(CanonicalTextField.SYNOPSIS, current.description),
            poster = selected(CanonicalTextField.POSTER, current.poster),
            background = selected(CanonicalTextField.BACKDROP, current.background),
            logo = selected(CanonicalTextField.LOGO, current.logo),
            releaseInfo = selectValue(MetaItem::releaseInfo) { it.isNotBlank() } ?: current.releaseInfo,
            imdbRating = selectValue(MetaItem::imdbRating) { it.isNotBlank() } ?: current.imdbRating,
            runtime = selectValue(MetaItem::runtime) { it.isNotBlank() } ?: current.runtime,
            genres = selectValue(MetaItem::genres) { it.isNotEmpty() } ?: current.genres
        )
        // Scalar facts (rating/runtime/genres) can be the only missing fields in
        // an otherwise text-empty card. Keep the original instance only when the
        // merge truly had nothing to add.
        return if (merged == current) current else merged
    }

    private data class SourcedValue<T : Any>(val value: T, val source: MetadataSource, val order: Int)

    private fun candidate(
        field: CanonicalTextField,
        value: String?,
        source: MetadataSource,
        order: Int
    ): TextFieldCandidate? = value?.takeIf(String::isNotBlank)?.let {
        TextFieldCandidate(
            field = field,
            value = it,
            source = source,
            observedAtMillis = 0L,
            identityVerified = true,
            providerOrder = order
        )
    }
}
