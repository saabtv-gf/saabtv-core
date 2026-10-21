package com.saab.tv.data.repository

import com.google.gson.Gson
import com.saab.tv.data.cache.BoundedExpiringCache
import com.saab.tv.data.local.AddonDao
import com.saab.tv.data.model.AddonEntity
import com.saab.tv.data.model.CatalogConfigEntity
import com.saab.tv.data.model.stremio.CatalogManifest
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.schema.ArtworkRole
import com.saab.tv.domain.schema.ContentIdentity
import com.saab.tv.domain.schema.CanonicalEntityKey
import com.saab.tv.domain.schema.addonInstallationKey
import com.saab.tv.domain.schema.toCanonicalProjection
import com.saab.tv.domain.schema.toTitleCardProjection
import com.saab.tv.data.model.stremio.Stream
import com.saab.tv.data.remote.StremioApiService
import com.saab.tv.domain.HomeRow
import com.saab.tv.domain.HubGroupRow
import com.saab.tv.domain.HubItem
import com.saab.tv.domain.HubShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.firstOrNull
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

data class CatalogSearchOutcome(val items: List<MetaItem>, val failedTypes: Set<String>)

@Singleton
class AddonRepository @Inject constructor(
    private val api: StremioApiService,
    private val dao: AddonDao,
    private val torBox: com.saab.tv.data.stream.TorBoxAvailabilityService,
    private val canonicalTitleStore: CanonicalTitleStore = NoopCanonicalTitleStore,
    canonicalSeriesMetadataStore: CanonicalSeriesMetadataStore = NoopCanonicalSeriesMetadataStore,
    private val canonicalIdentityStore: CanonicalIdentityStore = NoopCanonicalIdentityStore
) {
    private data class CanonicalFactEntry(val value: com.saab.tv.data.model.CanonicalTitleEntity?)

    private val canonicalMovieMetadataCache = CanonicalMovieMetadataCache(canonicalTitleStore)
    private val canonicalSeriesMetadataCache = CanonicalCinemetaSeriesCache(canonicalTitleStore, canonicalSeriesMetadataStore)
    private val canonicalFacts = BoundedExpiringCache<String, CanonicalFactEntry>(512, 60_000L, System::currentTimeMillis)
    private val dashboardCatalogPermits = Semaphore(4)
    private val gson = Gson()
    private val previewStreams = com.saab.tv.data.cache.PreviewWarmupCache<List<Stream>>(
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO),
        android.os.SystemClock::elapsedRealtime, ttlMs = 300_000
    )
    private val previewMetadata = com.saab.tv.data.cache.PreviewWarmupCache<MetaItem?>(
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO),
        android.os.SystemClock::elapsedRealtime, ttlMs = 300_000
    )

    private suspend fun previewMetadataKey(type: String, id: String, addon: String?): String =
        "${previewStreamKey(type, id)}:${if (type == "series") "cinemeta" else addon.orEmpty()}"

    suspend fun prefetchTrailerMetadata(type: String, id: String, addon: String?): MetaItem? =
        withContext(Dispatchers.IO) {
            val key = previewMetadataKey(type, id, addon)
            previewMetadata.prefetch(key) {
                val meta = fetchMetaDetails(type, id, addon)
                if (meta != null) cacheResolvedMetadata(meta, id, type)
                meta.takeIf { previewMetadataKey(type, id, addon) == key }
            }
            previewMetadata.awaitIfPresent(key)
        }

    private suspend fun previewStreamKey(type: String, id: String): String {
        val addons = dao.getAllAddons().firstOrNull().orEmpty()
            .filter { it.isEnabled && it.supportsStream }
            .map { it.transportUrl }.sorted()
        return "${dao.getActiveProfileId()}:$type:$id:${addons.hashCode()}"
    }

    suspend fun prefetchTrailerStreams(type: String, id: String) = withContext(Dispatchers.IO) {
        val key = previewStreamKey(type, id)
        previewStreams.prefetch(key) {
            if (previewStreamKey(type, id) != key) emptyList() else {
                val streams = fetchStreams(type, id)
                streams.takeIf { previewStreamKey(type, id) == key }.orEmpty()
            }
        }
        val ready = previewStreams.awaitIfPresent(key)
        com.saab.tv.AppDiagnostics.event("Source Warmup", "Ready", "type=$type id=$id sources=${ready?.size ?: 0}")
    }
    private val MAX_CATALOG_PAGES = 30

    /** Filter out MetaItems where Gson injected null into non-null Kotlin fields */
    private fun List<MetaItem>.sanitize(): List<MetaItem> = filter { item ->
        @Suppress("SENSELESS_COMPARISON")
        item.id != null && item.name != null && item.type != null
    }
    /** Validate a single MetaItem from getMeta() — returns null if Gson injected nulls */
    @Suppress("SENSELESS_COMPARISON")
    private fun MetaItem?.sanitize(): MetaItem? =
        this?.takeIf { it.id != null && it.name != null && it.type != null }
    private val CATALOG_TIMEOUT_MS = 10_000L // 10 seconds per catalog request
    private val STREAM_TIMEOUT_MS = 20_000L  // 20 seconds per stream request (torrent addons need more time)

    /** Canonicalized card projection shared by catalog, search and library compatibility edges. */
    suspend fun canonicalizeCatalogItems(items: List<MetaItem>): List<MetaItem> = withContext(Dispatchers.IO) {
        val locale = dao.getActiveProfileId()?.let { dao.getProfileById(it)?.tmdbLanguage }.orEmpty()
        val seen = HashSet<String>(items.size)
        val canonicalized = ArrayList<MetaItem>(items.size)
        for (item in items) {
            val projection = item.toCanonicalProjection()
            val canonicalKey = projection?.let { canonicalKey(it.identity) }
                ?: "legacy/${item.type.trim().lowercase()}/${item.id.trim()}"
            if (!seen.add(canonicalKey)) continue
            canonicalized += if (projection == null) item
            else applyCanonicalFacts(item, projection.identity, projection.artwork, locale)
        }
        canonicalized
    }

    private suspend fun canonicalKey(identity: ContentIdentity): String = try {
        canonicalIdentityStore.canonicalKey(identity)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        identity.stableKey
    }

    /** Resolves a UI/provider ID to the verified canonical key without a network request. */
    suspend fun canonicalIdentityKey(item: MetaItem): String? {
        val identity = item.toCanonicalProjection()?.identity ?: return null
        return canonicalKey(identity)
    }

    suspend fun canonicalIdentityKey(id: String, type: String, addonBaseUrl: String? = null): String? {
        val installationId = addonBaseUrl?.let(::addonInstallationKey)
        val identity = CanonicalEntityKey.titleIdentity(id, type, installationId) ?: return null
        return canonicalKey(identity)
    }

    suspend fun canonicalPlayableIdentityKey(id: String, type: String, addonBaseUrl: String? = null): String? {
        val installationId = addonBaseUrl?.let(::addonInstallationKey)
        return try {
            canonicalIdentityStore.canonicalPlayableKey(id, type, installationId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CanonicalEntityKey.playable(id, type, installationId)
        }
    }

    private suspend fun applyCanonicalFacts(
        item: MetaItem,
        identity: ContentIdentity,
        fallbackArtwork: List<com.saab.tv.domain.schema.ArtworkReference>,
        locale: String
    ): MetaItem {
        val key = "${identity.stableKey}\u0000$locale"
        val prior = canonicalFacts[key]
        if (prior != null) return prior.value?.let { projectCanonicalItem(item, identity, fallbackArtwork, it) } ?: item
        val title = try {
            canonicalTitleStore.getFreshTitle(identity, locale, CANONICAL_FACT_MAX_AGE_MS)
                ?: locale.takeIf(String::isNotBlank)?.let {
                    canonicalTitleStore.getFreshTitle(identity, "", CANONICAL_FACT_MAX_AGE_MS)
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return item
        }
        canonicalFacts[key] = CanonicalFactEntry(title)
        return if (title == null) item else projectCanonicalItem(item, identity, fallbackArtwork, title)
    }

    private fun projectCanonicalItem(
        item: MetaItem,
        identity: ContentIdentity,
        fallbackArtwork: List<com.saab.tv.domain.schema.ArtworkReference>,
        title: com.saab.tv.data.model.CanonicalTitleEntity
    ): MetaItem {
        val projection = title.toTitleCardProjection(identity, item.name, fallbackArtwork)
        return item.copy(
            name = projection.title,
            poster = projection.artwork(ArtworkRole.POSTER)?.locator ?: item.poster,
            background = projection.artwork(ArtworkRole.BACKDROP)?.locator ?: item.background,
            logo = projection.artwork(ArtworkRole.LOGO)?.locator ?: item.logo,
            description = projection.synopsis ?: item.description,
            releaseInfo = projection.year ?: item.releaseInfo,
            imdbRating = projection.rating?.takeIf { it.source == "imdb" && it.scale == "0-10" }
                ?.value?.toString() ?: item.imdbRating,
            runtime = projection.runtimeEstimateMillis?.let { (it / 60_000L).coerceAtLeast(1).toString() } ?: item.runtime,
            genres = projection.genres.takeIf { it.isNotEmpty() } ?: item.genres
        )
    }

    /**
     * Fetches a single page of catalog items at the given skip offset.
     * Used for on-demand lazy loading as the user scrolls.
     */
    data class CatalogPageResult(val items: List<MetaItem>, val failed: Boolean)
    data class CatalogPreviewResult(val row: HomeRow?, val failed: Boolean)

    suspend fun fetchNextCatalogPage(baseUrl: String, skip: Int): List<MetaItem> =
        fetchNextCatalogPageWithStatus(baseUrl, skip).items

    suspend fun fetchNextCatalogPageWithStatus(baseUrl: String, skip: Int): CatalogPageResult = withContext(Dispatchers.IO) {
        try {
            val url = if (skip == 0) baseUrl else {
                baseUrl.replace(".json", "/skip=$skip.json")
            }
            val addonOrigin = baseUrl.substringBefore("/catalog")
            CatalogPageResult(canonicalizeCatalogItems(
                withTimeout(CATALOG_TIMEOUT_MS) { api.getCatalog(url) }.metas.orEmpty().sanitize()
                    .map { it.copy(addonBaseUrl = addonOrigin) }
            ), failed = false)
        } catch (_: TimeoutCancellationException) {
            CatalogPageResult(emptyList(), failed = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            CatalogPageResult(emptyList(), failed = true)
        }
    }


    suspend fun searchMovies(query: String): List<MetaItem> = searchMoviesWithStatus(query).items

    suspend fun searchMoviesWithStatus(query: String): CatalogSearchOutcome = withContext(Dispatchers.IO) {
        // Automatic typing still waits for three characters in SearchViewModel,
        // but an explicit Search action may resolve a useful two-letter title.
        if (query.trim().length < 2) return@withContext CatalogSearchOutcome(emptyList(), emptySet())
        val encodedQuery = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
            .replace("+", "%20")

        suspend fun fetch(type: String): Pair<List<MetaItem>, Boolean> = try {
            val results = withTimeout(CATALOG_TIMEOUT_MS) {
                api.getCatalog("https://v3-cinemeta.strem.io/catalog/$type/top/search=$encodedQuery.json")
            }.metas.orEmpty().sanitize()
            results to false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList<MetaItem>() to true
        }

        val movieJob = async { fetch("movie") }
        val seriesJob = async { fetch("series") }
        val movie = movieJob.await()
        val series = seriesJob.await()
        val cinemeta = "https://v3-cinemeta.strem.io"
        CatalogSearchOutcome(
            canonicalizeCatalogItems((movie.first + series.first).map { it.copy(addonBaseUrl = cinemeta) }),
            buildSet {
                if (movie.second) add("movie")
                if (series.second) add("series")
            }
        )
    }

    /** Search a single canonical Cinemeta catalog while preserving its metadata origin. */
    suspend fun searchCinemeta(query: String, type: String): List<MetaItem> = withContext(Dispatchers.IO) {
        val normalizedType = if (type.equals("series", ignoreCase = true)) "series" else "movie"
        val cleanQuery = query.trim()
        if (cleanQuery.length < 2) return@withContext emptyList()

        val encodedQuery = URLEncoder.encode(cleanQuery, Charsets.UTF_8.name())
            .replace("+", "%20")
        val cinemeta = "https://v3-cinemeta.strem.io"
        val url = "$cinemeta/catalog/$normalizedType/top/search=$encodedQuery.json"

        try {
            withTimeout(CATALOG_TIMEOUT_MS) { api.getCatalog(url) }
                .metas
                .orEmpty()
                .sanitize()
                .filter { it.type.equals(normalizedType, ignoreCase = true) }
                .map { it.copy(addonBaseUrl = cinemeta) }
                .let { canonicalizeCatalogItems(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Checks whether a specific catalog in an addon's manifest declares "skip" extra support.
     */
    private fun catalogSupportsSkip(addon: AddonEntity, catalogType: String, catalogId: String): Boolean {
        val catalogs: List<CatalogManifest> = try {
            gson.fromJson(addon.catalogsJson, Array<CatalogManifest>::class.java)?.toList()?.filterNotNull() ?: return false
        } catch (_: Exception) { return false }
        val catalog = catalogs.find { it.type == catalogType && it.id == catalogId } ?: return false
        return catalog.extra?.any { it.name == "skip" } ?: false
    }

    data class DiscoverCatalog(
        val transportUrl: String,
        val addonName: String,
        val type: String,
        val catalogId: String,
        val catalogName: String = "",
        val genres: List<String> = emptyList(),
        val supportsSkip: Boolean = false
    )

    suspend fun getDiscoverCatalogs(): List<DiscoverCatalog> = withContext(Dispatchers.IO) {
        val addons = dao.getAllAddons().firstOrNull()?.filter { it.isEnabled } ?: emptyList()
        val result = mutableListOf<DiscoverCatalog>()

        for (addon in addons) {
            val catalogs: List<CatalogManifest> = try {
                gson.fromJson(addon.catalogsJson, Array<CatalogManifest>::class.java)?.toList()?.filterNotNull() ?: continue
            } catch (e: Exception) { continue }

            for (catalog in catalogs) {
                // Skip catalogs with null fields injected by Gson
                @Suppress("SENSELESS_COMPARISON")
                if (catalog.name == null || catalog.id == null || catalog.type == null) continue

                // Exclude search-only catalogs (required search extra)
                val hasRequiredSearch = catalog.extra?.any { it.name == "search" && it.isRequired } ?: false
                if (hasRequiredSearch) continue

                val genres = catalog.extra
                    ?.firstOrNull { it.name == "genre" }
                    ?.options ?: emptyList()

                val supportsSkip = catalog.extra?.any { it.name == "skip" } ?: false

                result.add(DiscoverCatalog(
                    transportUrl = addon.transportUrl,
                    addonName = addon.nickname ?: addon.name,
                    type = catalog.type,
                    catalogId = catalog.id,
                    catalogName = catalog.name,
                    genres = genres,
                    supportsSkip = supportsSkip
                ))
            }
        }
        result
    }

    suspend fun fetchDiscoverPage(
        transportUrl: String,
        type: String,
        catalogId: String,
        genre: String? = null,
        skip: Int = 0
    ): List<MetaItem> = withContext(Dispatchers.IO) {
        // Build extras as a single path segment joined by '&', matching Stremio protocol
        val extras = mutableListOf<String>()
        if (!genre.isNullOrEmpty()) extras.add("genre=${java.net.URLEncoder.encode(genre, "UTF-8")}")
        if (skip > 0) extras.add("skip=$skip")

        val url = if (extras.isEmpty()) {
            "$transportUrl/catalog/$type/$catalogId.json"
        } else {
            "$transportUrl/catalog/$type/$catalogId/${extras.joinToString("&")}.json"
        }

        try {
            canonicalizeCatalogItems(
                withTimeout(CATALOG_TIMEOUT_MS) { api.getCatalog(url) }.metas.orEmpty().sanitize()
                    .map { it.copy(addonBaseUrl = transportUrl) }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getDashboardRows(
        screen: String,
        skipConfigs: Int = 0,
        maxConfigs: Int = Int.MAX_VALUE,
        catalogTimeoutMs: Long = CATALOG_TIMEOUT_MS
    ): List<HomeRow> = withContext(Dispatchers.IO) {
        val addons = dao.getAllAddons().firstOrNull()?.filter { it.isEnabled } ?: emptyList()
        val configs = dao.getAllCatalogConfigs().firstOrNull() ?: emptyList()
        val addonMap = addons.associateBy { it.transportUrl }

        val filteredConfigs = configs
            .filter { config ->
                when(screen) {
                    "home" -> config.showInHome
                    "movies" -> config.showInMovies
                    "series" -> config.showInSeries
                    else -> false
                }
            }
            .sortedBy { config ->
                when(screen) {
                    "home" -> config.homeOrder
                    "movies" -> config.moviesOrder
                    "series" -> config.seriesOrder
                    else -> 0
                }
            }
            .drop(skipConfigs.coerceAtLeast(0))
            .let { sliced ->
                if (maxConfigs == Int.MAX_VALUE) sliced else sliced.take(maxConfigs.coerceAtLeast(0))
            }

        val deferredJobs = filteredConfigs.mapNotNull { config ->
            val addon = addonMap[config.transportUrl] ?: return@mapNotNull null
            async {
                try {
                    val url = "${config.transportUrl}/catalog/${config.catalogType}/${config.catalogId}.json"
                    // Fetch only the first page for fast initial load
                    val rawMetas = try {
                        val response = if (maxConfigs == Int.MAX_VALUE) {
                            // Background catalog fan-out is bounded; the small first-paint
                            // batch above the fold never waits behind those requests.
                            dashboardCatalogPermits.withPermit {
                                withTimeout(catalogTimeoutMs) { api.getCatalog(url) }
                            }
                        } else {
                            withTimeout(catalogTimeoutMs) { api.getCatalog(url) }
                        }
                        response.metas.orEmpty().sanitize()
                    } catch (_: TimeoutCancellationException) {
                        emptyList()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        emptyList()
                    }
                    if (rawMetas.isNotEmpty()) {
                        val metas = canonicalizeCatalogItems(rawMetas.map { it.copy(addonBaseUrl = config.transportUrl) })
                        val typeSuffix = config.catalogType.replaceFirstChar { it.uppercase() }
                        val defaultTitle = if (config.catalogName != null) "${config.catalogName} - ${typeSuffix}" else "${config.addonName} - ${config.catalogId.replaceFirstChar { it.uppercase() }}"
                        val finalTitle = config.customTitle ?: defaultTitle
                        HomeRow(
                            configId = config.uniqueId,
                            title = finalTitle,
                            items = metas,
                            catalogUrl = url,
                            isInfiniteLoopEnabled = config.isInfiniteLoopEnabled,
                            visibleItemCount = config.visibleItemCount,
                            isInfiniteScrollingEnabled = config.isInfiniteScrollingEnabled,
                            order = when(screen) {
                                "home" -> config.homeOrder
                                "movies" -> config.moviesOrder
                                "series" -> config.seriesOrder
                                else -> 999
                            },
                            supportsSkip = catalogSupportsSkip(addon, config.catalogType, config.catalogId)
                        )
                    } else null
                } catch (e: Exception) { null }
            }
        }
        deferredJobs.awaitAll().filterNotNull()
    }

    /**
     * Fast single-page category fetch for lightweight surfaces like Hero Carousel.
     * Avoids paginating through the full catalog when only a handful of items are needed.
     */
    suspend fun getCategoryRowPreview(
        configId: String,
        maxItems: Int,
        timeoutMs: Long = CATALOG_TIMEOUT_MS
    ): HomeRow? = getCategoryRowPreviewWithStatus(configId, maxItems, timeoutMs).row

    suspend fun getCategoryRowPreviewWithStatus(
        configId: String,
        maxItems: Int,
        timeoutMs: Long = CATALOG_TIMEOUT_MS
    ): CatalogPreviewResult = withContext(Dispatchers.IO) {
        val config = dao.getCatalogConfig(configId)
            ?: return@withContext CatalogPreviewResult(row = null, failed = true)
        val addon = dao.getAddon(config.transportUrl)
            ?: return@withContext CatalogPreviewResult(row = null, failed = true)
        if (!addon.isEnabled) return@withContext CatalogPreviewResult(row = null, failed = true)

        try {
            val url = "${config.transportUrl}/catalog/${config.catalogType}/${config.catalogId}.json"
            val metas = withTimeout(timeoutMs) { api.getCatalog(url) }.metas.orEmpty().sanitize()
                .map { it.copy(addonBaseUrl = config.transportUrl) }
                .let { canonicalizeCatalogItems(it) }
                .take(maxItems.coerceAtLeast(1))

            val typeSuffix = config.catalogType.replaceFirstChar { it.uppercase() }
            val defaultTitle = if (config.catalogName != null) "${config.catalogName} - ${typeSuffix}" else "${config.addonName} - ${config.catalogId.replaceFirstChar { it.uppercase() }}"
            val finalTitle = config.customTitle ?: defaultTitle
            CatalogPreviewResult(
                row = HomeRow(
                    configId = config.uniqueId,
                    title = finalTitle,
                    items = metas,
                    catalogUrl = url,
                    isInfiniteLoopEnabled = config.isInfiniteLoopEnabled,
                    visibleItemCount = config.visibleItemCount,
                    isInfiniteScrollingEnabled = config.isInfiniteScrollingEnabled,
                    order = 999,
                    supportsSkip = catalogSupportsSkip(addon, config.catalogType, config.catalogId)
                ),
                failed = false
            )
        } catch (_: TimeoutCancellationException) {
            CatalogPreviewResult(row = null, failed = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CatalogPreviewResult(row = null, failed = true)
        }
    }

    suspend fun getHubRows(screen: String = "home"): List<HubGroupRow> = withContext(Dispatchers.IO) {
        val hubRows = dao.getAllHubRows().firstOrNull() ?: emptyList()
        val allItems = dao.getAllHubRowItems().firstOrNull() ?: emptyList()
        val itemsByRow = allItems.groupBy { it.hubRowId }

        hubRows
            .filter { row ->
                when(screen) {
                    "home" -> row.showInHome
                    "movies" -> row.showInMovies
                    "series" -> row.showInSeries
                    else -> false
                }
            }
            .sortedBy { row ->
                when(screen) {
                    "home" -> row.homeOrder
                    "movies" -> row.moviesOrder
                    "series" -> row.seriesOrder
                    else -> 0
                }
            }
            .map { row ->
                val items = (itemsByRow[row.id] ?: emptyList())
                    .sortedBy { it.itemOrder }
                    .map { item ->
                        HubItem(
                            id = "${row.id}:${item.configUniqueId}",
                            title = item.title,
                            categoryId = item.configUniqueId,
                            customImageUrl = item.customImageUrl
                        )
                    }
                HubGroupRow(
                    id = row.id,
                    title = row.title,
                    items = items,
                    shape = try { HubShape.valueOf(row.shape) } catch (_: Exception) { HubShape.HORIZONTAL },
                    order = when(screen) {
                        "home" -> row.homeOrder
                        "movies" -> row.moviesOrder
                        "series" -> row.seriesOrder
                        else -> 0
                    }
                )
            }
    }

    suspend fun getStreams(type: String, id: String): List<Stream> = withContext(Dispatchers.IO) {
        val warmed = previewStreams.awaitIfPresent(previewStreamKey(type, id))?.takeIf { it.isNotEmpty() }
        com.saab.tv.AppDiagnostics.event("Source Warmup", "Consumed", "type=$type id=$id cacheHit=${warmed != null} sources=${warmed?.size ?: 0}")
        warmed ?: fetchStreams(type, id)
    }

    /**
     * Foreground source picker path. Each completed addon batch is TorBox-enriched and delivered
     * immediately so the caller can apply the same source policy used for the final result.
     */
    suspend fun getStreamsProgressively(
        type: String,
        id: String,
        onPartial: (List<Stream>) -> Unit
    ): List<Stream> = withContext(Dispatchers.IO) {
        val key = previewStreamKey(type, id)
        val warmed = previewStreams.awaitIfComplete(key)?.takeIf { it.isNotEmpty() }
        if (warmed != null) {
            onPartial(warmed)
            return@withContext warmed
        }
        // An unfinished trailer warmup must not recreate the all-addons barrier on the
        // foreground picker path. Cancel it and let this request publish per-addon progress.
        previewStreams.invalidate(key)
        fetchStreams(type, id, onPartial)
    }

    private suspend fun fetchStreams(
        type: String,
        id: String,
        onPartial: ((List<Stream>) -> Unit)? = null
    ): List<Stream> = withContext(Dispatchers.IO) {
        val addons = dao.getAllAddons().firstOrNull()
            ?.filter { it.isEnabled && it.supportsStream }
            ?: emptyList()

        suspend fun fetchAddon(addon: AddonEntity): List<Stream> = try {
            val url = "${addon.transportUrl}/stream/$type/$id.json"
            val response = withTimeout(STREAM_TIMEOUT_MS) { api.getStreams(url) }
            val sourceLabel = addon.nickname ?: addon.name
            response.streams.orEmpty().map { stream ->
                stream.copy(
                    name = "[$sourceLabel] ${stream.name ?: ""}".trim(),
                    addonTransportUrl = addon.transportUrl
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

        if (onPartial == null) {
            return@withContext torBox.enrich(
                addons.map { addon -> async { fetchAddon(addon) } }.awaitAll().flatten()
            )
        }

        val completed = Channel<List<Stream>>(capacity = addons.size.coerceAtLeast(1))
        val jobs = addons.map { addon -> launch { completed.send(fetchAddon(addon)) } }
        val accumulated = mutableListOf<Stream>()
        val checkedHashes = mutableSetOf<String>()
        val checkedStreams = mutableSetOf<String>()
        val availabilityByHash = mutableMapOf<String, Stream>()
        val availabilityByStream = mutableMapOf<String, Stream>()

        fun streamKey(stream: Stream): String = listOf(
            stream.url.orEmpty(),
            stream.addonTransportUrl.orEmpty(),
            stream.fileIdx?.toString().orEmpty(),
            stream.behaviorHints?.filename.orEmpty()
        ).joinToString("\u0000")

        fun applyAvailability(stream: Stream, availability: Stream): Stream = stream.copy(
            torBoxChecked = availability.torBoxChecked,
            torBoxCached = availability.torBoxCached,
            torBoxSeeders = availability.torBoxSeeders
        )

        fun currentResults() = accumulated.map { stream ->
            val evidence = com.saab.tv.data.stream.TorBoxAvailabilityPolicy.hash(stream)
                ?.let(availabilityByHash::get)
                ?: availabilityByStream[streamKey(stream)]
            if (evidence == null) stream else applyAvailability(stream, evidence)
        }

        try {
            repeat(addons.size) {
                val batch = completed.receive()
                if (batch.isNotEmpty()) {
                    accumulated += batch

                    // Verify just the new batch before publishing it. This avoids waiting for
                    // a slow unrelated addon while still preserving TorBox-first status when
                    // configured. Hashless/provider streams share one lookup per exact URL.
                    val needsCheck = batch.filter { stream ->
                        val hash = com.saab.tv.data.stream.TorBoxAvailabilityPolicy.hash(stream)
                        if (hash != null) hash !in checkedHashes
                        else streamKey(stream) !in checkedStreams
                    }
                    torBox.enrich(needsCheck).forEach { evidence ->
                        val hash = com.saab.tv.data.stream.TorBoxAvailabilityPolicy.hash(evidence)
                        if (hash == null) availabilityByStream[streamKey(evidence)] = evidence
                        else availabilityByHash[hash] = evidence
                    }
                    batch.forEach { stream ->
                        val hash = com.saab.tv.data.stream.TorBoxAvailabilityPolicy.hash(stream)
                        if (hash != null) checkedHashes += hash else checkedStreams += streamKey(stream)
                    }
                    onPartial(currentResults())
                }
            }
        } finally {
            completed.cancel()
            jobs.forEach { it.cancel() }
        }
        currentResults()
    }

    suspend fun getAddonSortOrders(): Map<String, Int> = withContext(Dispatchers.IO) {
        dao.getAllAddons().firstOrNull()
            ?.associate { it.transportUrl to it.sortOrder }
            ?: emptyMap()
    }

    suspend fun installAddonWithConfig(url: String, home: Boolean, movies: Boolean, series: Boolean) = withContext(Dispatchers.IO) {
        val manifest = api.getManifest(url)
        val transportUrl = url.removeSuffix("/manifest.json")
        val catalogsJson = gson.toJson(manifest.catalogs.orEmpty())
        val supportsMeta = manifest.resources?.any { element ->
            when {
                element.isJsonPrimitive -> element.asString == "meta"
                element.isJsonObject -> element.asJsonObject.get("name")?.asString == "meta"
                else -> false
            }
        } ?: false
        val supportsStream = manifest.resources?.any { element ->
            when {
                element.isJsonPrimitive -> element.asString == "stream"
                element.isJsonObject -> element.asJsonObject.get("name")?.asString == "stream"
                else -> false
            }
        } ?: false

        val entity = AddonEntity(
            transportUrl = transportUrl, id = manifest.id, name = manifest.name, version = manifest.version,
            description = manifest.description, iconUrl = manifest.logo, isTrusted = false, isEnabled = true,
            nickname = null, catalogsJson = catalogsJson,
            supportsMeta = supportsMeta,
            supportsStream = supportsStream,
            typesJson = gson.toJson(manifest.types.orEmpty()),
            idPrefixesJson = gson.toJson(manifest.idPrefixes.orEmpty())
        )
        dao.insertAddon(entity)

        val newConfigs = manifest.catalogs.orEmpty().map { catalog ->
            val uniqueId = "${transportUrl}/${catalog.type}/${catalog.id}"
            val isMovieCat = catalog.type == "movie"
            val isSeriesCat = catalog.type == "series"
            CatalogConfigEntity(
                uniqueId = uniqueId, transportUrl = transportUrl, addonName = manifest.name,
                catalogType = catalog.type, catalogId = catalog.id,
                catalogName = catalog.name, customTitle = null,
                showInHome = home, showInMovies = movies && isMovieCat, showInSeries = series && isSeriesCat,
                homeOrder = 999, moviesOrder = 999, seriesOrder = 999
            )
        }
        dao.saveCatalogConfigs(newConfigs)
    }

    suspend fun installAddon(url: String) = installAddonWithConfig(url, true, true, true)

    suspend fun renameAddon(transportUrl: String, newName: String) = withContext(Dispatchers.IO) {
        val addons = dao.getAllAddons().firstOrNull()
        val target = addons?.find { it.transportUrl == transportUrl }
        if (target != null) dao.insertAddon(target.copy(nickname = newName))
    }

    suspend fun deleteAddon(transportUrl: String) = withContext(Dispatchers.IO) {
        dao.deleteCatalogConfigs(transportUrl)
        dao.deleteAddonByUrl(transportUrl)
    }

    suspend fun fetchManifest(url: String) = withContext(Dispatchers.IO) { api.getManifest(url) }

    fun getAddons() = dao.getAllAddons()
    suspend fun updateAddons(addons: List<AddonEntity>) = withContext(Dispatchers.IO) { dao.insertAddons(addons) }

    suspend fun getMetaDetails(url: String) = withContext(Dispatchers.IO) { api.getMeta(url).meta.sanitize() }

    /** Resolves canonical Cinemeta details first for IMDb titles, then uses addon fallbacks. */
    suspend fun resolveMetaDetails(
        type: String,
        id: String,
        preferredAddonBaseUrl: String? = null
    ): MetaItem? = withContext(Dispatchers.IO) {
        canonicalMovieMetadataCache.read(type, id, preferredAddonBaseUrl)?.let { return@withContext it }
        canonicalSeriesMetadataCache.read(type, id)?.let { return@withContext it }

        val result = previewMetadata.awaitIfPresent(previewMetadataKey(type, id, preferredAddonBaseUrl))
            ?: fetchMetaDetails(type, id, preferredAddonBaseUrl)
        if (result != null) cacheResolvedMetadata(result, id, type)
        result
    }

    private suspend fun cacheResolvedMetadata(meta: MetaItem, id: String, type: String) {
        val now = System.currentTimeMillis()
        canonicalMovieMetadataCache.write(meta, id, type, now)
        canonicalSeriesMetadataCache.write(meta, id, type, now)
        canonicalFacts.clear()
    }

    private suspend fun fetchMetaDetails(
        type: String,
        id: String,
        preferredAddonBaseUrl: String? = null
    ): MetaItem? = withContext(Dispatchers.IO) {
        // Cinemeta is the sole source of series details and episode numbering.
        // A catalog's preferred addon (or another installed meta addon) can use
        // different episode numbering, such as E101 for S1E1. Never let that
        // response replace Cinemeta's canonical episode list.
        if (type.equals("series", ignoreCase = true)) {
            return@withContext try {
                val url = "https://v3-cinemeta.strem.io/meta/series/$id.json"
                withTimeout(CATALOG_TIMEOUT_MS) { api.getMeta(url) }.meta.sanitize()
                    ?.takeIf { it.id == id && it.type.equals("series", ignoreCase = true) }
                    ?.copy(addonBaseUrl = "https://v3-cinemeta.strem.io")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

        // Cinemeta is the canonical display-metadata source for standard IMDb movies.
        // Use it before a catalog's preferred addon so a catalog-specific title or artwork
        // cannot displace the same title's canonical metadata. Addons remain a fallback.
        if (type.equals("movie", ignoreCase = true) && id.matches(Regex("tt[0-9]+"))) {
            try {
                val url = "$CINEMETA_BASE_URL/meta/movie/$id.json"
                val meta = withTimeout(CATALOG_TIMEOUT_MS) { api.getMeta(url) }.meta.sanitize()
                if (meta != null && meta.id == id && meta.type.equals("movie", ignoreCase = true)) {
                    return@withContext meta.copy(addonBaseUrl = CINEMETA_BASE_URL)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Continue to the preferred addon and enabled metadata addons.
            }
        }

        val allAddons = dao.getAllAddons().firstOrNull()?.filter { it.isEnabled } ?: emptyList()
        val preferredTimeout = 5_000L

        // 1) Try preferred addon first (the addon the catalog item came from).
        // Don't check supportsMeta flag — it may be stale from older DB migrations.
        // If the addon can't serve meta, the request fails fast and we fall through.
        if (!preferredAddonBaseUrl.isNullOrBlank()) {
            try {
                val url = "${preferredAddonBaseUrl.trimEnd('/')}/meta/$type/$id.json"
                val meta = withTimeout(preferredTimeout) { api.getMeta(url) }.meta.sanitize()
                if (meta != null) {
                    return@withContext if (CanonicalStremioMetadata.isCinemetaOrigin(preferredAddonBaseUrl)) {
                        meta.copy(addonBaseUrl = CINEMETA_BASE_URL)
                    } else meta
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { /* try fallback */ }
        }

        // 2) Priority-based fallback across all meta addons
        val addons = allAddons.filter { it.supportsMeta }

        val requestedType = type.trim()
        val inferredType = inferCanonicalType(requestedType, id)

        // Build prioritized candidate list (LinkedHashSet preserves insertion order, deduplicates)
        val candidates = linkedSetOf<Pair<AddonEntity, String>>()

        // Priority 1: Addons that explicitly support the requested type
        for (addon in addons) {
            if (addon.supportsMetaType(requestedType)) {
                candidates.add(addon to requestedType)
            }
        }

        // Priority 2: Addons that support the inferred canonical type (for custom catalog types)
        if (!inferredType.equals(requestedType, ignoreCase = true)) {
            for (addon in addons) {
                if (addon.supportsMetaType(inferredType)) {
                    candidates.add(addon to inferredType)
                }
            }
        }

        // Priority 3: First meta addon as ultimate addon fallback
        addons.firstOrNull()?.let { fallback ->
            val fallbackType = when {
                fallback.supportsMetaType(requestedType) -> requestedType
                fallback.supportsMetaType(inferredType) -> inferredType
                else -> inferredType.ifBlank { requestedType }
            }
            candidates.add(fallback to fallbackType)
        }

        // Try each candidate, skip the preferred addon (already tried above).
        // Validate that returned meta ID matches the request — addons may return
        // meta with a different ID (e.g. tmdb:12345 instead of tt1234567), which causes
        // the details screen to appear stuck since it checks movie.id == requestedId.
        for ((addon, candidateType) in candidates) {
            if (addon.transportUrl == preferredAddonBaseUrl) continue
            try {
                val url = "${addon.transportUrl}/meta/$candidateType/$id.json"
                val meta = withTimeout(CATALOG_TIMEOUT_MS) { api.getMeta(url) }.meta.sanitize()
                if (meta != null && meta.id == id) return@withContext meta
            } catch (_: Exception) { /* try next */ }
        }

        // Last resort: Cinemeta for standard types
        try {
            val url = "https://v3-cinemeta.strem.io/meta/$type/$id.json"
            return@withContext withTimeout(CATALOG_TIMEOUT_MS) { api.getMeta(url) }.meta.sanitize()
                ?.copy(addonBaseUrl = CINEMETA_BASE_URL)
        } catch (_: Exception) { null }
    }

    private fun AddonEntity.supportsMetaType(type: String): Boolean {
        if (!supportsMeta) return false
        val types: List<String> = try {
            gson.fromJson(typesJson, Array<String>::class.java)?.toList() ?: emptyList()
        } catch (_: Exception) { emptyList() }
        if (types.isEmpty()) return true
        return types.any { it.equals(type, ignoreCase = true) }
    }

    private fun inferCanonicalType(type: String, id: String): String {
        val known = setOf("movie", "series", "tv", "channel", "anime")
        if (type.lowercase() in known) return type
        val normalizedId = id.lowercase()
        return when {
            ":movie:" in normalizedId -> "movie"
            ":series:" in normalizedId -> "series"
            ":tv:" in normalizedId -> "tv"
            ":anime:" in normalizedId -> "anime"
            else -> type
        }
    }

    private companion object {
        const val CINEMETA_BASE_URL = "https://v3-cinemeta.strem.io"
        const val CANONICAL_FACT_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1_000
    }
}
