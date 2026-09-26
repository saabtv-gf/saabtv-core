package com.saab.tv.data.model.trakt

import com.google.gson.annotations.SerializedName

/**
 * A single item in the Trakt watchlist response.
 * GET /sync/watchlist returns an array of these.
 */
data class TraktWatchlistItem(
    val rank: Int?,
    val id: Long?,
    @SerializedName("listed_at") val listedAt: String?,
    val type: String,         // "movie" or "show"
    val movie: TraktMovie?,
    val show: TraktShow?
)

data class TraktMovie(
    val title: String?,
    val year: Int?,
    val ids: TraktIds
)

data class TraktShow(
    val title: String?,
    val year: Int?,
    val ids: TraktIds
)

data class TraktIds(
    val trakt: Int? = null,
    val slug: String? = null,
    val imdb: String? = null,
    val tmdb: Int? = null
)

/**
 * Request body for POST /sync/watchlist and /sync/watchlist/remove.
 */
data class TraktSyncRequest(
    val movies: List<TraktSyncItem>? = null,
    val shows: List<TraktSyncItem>? = null
)

data class TraktSyncItem(
    val ids: TraktIds,
    val seasons: List<TraktSyncSeason>? = null
)

data class TraktSyncSeason(
    val number: Int,
    val episodes: List<TraktSyncEpisode>
)

data class TraktSyncEpisode(
    val number: Int
)

/**
 * Response from POST /sync/watchlist and /sync/watchlist/remove.
 */
data class TraktSyncResponse(
    val added: TraktSyncCounts?,
    val deleted: TraktSyncCounts?,
    val existing: TraktSyncCounts?,
    @SerializedName("not_found") val notFound: TraktSyncNotFound?
)

data class TraktSyncCounts(
    val movies: Int?,
    val shows: Int?,
    val episodes: Int?
)

data class TraktSyncNotFound(
    val movies: List<Any>?,
    val shows: List<Any>?
)

/**
 * Response from GET /sync/last_activities.
 * We only parse the fields we care about — Trakt returns many more.
 */
data class TraktLastActivities(
    val watchlist: TraktActivityTimestamp?,
    val episodes: TraktActivityTimestamps?,
    val movies: TraktActivityTimestamps?
)

data class TraktActivityTimestamp(
    @SerializedName("updated_at") val updatedAt: String?
)

data class TraktActivityTimestamps(
    @SerializedName("watched_at") val watchedAt: String? = null,
    @SerializedName("paused_at") val pausedAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null
)

/**
 * Response from GET /shows/{id}/progress/watched
 */
data class TraktShowProgress(
    val aired: Int?,
    val completed: Int?,
    @SerializedName("next_episode") val nextEpisode: TraktProgressEpisode?
)

data class TraktProgressEpisode(
    val season: Int,
    val number: Int,
    val title: String?,
    val ids: TraktIds? = null,
    @SerializedName("first_aired") val firstAired: String? = null
)

/**
 * Response from GET /sync/playback — items the user is currently watching.
 */
data class TraktPlaybackItem(
    val id: Long?,
    val progress: Float,          // 0.0–100.0
    val type: String,             // "movie" or "episode"
    @SerializedName("paused_at") val pausedAt: String?,
    val movie: TraktMovie?,
    val show: TraktShow?,
    val episode: TraktPlaybackEpisode?
)

data class TraktPlaybackEpisode(
    val season: Int,
    val number: Int,
    val title: String?,
    val ids: TraktIds? = null
)

/**
 * Response from GET /sync/watched/movies
 */
data class TraktWatchedMovie(
    val movie: TraktMovie,
    @SerializedName("last_watched_at") val lastWatchedAt: String?
)

/**
 * Response from GET /sync/watched/shows
 */
data class TraktWatchedShow(
    val show: TraktShow,
    @SerializedName("last_watched_at") val lastWatchedAt: String?,
    val seasons: List<TraktWatchedSeason>?
)

data class TraktWatchedSeason(
    val number: Int,
    val episodes: List<TraktWatchedEpisode>?
)

data class TraktWatchedEpisode(
    val number: Int,
    @SerializedName("last_watched_at") val lastWatchedAt: String?
)

/**
 * Request body for POST /scrobble/start, /scrobble/pause, /scrobble/stop.
 */
data class TraktScrobbleRequest(
    val movie: TraktScrobbleMovie? = null,
    val show: TraktScrobbleShow? = null,
    val episode: TraktScrobbleEpisode? = null,
    val progress: Float
)

data class TraktScrobbleMovie(
    val ids: TraktIds
)

data class TraktScrobbleShow(
    val ids: TraktIds
)

data class TraktScrobbleEpisode(
    val season: Int,
    val number: Int
)
