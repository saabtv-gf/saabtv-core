package com.saab.tv.ui.home

import com.saab.tv.data.model.stremio.MetaItem

internal enum class CatalogMediaFilter {
    ALL,
    MOVIES,
    SERIES
}

internal enum class CatalogGridSort {
    FEATURED,
    TITLE,
    NEWEST,
    RATING
}

/** Keeps provider order as the default while making large View All grids easy to browse. */
internal fun applyCatalogGridOptions(
    items: List<MetaItem>,
    filter: CatalogMediaFilter,
    sort: CatalogGridSort
): List<MetaItem> {
    val filtered = items.withIndex().filter { (_, item) ->
        when (filter) {
            CatalogMediaFilter.ALL -> true
            CatalogMediaFilter.MOVIES -> item.type.equals("movie", ignoreCase = true)
            CatalogMediaFilter.SERIES -> item.type.equals("series", ignoreCase = true) ||
                item.type.equals("tv", ignoreCase = true)
        }
    }

    val comparator = when (sort) {
        CatalogGridSort.FEATURED -> null
        CatalogGridSort.TITLE -> compareBy<IndexedValue<MetaItem>> { it.value.name.trim().lowercase() }
        CatalogGridSort.NEWEST -> compareByDescending<IndexedValue<MetaItem>> { it.value.releaseInfo?.yearOrNull() }
        CatalogGridSort.RATING -> compareByDescending<IndexedValue<MetaItem>> { it.value.imdbRating?.toDoubleOrNull() }
    }

    return (if (comparator == null) filtered else filtered.sortedWith(comparator.thenBy { it.index }))
        .map { it.value }
}

private fun String.yearOrNull(): Int? = Regex("\\d{4}").find(this)?.value?.toIntOrNull()
