package com.saab.tv.data.ott

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.HomeRow
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class OttCatalogException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Singleton
class OttCatalogRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val CINEMETA_BASE_URL = "https://v3-cinemeta.strem.io"
        private const val CACHE_DURATION_MS = 3 * 60 * 60 * 1000L
        private const val CACHE_PREFERENCES = "ott_justwatch_catalog_cache"
        private const val CACHE_PAYLOAD_KEY = "last_good_payload_v1"
        private const val TOP_10_TARGET = 10
        private const val NEWLY_ADDED_TARGET = 15
        private const val MAX_PARALLEL_GROUPS = 4
        private val IMDB_ID = Regex("^tt[0-9]+$")

        private val providers = listOf(
            OttProvider("Netflix", "nfx"),
            OttProvider("Amazon Prime Video", "prv"),
            OttProvider("JioHotstar", "jhs"),
            OttProvider("SonyLIV", "snl"),
            OttProvider("ZEE5", "zee"),
            OttProvider("Aha", "aha")
        )

        private val excludedResolvedGenres = setOf(
            "adult", "animation", "anime", "game-show", "reality-tv", "talk-show"
        )
    }

    private enum class CatalogKind { TOP_10, NEWLY_ADDED, OVERALL_TRENDING }

    private data class GroupSpec(
        val catalog: CatalogKind,
        val provider: OttProvider?,
        val type: OttMediaKind,
        val order: Int
    ) {
        val targetCount: Int
            get() = if (catalog == CatalogKind.NEWLY_ADDED) NEWLY_ADDED_TARGET else TOP_10_TARGET
    }

    private data class CachedRows(val loadedAt: Long, val rows: List<HomeRow>)

    private data class PersistedPayload(
        val loadedAt: Long = 0L,
        val rows: List<PersistedRow> = emptyList()
    )

    private data class PersistedRow(
        val configId: String = "",
        val title: String = "",
        val order: Int = 0,
        val items: List<PersistedItem> = emptyList()
    )

    private data class PersistedItem(
        val id: String = "",
        val type: String = "",
        val name: String = "",
        val poster: String = "",
        val background: String? = null,
        val logo: String? = null,
        val description: String? = null,
        val releaseInfo: String? = null,
        val imdbRating: String? = null,
        val runtime: String? = null,
        val genres: List<String>? = null
    )

    private val preferences = com.saab.tv.data.account.AccountStorage.preferences(context, CACHE_PREFERENCES)
    private val gson = Gson()
    private val justWatchClient = JustWatchOttClient(okHttpClient)
    private val cacheMutex = Mutex()
    private var cache: CachedRows? = null
    private var diskCacheLoaded = false

    suspend fun getCatalogRows(forceRefresh: Boolean = false): List<HomeRow> = cacheMutex.withLock {
        ensureDiskCacheLoaded()
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            cache?.takeIf { now - it.loadedAt < CACHE_DURATION_MS }?.let { return@withLock it.rows }
        }

        try {
            val rows = buildCatalogRows()
            if (rows.isEmpty()) throw OttCatalogException("JustWatch returned no usable OTT catalogs.")
            cache = CachedRows(now, rows)
            persistLastGood(now, rows)
            rows
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cache?.rows?.takeIf { it.isNotEmpty() }?.let { return@withLock it }
            throw OttCatalogException(
                "The JustWatch OTT catalog is temporarily unavailable. Try Refresh again later.",
                e
            )
        }
    }

    private suspend fun buildCatalogRows(): List<HomeRow> = coroutineScope {
        val semaphore = Semaphore(MAX_PARALLEL_GROUPS)
        val resolver = CinemetaResolver(this, okHttpClient)
        buildGroupSpecs().map { spec ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        buildRow(spec, resolver)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // A single provider/type outage must not discard every healthy catalog.
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull().sortedBy(HomeRow::order)
    }

    private fun buildGroupSpecs(): List<GroupSpec> {
        val specs = mutableListOf<GroupSpec>()
        var order = 0
        providers.forEach { provider ->
            specs += GroupSpec(CatalogKind.TOP_10, provider, OttMediaKind.MOVIE, order++)
            specs += GroupSpec(CatalogKind.TOP_10, provider, OttMediaKind.SERIES, order++)
            specs += GroupSpec(CatalogKind.NEWLY_ADDED, provider, OttMediaKind.MOVIE, order++)
            specs += GroupSpec(CatalogKind.NEWLY_ADDED, provider, OttMediaKind.SERIES, order++)
        }
        specs += GroupSpec(CatalogKind.OVERALL_TRENDING, null, OttMediaKind.MOVIE, order++)
        specs += GroupSpec(CatalogKind.OVERALL_TRENDING, null, OttMediaKind.SERIES, order)
        return specs
    }

    private suspend fun buildRow(spec: GroupSpec, resolver: CinemetaResolver): HomeRow? {
        val candidates = when (spec.catalog) {
            CatalogKind.TOP_10 -> justWatchClient.popular(spec.provider, spec.type, trending = false)
            CatalogKind.NEWLY_ADDED -> justWatchClient.newlyAdded(requireNotNull(spec.provider), spec.type)
            CatalogKind.OVERALL_TRENDING -> justWatchClient.popular(null, spec.type, trending = true)
        }

        // Dedupe only inside this catalog/platform/type group. Cross-catalog repetition is valid.
        val items = mutableListOf<MetaItem>()
        val seen = mutableSetOf<String>()
        for (candidate in candidates) {
            if (!seen.add("${candidate.kind}:${candidate.imdbId}")) continue
            val resolved = resolver.resolve(candidate) ?: continue
            if (!isAllowedResolvedMeta(resolved)) continue
            items += resolved
            if (items.size >= spec.targetCount) break
        }
        if (items.isEmpty()) return null

        return HomeRow(
            configId = catalogId(spec),
            title = catalogTitle(spec),
            items = items,
            isInfiniteLoopEnabled = false,
            visibleItemCount = items.size,
            isInfiniteScrollingEnabled = false,
            order = spec.order,
            supportsSkip = false
        )
    }

    private fun isAllowedResolvedMeta(meta: MetaItem): Boolean {
        if (!IMDB_ID.matches(meta.id) || meta.name.isBlank() || meta.poster.isNullOrBlank()) return false
        val genres = meta.genres.orEmpty().map { it.trim().lowercase(Locale.US) }
        return genres.none(excludedResolvedGenres::contains)
    }

    private fun catalogTitle(spec: GroupSpec): String {
        val type = if (spec.type == OttMediaKind.SERIES) "Series" else "Movies"
        return when (spec.catalog) {
            CatalogKind.TOP_10 -> "${spec.provider?.name} Top 10 - $type"
            CatalogKind.NEWLY_ADDED -> "${spec.provider?.name} New - $type"
            CatalogKind.OVERALL_TRENDING -> "Overall Trending - $type"
        }
    }

    private fun catalogId(spec: GroupSpec): String = listOfNotNull(
        spec.provider?.name,
        spec.catalog.name,
        spec.type.name
    ).joinToString("_") { normalizeIdPart(it) }

    private fun normalizeIdPart(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')

    private fun ensureDiskCacheLoaded() {
        if (diskCacheLoaded) return
        diskCacheLoaded = true
        val payload = preferences.getString(CACHE_PAYLOAD_KEY, null)
            ?.let { json -> runCatching { gson.fromJson(json, PersistedPayload::class.java) }.getOrNull() }
            ?: return
        val rows = payload.rows.mapNotNull { it.toHomeRow() }
        if (payload.loadedAt > 0 && rows.isNotEmpty()) cache = CachedRows(payload.loadedAt, rows)
    }

    private fun persistLastGood(loadedAt: Long, rows: List<HomeRow>) {
        val payload = PersistedPayload(
            loadedAt = loadedAt,
            rows = rows.map { row ->
                PersistedRow(
                    configId = row.configId,
                    title = row.title,
                    order = row.order,
                    items = row.items.map { item ->
                        PersistedItem(
                            id = item.id,
                            type = item.type,
                            name = item.name,
                            poster = item.poster.orEmpty(),
                            background = item.background,
                            logo = item.logo,
                            description = item.description,
                            releaseInfo = item.releaseInfo,
                            imdbRating = item.imdbRating,
                            runtime = item.runtime,
                            genres = item.genres
                        )
                    }
                )
            }
        )
        preferences.edit().putString(CACHE_PAYLOAD_KEY, gson.toJson(payload)).apply()
    }

    private fun PersistedRow.toHomeRow(): HomeRow? {
        if (configId.isBlank() || title.isBlank()) return null
        val resolvedItems = items.mapNotNull { item ->
            if (!IMDB_ID.matches(item.id) || item.name.isBlank() || item.poster.isBlank()) return@mapNotNull null
            MetaItem(
                id = item.id,
                type = item.type,
                name = item.name,
                poster = item.poster,
                background = item.background,
                logo = item.logo,
                description = item.description,
                releaseInfo = item.releaseInfo,
                imdbRating = item.imdbRating,
                runtime = item.runtime,
                genres = item.genres,
                addonBaseUrl = CINEMETA_BASE_URL
            )
        }
        if (resolvedItems.isEmpty()) return null
        return HomeRow(
            configId = configId,
            title = title,
            items = resolvedItems,
            isInfiniteLoopEnabled = false,
            visibleItemCount = resolvedItems.size,
            isInfiniteScrollingEnabled = false,
            order = order,
            supportsSkip = false
        )
    }

    private class CinemetaResolver(
        private val scope: CoroutineScope,
        private val okHttpClient: OkHttpClient
    ) {
        private data class Resolution(val meta: MetaItem?)

        private val mutex = Mutex()
        private val completed = mutableMapOf<String, Resolution>()
        private val inFlight = mutableMapOf<String, Deferred<MetaItem?>>()
        private val gson = Gson()

        suspend fun resolve(candidate: JustWatchCandidate): MetaItem? {
            val key = "${candidate.kind.cinemetaType}:${candidate.imdbId}"
            val deferred = mutex.withLock {
                completed[key]?.let { return it.meta }
                inFlight[key] ?: scope.async(Dispatchers.IO) { fetch(candidate) }
                    .also { inFlight[key] = it }
            }
            val result = try {
                deferred.await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            mutex.withLock {
                inFlight.remove(key)
                completed[key] = Resolution(result)
            }
            return result
        }

        private suspend fun fetch(candidate: JustWatchCandidate): MetaItem? {
            val url = "$CINEMETA_BASE_URL/meta/${candidate.kind.cinemetaType}/${candidate.imdbId}.json"
            repeat(3) { attempt ->
                try {
                    val body = withContext(Dispatchers.IO) {
                        val request = Request.Builder().url(url).header("Accept", "application/json").build()
                        okHttpClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) throw IOException("Cinemeta returned HTTP ${response.code}")
                            response.body?.string()?.takeIf(String::isNotBlank)
                                ?: throw IOException("Cinemeta returned an empty response")
                        }
                    }
                    val metaJson = JsonParser.parseString(body).asJsonObject.getAsJsonObject("meta") ?: return null
                    val meta = gson.fromJson(metaJson, MetaItem::class.java)
                    if (meta.id != candidate.imdbId || meta.type != candidate.kind.cinemetaType) return null
                    if (meta.name.isBlank() || meta.poster.isNullOrBlank()) return null
                    return meta.copy(addonBaseUrl = CINEMETA_BASE_URL)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (attempt < 2) delay(400L * (attempt + 1))
                }
            }
            return null
        }
    }
}
