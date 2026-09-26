package com.saab.tv.data.repository

import com.saab.tv.data.model.stremio.MetaItem

internal object CinemetaRecommendationRanker {
    fun merge(
        catalogs: List<List<MetaItem>>,
        currentId: String,
        targetGenres: List<String>,
        maxItems: Int = 18
    ): List<MetaItem> {
        data class Candidate(
            val item: MetaItem,
            val firstSeen: Int,
            var catalogHits: Int = 0
        )

        val target = targetGenres.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val candidates = linkedMapOf<String, Candidate>()
        var seenIndex = 0

        catalogs.forEach { catalog ->
            catalog.forEach itemLoop@{ item ->
                val key = "${item.type.lowercase()}:${item.id.lowercase()}"
                if (item.id.equals(currentId, ignoreCase = true) || item.poster.isNullOrBlank()) {
                    return@itemLoop
                }
                val candidate = candidates.getOrPut(key) {
                    Candidate(item = item, firstSeen = seenIndex++)
                }
                candidate.catalogHits++
            }
        }

        return candidates.values.sortedWith(
            compareByDescending<Candidate> { candidate ->
                candidate.item.genres.orEmpty().count { it.trim().lowercase() in target }
            }.thenByDescending { it.catalogHits }
                .thenByDescending { it.item.imdbRating?.toDoubleOrNull() ?: 0.0 }
                .thenBy { it.firstSeen }
        ).take(maxItems).map { it.item }
    }
}
