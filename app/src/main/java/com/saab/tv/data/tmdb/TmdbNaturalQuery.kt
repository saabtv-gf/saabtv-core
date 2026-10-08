package com.saab.tv.data.tmdb

data class TmdbNaturalQuery(
    val mediaType: String?,
    val genreIds: String?,
    val originalLanguage: String?,
    val sortBy: String,
    val tvGenreIds: String? = null,
    val releaseDateGte: String? = null,
    val releaseDateLte: String? = null
) {
    companion object {
        private val languages = mapOf(
            "english" to "en", "telugu" to "te", "kannada" to "kn",
            "malayalam" to "ml", "hindi" to "hi", "tamil" to "ta"
        )

        fun parse(query: String): TmdbNaturalQuery? {
            val words = query.lowercase().replace(Regex("[^a-z0-9]+"), " ")
                .trim().split(Regex("\\s+")).filter(String::isNotBlank).toSet()
            if (words.isEmpty()) return null

            val mediaType = when {
                words.any { it in setOf("series", "show", "shows", "tv") } -> "tv"
                words.any { it in setOf("movie", "movies", "film", "films") } -> "movie"
                else -> null
            }
            val language = languages.entries.firstOrNull { it.key in words }?.value
            val genreName = when {
                "thriller" in words -> "thriller"
                "action" in words -> "action"
                "adventure" in words -> "adventure"
                "comedy" in words -> "comedy"
                "drama" in words -> "drama"
                "horror" in words -> "horror"
                "romance" in words -> "romance"
                "crime" in words -> "crime"
                "mystery" in words -> "mystery"
                "animation" in words || "animated" in words -> "animation"
                "documentary" in words -> "documentary"
                "fantasy" in words -> "fantasy"
                "scifi" in words || ("science" in words && "fiction" in words) -> "scifi"
                else -> null
            }
            val movieGenre = when (genreName) {
                "thriller" -> "53"
                "action" -> "28"
                "adventure" -> "12"
                "comedy" -> "35"
                "drama" -> "18"
                "horror" -> "27"
                "romance" -> "10749"
                "crime" -> "80"
                "mystery" -> "9648"
                "animation" -> "16"
                "documentary" -> "99"
                "fantasy" -> "14"
                "scifi" -> "878"
                else -> null
            }
            val tvGenre = when (genreName) {
                "thriller" -> "9648|80"
                "action", "adventure" -> "10759"
                "comedy" -> "35"
                "drama" -> "18"
                "horror" -> "27"
                "romance" -> "10749"
                "crime" -> "80"
                "mystery" -> "9648"
                "animation" -> "16"
                "documentary" -> "99"
                "fantasy", "scifi" -> "10765"
                else -> null
            }
            val genre = if (mediaType == "tv") tvGenre else movieGenre
            val isPopular = words.any { it in setOf("popular", "popularly", "trending", "trendingly") }
            if (mediaType == null && language == null && genre == null && !isPopular) return null
            return TmdbNaturalQuery(mediaType, genre, language,
                if (isPopular) "popularity.desc" else "vote_average.desc",
                tvGenreIds = if (mediaType == null) tvGenre else null)
        }
    }
}
