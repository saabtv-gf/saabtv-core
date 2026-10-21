package com.saab.tv.ui.search

import com.saab.tv.data.model.stremio.MetaItem

enum class SearchMediaFilter { MOVIES, SERIES }

internal data class SearchInterpretationChip(
    val phrase: String,
    val label: String = phrase.replaceFirstChar { it.uppercase() }
)

/** Resolves a saved TV focus identity using an index built once for the result batch. */
internal fun resolveSearchFocusItem(
    items: List<MetaItem>,
    indexByCanonicalKey: Map<String, Int>,
    requestedKey: String?,
    fallback: MetaItem? = null
): MetaItem? = requestedKey
    ?.let(indexByCanonicalKey::get)
    ?.let(items::getOrNull)
    ?: fallback

/** Only exposes terms understood by the existing TMDB natural-query parser. */
internal fun searchInterpretationChips(query: String): List<SearchInterpretationChip> {
    if (com.saab.tv.data.tmdb.TmdbNaturalQuery.parse(query) == null) return emptyList()
    val supportedTerms = listOf(
        "science fiction", "english", "telugu", "kannada", "malayalam", "hindi", "tamil",
        "popularly", "trendingly", "movies", "movie", "films", "film", "series", "shows", "show", "tv",
        "thriller", "adventure", "comedy", "documentary", "animation", "animated", "fantasy", "romance",
        "mystery", "horror", "drama", "action", "crime", "scifi", "popular", "trending"
    )
    return supportedTerms.mapNotNull { phrase ->
        val match = Regex("(?i)(?<![a-z0-9])${Regex.escape(phrase)}(?![a-z0-9])").find(query)
        match?.let { it.range.first to SearchInterpretationChip(phrase) }
    }.sortedBy { it.first }.map { it.second }
}

/** Removes one displayed interpretation while preserving the user's remaining query text. */
internal fun removeSearchInterpretation(query: String, phrase: String): String =
    query.replace(
        Regex("(?i)(?<![a-z0-9])${Regex.escape(phrase)}(?![a-z0-9])"),
        " "
    ).replace(Regex("\\s+"), " ").trim()
