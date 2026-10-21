package com.saab.tv.domain

/** TMDB recommendation rows that can be shown or hidden from Home Screen settings. */
data class TmdbHomeRail(
    val id: String,
    val title: String,
    val order: Int
)

object TmdbHomeRails {
    val all = listOf(
        TmdbHomeRail("tmdb-top-suggestions", "Top Suggestions", 10),
        TmdbHomeRail("tmdb-favorite-genres", "More in Your Favorite Genres", 13),
        TmdbHomeRail("tmdb-new-for-you", "New for You", 15),
        TmdbHomeRail("tmdb-popular-in-taste", "Popular in Your Taste", 16)
    )

    val defaultIds = all.map(TmdbHomeRail::id)
    private val knownIds = all.mapTo(hashSetOf(), TmdbHomeRail::id)

    /** Unknown IDs from newer/older releases are ignored without making settings unreadable. */
    fun parse(ids: String): List<String> = ids.split(',')
        .map(String::trim)
        .filter { it in knownIds }
        .distinct()

    fun encode(ids: List<String>): String = ids.filter { it in knownIds }.distinct().joinToString(",")

    fun move(ids: List<String>, id: String, direction: Int): List<String> {
        val from = ids.indexOf(id)
        val to = from + direction
        if (from < 0 || to !in ids.indices) return ids
        return ids.toMutableList().also { entries ->
            val item = entries.removeAt(from)
            entries.add(to, item)
        }
    }
}
