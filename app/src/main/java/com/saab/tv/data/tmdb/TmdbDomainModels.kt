package com.saab.tv.data.tmdb

/**
 * Enriched metadata from TMDB for a movie or TV show.
 */
data class TmdbEnrichment(
    val localizedTitle: String?,
    val description: String?,
    val genres: List<String>,
    val backdrop: String?,
    val logo: String?,
    val poster: String?,
    val directorMembers: List<TmdbCastInfo>,
    val writerMembers: List<TmdbCastInfo>,
    val castMembers: List<TmdbCastInfo>,
    val releaseInfo: String?,
    val rating: Double?,
    val runtimeMinutes: Int?,
    val productionCompanies: List<TmdbCompanyInfo>,
    val networks: List<TmdbCompanyInfo>,
    val ageRating: String?,
    val status: String?,
    val countries: List<String>?,
    val language: String?,
    val collectionId: Int?,
    val collectionName: String?
)

/**
 * Episode-level enrichment from TMDB.
 */
data class TmdbEpisodeEnrichment(
    val title: String?,
    val overview: String?,
    val thumbnail: String?,
    val airDate: String?,
    val runtimeMinutes: Int?
)

/**
 * Cast/crew member info.
 */
data class TmdbCastInfo(
    val name: String,
    val character: String?,
    val photo: String?,
    val tmdbId: Int?
)

/**
 * Production company or network info.
 */
data class TmdbCompanyInfo(
    val name: String,
    val logo: String?,
    val tmdbId: Int?
)

/**
 * Minimal preview for recommendations, collections, and filmographies.
 */
data class TmdbMetaPreview(
    val tmdbId: Int,
    val type: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val description: String?,
    val releaseInfo: String?,
    val rating: Double?
)

/**
 * Person detail with filmography.
 */
data class TmdbPersonDetail(
    val tmdbId: Int,
    val name: String,
    val biography: String?,
    val birthday: String?,
    val deathday: String?,
    val placeOfBirth: String?,
    val profilePhoto: String?,
    val knownFor: String?,
    val movieCredits: List<TmdbMetaPreview>,
    val tvCredits: List<TmdbMetaPreview>
)

/**
 * Company/Network detail with discover rails.
 */
data class TmdbEntityDetail(
    val tmdbId: Int,
    val kind: String, // "company" or "network"
    val name: String,
    val logo: String?,
    val originCountry: String?,
    val headquarters: String?,
    val description: String?
)

data class TmdbDiscoverRail(
    val mediaType: String, // "movie" or "tv"
    val railType: String, // "popular", "top_rated", "recent"
    val items: List<TmdbMetaPreview>,
    val currentPage: Int = 1,
    val hasMore: Boolean = false
)

/**
 * Video/trailer info.
 */
data class TmdbVideoInfo(
    val name: String,
    val key: String,
    val type: String,
    val thumbnail: String
)
