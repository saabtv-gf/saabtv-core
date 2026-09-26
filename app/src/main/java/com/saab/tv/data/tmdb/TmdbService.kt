package com.saab.tv.data.tmdb

import android.content.Context
import android.util.Log
import com.saab.tv.data.security.SecurePreferences
import com.saab.tv.BuildConfig
import com.saab.tv.data.remote.TmdbApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TmdbService"

@Singleton
class TmdbService @Inject constructor(
    @ApplicationContext context: Context,
    private val tmdbApi: TmdbApiService
) {
    companion object {
        private const val PREFS_NAME = "tmdb_credentials"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_ALIAS = "saabtv_tmdb_master_key"
    }

    private val securePrefs by lazy { SecurePreferences.create(context, PREFS_NAME, KEY_ALIAS) }
    private val prefs get() = securePrefs.preferences

    private val imdbToTmdbCache = ConcurrentHashMap<String, Int>()
    private val tmdbToImdbCache = ConcurrentHashMap<Int, String>()
    private val cacheMutex = Mutex()

    /** Runtime configuration keeps self-built APKs usable without embedding a private key. */
    fun apiKey(): String = prefs.getString(KEY_API_KEY, null)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: BuildConfig.TMDB_API_KEY.trim()

    fun hasApiKey(): Boolean = apiKey().isNotEmpty()

    fun saveApiKey(apiKey: String): Boolean {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty() || !securePrefs.isPersistent) return false
        prefs.edit().putString(KEY_API_KEY, cleanKey).apply()
        clearCache()
        return true
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
        clearCache()
    }

    /**
     * Convert an IMDB ID to a TMDB ID.
     */
    suspend fun imdbToTmdb(imdbId: String, mediaType: String): Int? = withContext(Dispatchers.IO) {
        if (!imdbId.startsWith("tt")) return@withContext null

        imdbToTmdbCache[imdbId]?.let { return@withContext it }

        try {
            val response = tmdbApi.findByExternalId(
                externalId = imdbId,
                apiKey = apiKey(),
                externalSource = "imdb_id"
            )
            if (!response.isSuccessful) return@withContext null

            val body = response.body() ?: return@withContext null
            val normalizedType = normalizeMediaType(mediaType)
            val result = when (normalizedType) {
                "movie" -> body.movieResults?.firstOrNull()
                "tv" -> body.tvResults?.firstOrNull()
                else -> body.movieResults?.firstOrNull() ?: body.tvResults?.firstOrNull()
            }

            result?.let { found ->
                cacheMutex.withLock {
                    imdbToTmdbCache[imdbId] = found.id
                    tmdbToImdbCache[found.id] = imdbId
                }
                found.id
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up TMDB ID for $imdbId: ${e.message}")
            null
        }
    }

    /**
     * Convert a TMDB ID to an IMDB ID.
     */
    suspend fun tmdbToImdb(tmdbId: Int, mediaType: String): String? = withContext(Dispatchers.IO) {
        tmdbToImdbCache[tmdbId]?.let { return@withContext it }

        try {
            val normalizedType = normalizeMediaType(mediaType)
            val response = when (normalizedType) {
                "movie" -> tmdbApi.getMovieExternalIds(tmdbId, apiKey())
                else -> tmdbApi.getTvExternalIds(tmdbId, apiKey())
            }
            if (!response.isSuccessful) return@withContext null

            val body = response.body() ?: return@withContext null
            body.imdbId?.let { imdbId ->
                cacheMutex.withLock {
                    tmdbToImdbCache[tmdbId] = imdbId
                    imdbToTmdbCache[imdbId] = tmdbId
                }
                imdbId
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up IMDB ID for $tmdbId: ${e.message}")
            null
        }
    }

    /**
     * Get a TMDB ID from any video ID format (IMDB, TMDB, prefixed).
     */
    suspend fun ensureTmdbId(videoId: String, mediaType: String): String? {
        val cleanId = videoId
            .removePrefix("tmdb:")
            .removePrefix("movie:")
            .removePrefix("series:")

        // Strip Stremio-style suffixes like tt1234567:1:3
        val idPart = cleanId
            .substringBefore(':')
            .substringBefore('/')
            .trim()

        if (idPart.startsWith("tt")) {
            return imdbToTmdb(idPart, normalizeMediaType(mediaType))?.toString()
        }

        if (idPart.all { it.isDigit() }) return idPart

        Log.w(TAG, "Unknown video ID format: $videoId")
        return null
    }

    fun normalizeMediaType(mediaType: String): String {
        return when (mediaType.lowercase()) {
            "series", "tv", "show", "tvshow" -> "tv"
            "movie", "film" -> "movie"
            else -> mediaType.lowercase()
        }
    }

    fun clearCache() {
        imdbToTmdbCache.clear()
        tmdbToImdbCache.clear()
    }

    fun preCacheMapping(imdbId: String, tmdbId: Int) {
        imdbToTmdbCache[imdbId] = tmdbId
        tmdbToImdbCache[tmdbId] = imdbId
    }
}
