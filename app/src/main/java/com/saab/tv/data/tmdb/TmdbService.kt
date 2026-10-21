package com.saab.tv.data.tmdb

import android.content.Context
import android.util.Log
import android.util.LruCache
import com.saab.tv.data.security.SecurePreferences
import com.saab.tv.data.cache.SingleFlight
import com.saab.tv.data.cache.metadataRequestScope
import com.saab.tv.data.repository.CanonicalIdentityStore
import com.saab.tv.data.repository.CanonicalMetadataRepository
import com.saab.tv.BuildConfig
import com.saab.tv.data.remote.TmdbApiService
import com.saab.tv.domain.schema.ContentIdentity
import com.saab.tv.domain.schema.ContentKind
import com.saab.tv.domain.schema.IdentityNamespace
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import java.io.IOException

private const val TAG = "TmdbService"

@Singleton
class TmdbService private constructor(
    @ApplicationContext context: Context,
    private val tmdbApi: TmdbApiService,
    private val canonicalIdentityStore: CanonicalIdentityStore
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        tmdbApi: TmdbApiService,
        canonicalMetadataRepository: CanonicalMetadataRepository
    ) : this(context, tmdbApi, canonicalMetadataRepository as CanonicalIdentityStore)

    /** Lightweight constructor for deterministic service tests without a Room graph. */
    constructor(@ApplicationContext context: Context, tmdbApi: TmdbApiService) :
        this(context, tmdbApi, com.saab.tv.data.repository.NoopCanonicalIdentityStore)

    companion object {
        private const val PREFS_NAME = "tmdb_credentials"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_ALIAS = "saabtv_tmdb_master_key"
    }

    private val securePrefs by lazy { SecurePreferences.create(context, PREFS_NAME, KEY_ALIAS) }
    private val prefs get() = securePrefs.preferences

    // Keys include media kind: TMDB 123 can identify both a movie and a series.
    private val imdbToTmdbCache = LruCache<String, Int>(256)
    private val tmdbToImdbCache = LruCache<String, String>(256)
    private val cacheMutex = Mutex()
    private val requestScope = metadataRequestScope()
    private val identityLookupPermits = kotlinx.coroutines.sync.Semaphore(2)
    private val imdbLookupFlights = SingleFlight<String, Int?>(requestScope, sharedPermits = identityLookupPermits)
    private val tmdbLookupFlights = SingleFlight<String, String?>(requestScope, sharedPermits = identityLookupPermits)

    /** Runtime configuration keeps self-built APKs usable without embedding a private key. */
    fun apiKey(): String = prefs.getString(KEY_API_KEY, null)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: BuildConfig.TMDB_API_KEY.trim()

    fun hasApiKey(): Boolean = apiKey().isNotEmpty()

    /** Local-only canonical key lookup used to merge rails without adding network latency. */
    suspend fun canonicalIdentityKey(identity: ContentIdentity): String =
        runCatching { canonicalIdentityStore.canonicalKey(identity) }.getOrDefault(identity.stableKey)

    fun saveApiKey(apiKey: String): Boolean {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty() || !securePrefs.isPersistent) {
            com.saab.tv.AppDiagnostics.event("TMDB", "API Key Save Rejected", "empty=${cleanKey.isEmpty()} secureStorage=${securePrefs.isPersistent}")
            return false
        }
        prefs.edit().putString(KEY_API_KEY, cleanKey).apply()
        clearCache()
        com.saab.tv.AppDiagnostics.event("TMDB", "API Key Saved")
        return true
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
        clearCache()
        com.saab.tv.AppDiagnostics.event("TMDB", "API Key Removed")
    }

    /**
     * Convert an IMDB ID to a TMDB ID.
     */
    suspend fun imdbToTmdb(imdbId: String, mediaType: String): Int? {
        val kind = ContentKind.fromProvider(mediaType)?.takeIf { it == ContentKind.MOVIE || it == ContentKind.SERIES }
            ?: return null
        val imdbIdentity = ContentIdentity.imdb(kind, imdbId) ?: return null
        val cleanImdbId = imdbIdentity.externalId
        val imdbKey = imdbIdentity.stableKey
        return imdbLookupFlights.await(imdbKey) {
            resolveImdbToTmdb(cleanImdbId, kind, imdbIdentity)
        }
    }

    private suspend fun resolveImdbToTmdb(
        cleanImdbId: String,
        kind: ContentKind,
        imdbIdentity: ContentIdentity
    ): Int? = withContext(Dispatchers.IO) {
        val imdbKey = imdbIdentity.stableKey
        imdbToTmdbCache[imdbKey]?.let { return@withContext it }
        resolveAliasSafely(imdbIdentity, IdentityNamespace.TMDB)?.externalId?.toIntOrNull()
            ?.takeIf { ContentIdentity.tmdb(kind, it) != null }
            ?.let { cachedId ->
                cacheMutex.withLock {
                    imdbToTmdbCache.put(imdbKey, cachedId)
                    ContentIdentity.tmdb(kind, cachedId)?.stableKey?.let { tmdbKey ->
                        tmdbToImdbCache.put(tmdbKey, cleanImdbId)
                    }
                }
                return@withContext cachedId
            }

        try {
            val response = tmdbApi.findByExternalId(
                externalId = cleanImdbId,
                apiKey = apiKey(),
                externalSource = "imdb_id"
            )
            if (!response.isSuccessful) return@withContext null

            val body = response.body() ?: return@withContext null
            val resultWithKind = when (kind) {
                ContentKind.MOVIE -> body.movieResults?.firstOrNull()?.let { it to ContentKind.MOVIE }
                ContentKind.SERIES -> body.tvResults?.firstOrNull()?.let { it to ContentKind.SERIES }
                else -> null
            }

            resultWithKind?.let { (found, resultKind) ->
                val tmdbIdentity = ContentIdentity.tmdb(resultKind, found.id) ?: return@withContext null
                cacheMutex.withLock {
                    imdbToTmdbCache.put(imdbKey, found.id)
                    tmdbToImdbCache.put(tmdbIdentity.stableKey, cleanImdbId)
                }
                rememberAliasSafely(imdbIdentity, tmdbIdentity)
                found.id
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            recordLookupFailure("IMDb Lookup", cleanImdbId, e)
            null
        }
    }

    /**
     * Convert a TMDB ID to an IMDB ID.
     */
    suspend fun tmdbToImdb(tmdbId: Int, mediaType: String): String? {
        val kind = ContentKind.fromProvider(mediaType)?.takeIf { it == ContentKind.MOVIE || it == ContentKind.SERIES }
            ?: return null
        val tmdbIdentity = ContentIdentity.tmdb(kind, tmdbId) ?: return null
        return tmdbLookupFlights.await(tmdbIdentity.stableKey) {
            resolveTmdbToImdb(tmdbId, kind, tmdbIdentity)
        }
    }

    private suspend fun resolveTmdbToImdb(
        tmdbId: Int,
        kind: ContentKind,
        tmdbIdentity: ContentIdentity
    ): String? = withContext(Dispatchers.IO) {
        val tmdbKey = tmdbIdentity.stableKey
        tmdbToImdbCache[tmdbKey]?.let { return@withContext it }
        resolveAliasSafely(tmdbIdentity, IdentityNamespace.IMDB)?.externalId?.let { cachedImdbId ->
            cacheMutex.withLock {
                tmdbToImdbCache.put(tmdbKey, cachedImdbId)
                ContentIdentity.imdb(kind, cachedImdbId)?.stableKey?.let { imdbKey ->
                    imdbToTmdbCache.put(imdbKey, tmdbId)
                }
            }
            return@withContext cachedImdbId
        }

        try {
            val response = when (kind) {
                ContentKind.MOVIE -> tmdbApi.getMovieExternalIds(tmdbId, apiKey())
                else -> tmdbApi.getTvExternalIds(tmdbId, apiKey())
            }
            if (!response.isSuccessful) return@withContext null

            val body = response.body() ?: return@withContext null
            if (body.id != tmdbId) return@withContext null
            val imdbIdentity = ContentIdentity.imdb(kind, body.imdbId ?: return@withContext null)
                ?: return@withContext null
            imdbIdentity.externalId.let { imdbId ->
                cacheMutex.withLock {
                    tmdbToImdbCache.put(tmdbKey, imdbId)
                    imdbToTmdbCache.put(imdbIdentity.stableKey, tmdbId)
                }
                rememberAliasSafely(imdbIdentity, tmdbIdentity)
                imdbId
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            recordLookupFailure("IMDb ID Resolution", tmdbId.toString(), e)
            null
        }
    }

    private fun recordLookupFailure(operation: String, id: String, failure: Exception) {
        if (failure is IOException) {
            // DNS/TLS/socket failures are expected transient request failures, not app crashes.
            com.saab.tv.AppDiagnostics.event(
                "TMDB", "$operation Network Failure",
                "id=$id exception=${failure.javaClass.simpleName}"
            )
            Log.w(TAG, "$operation unavailable for $id: ${failure.message}")
        } else {
            com.saab.tv.AppDiagnostics.failure("TMDB", "$operation Failed", failure)
            Log.e(TAG, "$operation failed for $id", failure)
        }
    }

    private suspend fun resolveAliasSafely(
        identity: ContentIdentity,
        targetNamespace: IdentityNamespace
    ): ContentIdentity? = try {
        canonicalIdentityStore.resolveAlias(identity, targetNamespace)
            ?.takeIf { it.kind == identity.kind && it.namespace == targetNamespace }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        com.saab.tv.AppDiagnostics.event(
            "TMDB", "Identity Cache Read Failed", "exception=${failure.javaClass.simpleName}"
        )
        null
    }

    private suspend fun rememberAliasSafely(left: ContentIdentity, right: ContentIdentity) {
        try {
            canonicalIdentityStore.linkVerifiedIdentities(left, right)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // A local cache write must not turn a successful remote mapping into a playback/UI error.
            com.saab.tv.AppDiagnostics.event(
                "TMDB", "Identity Cache Write Failed", "exception=${failure.javaClass.simpleName}"
            )
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

        val kind = ContentKind.fromProvider(mediaType)?.takeIf { it == ContentKind.MOVIE || it == ContentKind.SERIES }
            ?: return null
        if (idPart.startsWith("tt")) {
            val imdbIdentity = ContentIdentity.imdb(kind, idPart) ?: return null
            return imdbToTmdb(imdbIdentity.externalId, mediaType)?.toString()
        }

        return ContentIdentity.tmdb(kind, idPart)?.externalId.also {
            if (it == null) Log.w(TAG, "Unknown video ID format: $videoId")
        }

    }

    fun normalizeMediaType(mediaType: String): String {
        return when (ContentKind.fromProvider(mediaType)) {
            ContentKind.SERIES -> "tv"
            ContentKind.MOVIE -> "movie"
            else -> mediaType.trim().lowercase()
        }
    }

    fun clearCache() {
        imdbToTmdbCache.evictAll()
        tmdbToImdbCache.evictAll()
    }

    suspend fun preCacheMapping(imdbId: String, tmdbId: Int, mediaType: String) {
        val kind = ContentKind.fromProvider(mediaType)?.takeIf { it == ContentKind.MOVIE || it == ContentKind.SERIES }
            ?: return
        val imdbKey = ContentIdentity.imdb(kind, imdbId)?.stableKey ?: return
        val tmdbKey = ContentIdentity.tmdb(kind, tmdbId)?.stableKey ?: return
        imdbToTmdbCache.put(imdbKey, tmdbId)
        tmdbToImdbCache.put(tmdbKey, imdbId.trim())
        rememberAliasSafely(
            ContentIdentity.imdb(kind, imdbId) ?: return,
            ContentIdentity.tmdb(kind, tmdbId) ?: return
        )
    }
}
