package com.saab.tv.data.tmdb

import android.util.Log
import android.util.LruCache
import com.saab.tv.data.model.tmdb.*
import com.saab.tv.data.cache.SingleFlight
import com.saab.tv.data.cache.metadataRequestScope
import com.saab.tv.data.remote.TmdbApiService
import com.saab.tv.data.repository.CanonicalTitleStore
import com.saab.tv.data.repository.NoopCanonicalTitleStore
import com.saab.tv.domain.schema.ContentIdentity
import com.saab.tv.domain.schema.ContentKind
import com.saab.tv.domain.schema.TitleCardProjection
import com.saab.tv.domain.schema.toCanonicalProjection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TmdbMetadataService"

internal fun mixTmdbMediaTypes(items: List<TitleCardProjection>, limit: Int): List<TitleCardProjection> {
    val distinct = items.distinctBy { it.presentationKey }
    val movies = distinct.filter { it.kind == ContentKind.MOVIE }
    val series = distinct.filter { it.kind == ContentKind.SERIES }
    if (movies.isEmpty() || series.isEmpty()) return distinct.take(limit.coerceAtLeast(0))

    return buildList {
        var index = 0
        while (size < limit && (index < movies.size || index < series.size)) {
            movies.getOrNull(index)?.let(::add)
            series.getOrNull(index)?.let(::add)
            index++
        }
    }.take(limit.coerceAtLeast(0))
}

@Singleton
class TmdbMetadataService @Inject constructor(
    private val tmdbApi: TmdbApiService,
    private val tmdbService: TmdbService,
    private val titleStore: CanonicalTitleStore
) {
    constructor(tmdbApi: TmdbApiService, tmdbService: TmdbService) :
        this(tmdbApi, tmdbService, NoopCanonicalTitleStore)
    private val apiKey get() = tmdbService.apiKey()

    // Keep large cast/episode payloads from growing without bound on long-lived TV devices.
    private val enrichmentCache = LruCache<String, TmdbEnrichment>(48)
    private val homeEnrichmentCache = LruCache<String, TmdbEnrichment>(96)
    private val episodeCache = LruCache<String, Map<Pair<Int, Int>, TmdbEpisodeEnrichment>>(24)
    private val personCache = LruCache<String, TmdbPersonDetail>(64)
    private val playbackCastCache = LruCache<String, List<TmdbCastInfo>>(48)
    private val recommendationsCache = LruCache<String, TmdbDiscoveryPage>(96)
    private val collectionCache = LruCache<String, List<TitleCardProjection>>(24)
    private val discoverCache = LruCache<String, TmdbDiscoveryPage>(48)
    private val requestScope = metadataRequestScope()
    private val requestPermits = Semaphore(4)
    private val enrichmentFlights = SingleFlight<String, TmdbEnrichment?>(requestScope, sharedPermits = requestPermits)
    private val homeEnrichmentFlights = SingleFlight<String, TmdbEnrichment?>(requestScope, sharedPermits = requestPermits)
    private val episodeFlights = SingleFlight<String, Map<Pair<Int, Int>, TmdbEpisodeEnrichment>>(requestScope, sharedPermits = requestPermits)
    private val recommendationFlights = SingleFlight<String, TmdbDiscoveryPage>(requestScope, sharedPermits = requestPermits)
    private val collectionFlights = SingleFlight<String, List<TitleCardProjection>>(requestScope, sharedPermits = requestPermits)
    private val personFlights = SingleFlight<String, TmdbPersonDetail?>(requestScope, sharedPermits = requestPermits)
    private val playbackCastFlights = SingleFlight<String, List<TmdbCastInfo>>(requestScope, sharedPermits = requestPermits)
    private val videoFlights = SingleFlight<String, List<TmdbVideoInfo>>(requestScope, sharedPermits = requestPermits)
    private val bestTrailerFlights = SingleFlight<String, TmdbVideoInfo?>(requestScope, sharedPermits = requestPermits)
    private val entityFlights = SingleFlight<String, TmdbEntityDetail?>(requestScope, sharedPermits = requestPermits)
    private val entityDiscoveryFlights = SingleFlight<String, Pair<List<TitleCardProjection>, Int>>(requestScope, sharedPermits = requestPermits)
    private val discoverFlights = SingleFlight<String, TmdbDiscoveryPage>(requestScope, sharedPermits = requestPermits)

    /**
     * Fetch full metadata enrichment for a title: details, credits, images, age ratings — all in parallel.
     */
    suspend fun fetchEnrichment(
        tmdbId: String,
        mediaType: String,
        language: String = "en"
    ): TmdbEnrichment? {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val kind = ContentKind.fromProvider(mediaType)
            ?.takeIf { it == ContentKind.MOVIE || it == ContentKind.SERIES }
            ?: return null
        val identity = ContentIdentity.tmdb(kind, tmdbId) ?: return null
        val key = "${identity.stableKey}:$normalizedLanguage:details"
        return enrichmentFlights.await(key) {
            loadEnrichment(identity.externalId, kind.keyPart, normalizedLanguage)
        }
    }

    /** Loads only credits for the playback cast panel; avoids the four-request details enrichment. */
    suspend fun fetchCastMembers(
        tmdbId: String,
        mediaType: String,
        language: String = "en"
    ): List<TmdbCastInfo> {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val kind = ContentKind.fromProvider(mediaType)
            ?.takeIf { it == ContentKind.MOVIE || it == ContentKind.SERIES }
            ?: return emptyList()
        val identity = ContentIdentity.tmdb(kind, tmdbId) ?: return emptyList()
        val tmdbType = if (kind == ContentKind.SERIES) "tv" else "movie"
        val key = "${identity.stableKey}:$normalizedLanguage:playback-cast"
        playbackCastCache.get(key)?.let { return it }

        return playbackCastFlights.await(key) {
            withContext(Dispatchers.IO) {
                playbackCastCache.get(key)?.let { return@withContext it }
                val detailsKey = "${identity.externalId}:$tmdbType:$normalizedLanguage:details"
                enrichmentCache.get(detailsKey)?.castMembers?.let { members ->
                    playbackCastCache.put(key, members)
                    return@withContext members
                }

                try {
                    val numericId = identity.externalId.toInt()
                    val credits = when (tmdbType) {
                        "tv" -> tmdbApi.getTvCredits(numericId, apiKey, normalizedLanguage)
                        else -> tmdbApi.getMovieCredits(numericId, apiKey, normalizedLanguage)
                    }.body()
                    val members = credits?.cast.orEmpty().mapNotNull { member ->
                        val name = member.name?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                        TmdbCastInfo(
                            name = name,
                            character = member.character?.trim()?.takeIf(String::isNotEmpty),
                            photo = buildImageUrl(member.profilePath, "w500"),
                            tmdbId = member.id
                        )
                    }
                    playbackCastCache.put(key, members)
                    members
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.w(TAG, "Unable to load playback cast: ${failure.message}")
                    emptyList()
                }
            }
        }
    }

    private suspend fun loadEnrichment(
        tmdbId: String,
        mediaType: String,
        language: String
    ): TmdbEnrichment? = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val kind = ContentKind.fromProvider(mediaType)
            ?.takeIf { it == ContentKind.MOVIE || it == ContentKind.SERIES }
            ?: return@withContext null
        val identity = ContentIdentity.tmdb(kind, tmdbId) ?: return@withContext null
        val tmdbType = if (kind == ContentKind.SERIES) "tv" else "movie"
        // Full Details must never reuse a lightweight Home-card response: Home intentionally
        // skips credits and age certifications, and can be warmed before Details is opened.
        val cacheKey = "${identity.externalId}:$tmdbType:$normalizedLanguage:details"
        enrichmentCache.get(cacheKey)?.let { return@withContext it }

        try {
            val cached = titleStore.getFreshTitle(identity, normalizedLanguage, DETAILS_METADATA_MAX_AGE_MILLIS)
            if (cached?.creditsSource == "TMDB" && cached.creditsObservedAtMillis > 0L &&
                System.currentTimeMillis() - cached.creditsObservedAtMillis in 0..DETAILS_METADATA_MAX_AGE_MILLIS
            ) {
                return@withContext CanonicalHomeMetadata.decode(cached).also {
                    enrichmentCache.put(cacheKey, it)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "Unable to read canonical full details: ${failure.message}")
        }

        val numericId = identity.externalId.toInt()

        try {
            val includeImageLanguage = buildString {
                append(normalizedLanguage.substringBefore("-"))
                append(",")
                append(normalizedLanguage)
                append(",en,null")
            }

            val (details, credits, images, ageRating) = coroutineScope {
                val detailsDeferred = async {
                    when (tmdbType) {
                        "tv" -> tmdbApi.getTvDetails(numericId, apiKey, normalizedLanguage)
                        else -> tmdbApi.getMovieDetails(numericId, apiKey, normalizedLanguage)
                    }.body()
                }
                val creditsDeferred = async {
                    when (tmdbType) {
                        "tv" -> tmdbApi.getTvCredits(numericId, apiKey, normalizedLanguage)
                        else -> tmdbApi.getMovieCredits(numericId, apiKey, normalizedLanguage)
                    }.body()
                }
                val imagesDeferred = async {
                    when (tmdbType) {
                        "tv" -> tmdbApi.getTvImages(numericId, apiKey, includeImageLanguage)
                        else -> tmdbApi.getMovieImages(numericId, apiKey, includeImageLanguage)
                    }.body()
                }
                val ageRatingDeferred = async {
                    when (tmdbType) {
                        "tv" -> {
                            val ratings = tmdbApi.getTvContentRatings(numericId, apiKey).body()?.results.orEmpty()
                            selectTvAgeRating(ratings, normalizedLanguage)
                        }
                        else -> {
                            val releases = tmdbApi.getMovieReleaseDates(numericId, apiKey).body()?.results.orEmpty()
                            selectMovieAgeRating(releases, normalizedLanguage)
                        }
                    }
                }
                Quadruple(
                    detailsDeferred.await(),
                    creditsDeferred.await(),
                    imagesDeferred.await(),
                    ageRatingDeferred.await()
                )
            }
            if (details?.id != numericId) return@withContext null

            val genres = details?.genres?.mapNotNull { it.name.trim().takeIf { n -> n.isNotBlank() } } ?: emptyList()
            val description = details?.overview?.takeIf { it.isNotBlank() }
            val releaseInfo = details?.releaseDate ?: details?.firstAirDate
            val status = details?.status?.trim()?.takeIf { it.isNotBlank() }
            val rating = details?.voteAverage
            val runtime = details?.runtime ?: details?.episodeRunTime?.firstOrNull()
            val localizedTitle = (details?.title ?: details?.name)?.takeIf { it.isNotBlank() }

            val countries = details?.productionCountries
                ?.mapNotNull { it.iso31661?.trim()?.uppercase()?.takeIf { code -> code.isNotBlank() } }
                ?.takeIf { it.isNotEmpty() }
                ?: details?.originCountry?.takeIf { it.isNotEmpty() }
            val originalLanguage = details?.originalLanguage?.takeIf { it.isNotBlank() }

            val productionCompanies = details?.productionCompanies.orEmpty().mapNotNull { company ->
                val name = company.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                TmdbCompanyInfo(name = name, logo = buildImageUrl(company.logoPath, "w300"), tmdbId = company.id)
            }
            val networks = details?.networks.orEmpty().mapNotNull { network ->
                val name = network.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                TmdbCompanyInfo(name = name, logo = buildImageUrl(network.logoPath, "w300"), tmdbId = network.id)
            }

            val poster = buildImageUrl(details?.posterPath, "w500")
            val backdrop = buildImageUrl(details?.backdropPath, "w1280")
            val logoPath = images?.logos?.let { selectBestLocalizedImagePath(it, normalizedLanguage) }
            val logo = buildImageUrl(logoPath, "w500")

            val castMembers = credits?.cast.orEmpty().mapNotNull { member ->
                val name = member.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                TmdbCastInfo(
                    name = name,
                    character = member.character?.takeIf { it.isNotBlank() },
                    photo = buildImageUrl(member.profilePath, "w500"),
                    tmdbId = member.id
                )
            }

            // Crew: creators (TV), directors (movies), writers
            val creatorMembers = if (tmdbType == "tv") {
                details?.createdBy.orEmpty().mapNotNull { creator ->
                    val id = creator.id ?: return@mapNotNull null
                    val name = creator.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    TmdbCastInfo(name = name, character = "Creator", photo = buildImageUrl(creator.profilePath, "w500"), tmdbId = id)
                }.distinctBy { it.tmdbId ?: it.name.lowercase() }
            } else emptyList()

            val directorMembers = credits?.crew.orEmpty()
                .filter { it.job.equals("Director", ignoreCase = true) }
                .mapNotNull { member ->
                    val id = member.id ?: return@mapNotNull null
                    val name = member.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    TmdbCastInfo(name = name, character = "Director", photo = buildImageUrl(member.profilePath, "w500"), tmdbId = id)
                }.distinctBy { it.tmdbId ?: it.name.lowercase() }

            val writerMembers = credits?.crew.orEmpty()
                .filter { crew ->
                    val job = crew.job?.lowercase() ?: ""
                    job.contains("writer") || job.contains("screenplay")
                }
                .mapNotNull { member ->
                    val id = member.id ?: return@mapNotNull null
                    val name = member.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    TmdbCastInfo(name = name, character = "Writer", photo = buildImageUrl(member.profilePath, "w500"), tmdbId = id)
                }.distinctBy { it.tmdbId ?: it.name.lowercase() }

            // Prefer creator (TV) or director (movie); fall back to writer
            val hasCreator = creatorMembers.isNotEmpty()
            val hasDirector = directorMembers.isNotEmpty()
            val exposedDirectorMembers = when {
                tmdbType == "tv" && hasCreator -> creatorMembers
                tmdbType != "tv" && hasDirector -> directorMembers
                else -> emptyList()
            }
            val exposedWriterMembers = when {
                tmdbType == "tv" && hasCreator -> emptyList()
                tmdbType != "tv" && hasDirector -> emptyList()
                else -> writerMembers
            }

            val collectionId = details?.belongsToCollection?.id
            val collectionName = details?.belongsToCollection?.name

            // Don't cache empty results
            if (genres.isEmpty() && description == null && backdrop == null && logo == null &&
                poster == null && castMembers.isEmpty() && releaseInfo == null && rating == null &&
                runtime == null && productionCompanies.isEmpty() && networks.isEmpty()
            ) {
                return@withContext null
            }

            val enrichment = TmdbEnrichment(
                localizedTitle = localizedTitle,
                description = description,
                genres = genres,
                genreIds = details?.genres.orEmpty().map { it.id },
                backdrop = backdrop,
                logo = logo,
                poster = poster,
                directorMembers = exposedDirectorMembers,
                writerMembers = exposedWriterMembers,
                castMembers = castMembers,
                releaseInfo = releaseInfo,
                rating = rating,
                runtimeMinutes = runtime,
                productionCompanies = productionCompanies,
                networks = networks,
                ageRating = ageRating?.value,
                ageRatingRegion = ageRating?.region,
                status = status,
                countries = countries,
                language = originalLanguage,
                collectionId = collectionId,
                collectionName = collectionName
            )
            enrichmentCache.put(cacheKey, enrichment)
            try {
                // Keep the shared canonical fact row warm regardless of which surface
                // requested the full detail payload. Credits and certifications remain
                // details-only; the canonical row stores only the common title facts.
                titleStore.putTitle(
                    CanonicalHomeMetadata.encode(
                        identity = identity,
                        locale = normalizedLanguage,
                        value = enrichment,
                        nowMillis = System.currentTimeMillis(),
                        logoFetched = true,
                        includeDetails = true
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Unable to persist canonical detail facts: ${failure.message}")
            }
            enrichment
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            com.saab.tv.AppDiagnostics.failure("TMDB", "Metadata Enrichment Failed", e)
            Log.e(TAG, "Failed to fetch TMDB enrichment: ${e.message}", e)
            null
        }
    }

    /**
     * Lightweight card enrichment. Landscape cards opt into one parallel images
     * request for a title logo; credits and certifications remain details-only.
     */
    suspend fun fetchHomeEnrichment(
        tmdbId: String,
        mediaType: String,
        language: String = "en",
        includeLogo: Boolean = false
    ): TmdbEnrichment? {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val kind = ContentKind.fromProvider(mediaType)
            ?.takeIf { it == ContentKind.MOVIE || it == ContentKind.SERIES }
            ?: return null
        val identity = ContentIdentity.tmdb(kind, tmdbId) ?: return null
        val key = "${identity.stableKey}:$normalizedLanguage:home:${if (includeLogo) "logo" else "basic"}"
        return homeEnrichmentFlights.await(key) {
            loadHomeEnrichment(identity.externalId, kind.keyPart, normalizedLanguage, includeLogo)
        }
    }

    private suspend fun loadHomeEnrichment(
        tmdbId: String,
        mediaType: String,
        language: String,
        includeLogo: Boolean
    ): TmdbEnrichment? = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val kind = ContentKind.fromProvider(mediaType) ?: return@withContext null
        if (kind != ContentKind.MOVIE && kind != ContentKind.SERIES) return@withContext null
        val identity = ContentIdentity.tmdb(kind, tmdbId) ?: return@withContext null
        val numericId = identity.externalId.toInt()
        val tmdbType = if (kind == ContentKind.SERIES) "tv" else "movie"
        val cacheKey = "$numericId:$tmdbType:$normalizedLanguage${if (includeLogo) ":logo" else ""}"
        enrichmentCache.get(cacheKey)?.let { return@withContext it }
        homeEnrichmentCache.get(cacheKey)?.let { return@withContext it }
        try {
            val cached = titleStore.getFreshTitle(identity, normalizedLanguage, HOME_METADATA_MAX_AGE_MILLIS)
            if (cached != null && cached.kind == kind.keyPart &&
                (!includeLogo || cached.logo != null || cached.logoObservedAtMillis > 0)) {
                val enrichment = CanonicalHomeMetadata.decode(cached)
                homeEnrichmentCache.put(cacheKey, enrichment)
                return@withContext enrichment
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "Unable to read canonical Home metadata: ${failure.message}")
        }
        try {
            val includeImageLanguage = "${normalizedLanguage.substringBefore("-")},$normalizedLanguage,en,null"
            val (details, logoPath) = coroutineScope {
                val detailsDeferred = async {
                    when (tmdbType) {
                        "tv" -> tmdbApi.getTvDetails(numericId, apiKey, normalizedLanguage)
                        else -> tmdbApi.getMovieDetails(numericId, apiKey, normalizedLanguage)
                    }.body()
                }
                val logoDeferred = if (includeLogo) async {
                    try {
                        val images = when (tmdbType) {
                            "tv" -> tmdbApi.getTvImages(numericId, apiKey, includeImageLanguage)
                            else -> tmdbApi.getMovieImages(numericId, apiKey, includeImageLanguage)
                        }.body()
                        images?.logos?.let { selectBestLocalizedImagePath(it, normalizedLanguage) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Log.w(TAG, "Failed to fetch landscape title logo for $tmdbId: ${failure.message}")
                        null
                    }
                } else null
                detailsDeferred.await() to logoDeferred?.await()
            }
            if (details == null || details.id != numericId) return@withContext null

            val enrichment = TmdbEnrichment(
                localizedTitle = (details.title ?: details.name)?.takeIf(String::isNotBlank),
                description = details.overview?.takeIf(String::isNotBlank),
                genres = details.genres.orEmpty().mapNotNull { it.name.trim().takeIf(String::isNotBlank) },
                genreIds = details.genres.orEmpty().map { it.id },
                backdrop = buildImageUrl(details.backdropPath, "w1280"),
                logo = buildImageUrl(logoPath, "w500"),
                poster = buildImageUrl(details.posterPath, "w500"),
                directorMembers = emptyList(),
                writerMembers = emptyList(),
                castMembers = emptyList(),
                releaseInfo = details.releaseDate ?: details.firstAirDate,
                rating = details.voteAverage,
                runtimeMinutes = details.runtime ?: details.episodeRunTime?.firstOrNull(),
                productionCompanies = emptyList(),
                networks = emptyList(),
                ageRating = null,
                status = details.status?.trim()?.takeIf(String::isNotBlank),
                countries = details.productionCountries.orEmpty().mapNotNull { country ->
                    country.iso31661?.trim()?.uppercase()?.takeIf(String::isNotBlank)
                }.ifEmpty { details.originCountry.orEmpty() },
                language = details.originalLanguage?.takeIf(String::isNotBlank),
                collectionId = details.belongsToCollection?.id,
                collectionName = details.belongsToCollection?.name
            )
            try {
                titleStore.putTitle(CanonicalHomeMetadata.encode(identity, normalizedLanguage, enrichment, System.currentTimeMillis(), includeLogo))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Unable to cache canonical Home metadata: ${failure.message}")
            }
            homeEnrichmentCache.put(cacheKey, enrichment)
            enrichment
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            com.saab.tv.AppDiagnostics.failure("TMDB", "Home Metadata Failed", failure)
            Log.w(TAG, "Failed to fetch Home metadata for $tmdbId: ${failure.message}")
            null
        }
    }

    /**
     * Fetch localized episode metadata for given seasons.
     */
    suspend fun fetchEpisodeEnrichment(
        tmdbId: String,
        seasonNumbers: List<Int>,
        language: String = "en"
    ): Map<Pair<Int, Int>, TmdbEpisodeEnrichment> {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val key = "$tmdbId:${seasonNumbers.distinct().sorted().joinToString(",")}:$normalizedLanguage"
        return episodeFlights.await(key) { loadEpisodeEnrichment(tmdbId, seasonNumbers, normalizedLanguage) }
    }

    private suspend fun loadEpisodeEnrichment(
        tmdbId: String,
        seasonNumbers: List<Int>,
        language: String
    ): Map<Pair<Int, Int>, TmdbEpisodeEnrichment> = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val cacheKey = "$tmdbId:${seasonNumbers.sorted().joinToString(",")}:$normalizedLanguage"
        episodeCache.get(cacheKey)?.let { return@withContext it }

        val numericId = tmdbId.toIntOrNull() ?: return@withContext emptyMap()
        val result = mutableMapOf<Pair<Int, Int>, TmdbEpisodeEnrichment>()

        val seasonResponses = coroutineScope {
            val requestLimit = Semaphore(3)
            seasonNumbers.distinct().map { season ->
                async {
                    try {
                        season to requestLimit.withPermit {
                            tmdbApi.getTvSeasonDetails(numericId, season, apiKey, normalizedLanguage)
                                .body()?.episodes.orEmpty()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        com.saab.tv.AppDiagnostics.failure("TMDB", "Season Enrichment Failed", failure)
                        Log.w(TAG, "Failed to fetch TMDB season $season: ${failure.message}")
                        season to emptyList()
                    }
                }
            }.awaitAll()
        }

        seasonResponses.forEach { (season, episodes) ->
            episodes.forEach { ep ->
                    val epNum = ep.episodeNumber ?: return@forEach
                    result[season to epNum] = TmdbEpisodeEnrichment(
                        title = ep.name?.takeIf { it.isNotBlank() },
                        overview = ep.overview?.takeIf { it.isNotBlank() },
                        thumbnail = ep.stillPath?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" },
                        airDate = ep.airDate?.takeIf { it.isNotBlank() },
                        runtimeMinutes = ep.runtime
                    )
            }
        }

        if (result.isNotEmpty()) episodeCache.put(cacheKey, result)
        result
    }

    /**
     * Fetch "More Like This" recommendations.
     */
    suspend fun fetchRecommendations(
        tmdbId: String,
        mediaType: String,
        language: String = "en",
        maxItems: Int = 12
    ): List<TitleCardProjection> {
        return fetchRecommendationsPage(tmdbId, mediaType, language, page = 1, maxItems = maxItems).items
    }

    suspend fun fetchRecommendationsPage(
        tmdbId: String,
        mediaType: String,
        language: String = "en",
        page: Int,
        maxItems: Int = 20
    ): TmdbDiscoveryPage {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val tmdbType = if (mediaType == "tv" || mediaType == "series") "tv" else "movie"
        val safePage = page.coerceIn(1, 500)
        val key = "$tmdbId:$tmdbType:$normalizedLanguage:recs:$safePage:${maxItems.coerceAtLeast(0)}"
        return recommendationFlights.await(key) {
            loadRecommendations(tmdbId, mediaType, normalizedLanguage, safePage, maxItems)
        }
    }

    private suspend fun loadRecommendations(
        tmdbId: String,
        mediaType: String,
        language: String,
        page: Int,
        maxItems: Int
    ): TmdbDiscoveryPage = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val tmdbType = if (mediaType == "tv" || mediaType == "series") "tv" else "movie"
        val cacheKey = "$tmdbId:$tmdbType:$normalizedLanguage:recs:$page:$maxItems"
        recommendationsCache.get(cacheKey)?.let { return@withContext it }

        val numericId = tmdbId.toIntOrNull() ?: return@withContext TmdbDiscoveryPage(emptyList(), page, false)

        try {
            val recommendations = when (tmdbType) {
                "tv" -> tmdbApi.getTvRecommendations(numericId, apiKey, normalizedLanguage, page).body()
                else -> tmdbApi.getMovieRecommendations(numericId, apiKey, normalizedLanguage, page).body()
            }

            val rawResults = recommendations?.results.orEmpty().filter { it.id > 0 }
            val languageCode = normalizedLanguage.substringBefore("-")

            val sorted = rawResults.sortedWith(
                compareByDescending<TmdbRecommendationResult> {
                    it.originalLanguage?.equals(languageCode, ignoreCase = true) == true
                }.thenByDescending { it.voteCount ?: 0 }
                    .thenByDescending { it.voteAverage ?: 0.0 }
            )

            val filtered = sorted.filter { rec ->
                val localized = rec.originalLanguage?.equals(languageCode, ignoreCase = true) == true
                localized || (rec.voteCount ?: 0) >= 20 || (rec.voteAverage ?: 0.0) >= 6.0
            }.ifEmpty { sorted }

            val items = filtered.take(maxItems).mapNotNull { rec ->
                val title = rec.title?.takeIf { it.isNotBlank() }
                    ?: rec.name?.takeIf { it.isNotBlank() }
                    ?: rec.originalTitle?.takeIf { it.isNotBlank() }
                    ?: rec.originalName?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val recType = when (rec.mediaType?.trim()?.lowercase()) {
                    "tv" -> "series"
                    "movie" -> "movie"
                    else -> if (tmdbType == "tv") "series" else "movie"
                }

                TmdbMetaPreview(
                    tmdbId = rec.id,
                    type = recType,
                    name = title,
                    poster = buildImageUrl(rec.posterPath, "w500"),
                    backdrop = buildImageUrl(rec.backdropPath, "w1280"),
                    description = rec.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = rec.releaseDate?.take(4) ?: rec.firstAirDate?.take(4),
                    rating = rec.voteAverage
                )
            }.mapNotNull(TmdbMetaPreview::toCanonicalProjection)

            TmdbDiscoveryPage(
                items = items,
                page = page,
                hasMore = page < (recommendations?.totalPages ?: 1).coerceAtMost(500)
            ).also { recommendationsCache.put(cacheKey, it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            com.saab.tv.AppDiagnostics.failure("TMDB", "Recommendations Failed", e)
            Log.w(TAG, "Failed to fetch recommendations for $tmdbId: ${e.message}")
            TmdbDiscoveryPage(emptyList(), page, hasMore = false)
        }
    }

    /**
     * Fetch all movies in a collection, ordered by release date.
     */
    suspend fun fetchCollection(
        collectionId: Int,
        language: String = "en"
    ): List<TitleCardProjection> {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        return collectionFlights.await("$collectionId:$normalizedLanguage:collection") {
            loadCollection(collectionId, normalizedLanguage)
        }
    }

    private suspend fun loadCollection(
        collectionId: Int,
        language: String
    ): List<TitleCardProjection> = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val cacheKey = "$collectionId:$normalizedLanguage:collection"
        collectionCache.get(cacheKey)?.let { return@withContext it }

        try {
            val collection = tmdbApi.getCollectionDetails(collectionId, apiKey, normalizedLanguage).body()
            val parts = collection?.parts.orEmpty().sortedBy { it.releaseDate ?: "9999" }

            val items = parts.mapNotNull { part ->
                val title = part.title ?: return@mapNotNull null
                TmdbMetaPreview(
                    tmdbId = part.id,
                    type = "movie",
                    name = title,
                    poster = buildImageUrl(part.posterPath, "w500"),
                    backdrop = buildImageUrl(part.backdropPath, "w1280"),
                    description = part.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = part.releaseDate?.take(4),
                    rating = part.voteAverage
                )
            }.mapNotNull(TmdbMetaPreview::toCanonicalProjection)

            collectionCache.put(cacheKey, items)
            items
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            com.saab.tv.AppDiagnostics.failure("TMDB", "Collection Failed", e)
            Log.w(TAG, "Failed to fetch collection $collectionId: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetch person detail with filmography.
     */
    suspend fun fetchPersonDetail(
        personId: Int,
        language: String = "en"
    ): TmdbPersonDetail? {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        return personFlights.await("$personId:$normalizedLanguage:person") {
            loadPersonDetail(personId, normalizedLanguage)
        }
    }

    private suspend fun loadPersonDetail(
        personId: Int,
        language: String
    ): TmdbPersonDetail? = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val cacheKey = "$personId:$normalizedLanguage"
        personCache.get(cacheKey)?.let { return@withContext it }

        try {
            val (person, credits) = coroutineScope {
                val personDeferred = async {
                    tmdbApi.getPersonDetails(personId, apiKey, normalizedLanguage).body()
                }
                val creditsDeferred = async {
                    tmdbApi.getPersonCombinedCredits(personId, apiKey, normalizedLanguage).body()
                }
                Pair(personDeferred.await(), creditsDeferred.await())
            }

            if (person == null) return@withContext null

            // English fallback for empty biography
            val biography = if (person.biography.isNullOrBlank() && normalizedLanguage != "en") {
                try {
                    tmdbApi.getPersonDetails(personId, apiKey, "en").body()?.biography
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            } else {
                person.biography
            }?.takeIf { it.isNotBlank() }

            val preferCrew = person.knownForDepartment?.trim()?.lowercase()?.let {
                it.isNotBlank() && it != "acting" && it != "actors"
            } ?: false

            val movieCredits = mapCredits(credits?.cast.orEmpty(), credits?.crew.orEmpty(), "movie", preferCrew)
                .mapNotNull(TmdbMetaPreview::toCanonicalProjection)
            val tvCredits = mapCredits(credits?.cast.orEmpty(), credits?.crew.orEmpty(), "tv", preferCrew)
                .mapNotNull(TmdbMetaPreview::toCanonicalProjection)

            val detail = TmdbPersonDetail(
                tmdbId = person.id,
                name = person.name ?: "Unknown",
                biography = biography,
                birthday = person.birthday?.takeIf { it.isNotBlank() },
                deathday = person.deathday?.takeIf { it.isNotBlank() },
                placeOfBirth = person.placeOfBirth?.takeIf { it.isNotBlank() },
                profilePhoto = buildImageUrl(person.profilePath, "w500"),
                knownFor = person.knownForDepartment?.takeIf { it.isNotBlank() },
                movieCredits = movieCredits,
                tvCredits = tvCredits
            )
            personCache.put(cacheKey, detail)
            detail
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            com.saab.tv.AppDiagnostics.failure("TMDB", "Person Details Failed", e)
            Log.e(TAG, "Failed to fetch person detail: ${e.message}", e)
            null
        }
    }

    /**
     * Fetch trailers/videos for a title.
     */
    suspend fun fetchVideos(
        tmdbId: String,
        mediaType: String,
        language: String = "en"
    ): List<TmdbVideoInfo> {
        val type = if (mediaType == "tv" || mediaType == "series") "tv" else "movie"
        val normalizedLanguage = normalizeTmdbLanguage(language)
        return videoFlights.await("$tmdbId:$type:$normalizedLanguage:videos") {
            loadVideos(tmdbId, type, normalizedLanguage)
        }
    }

    private suspend fun loadVideos(
        tmdbId: String,
        mediaType: String,
        language: String
    ): List<TmdbVideoInfo> = withContext(Dispatchers.IO) {
        val numericId = tmdbId.toIntOrNull() ?: return@withContext emptyList()
        val tmdbType = if (mediaType == "tv" || mediaType == "series") "tv" else "movie"
        val normalizedLanguage = normalizeTmdbLanguage(language)

        try {
            val response = when (tmdbType) {
                "tv" -> tmdbApi.getTvVideos(numericId, apiKey, normalizedLanguage)
                else -> tmdbApi.getMovieVideos(numericId, apiKey, normalizedLanguage)
            }
            val results = response.body()?.results.orEmpty()

            // Prioritize: official trailers > trailers > teasers > others
            results
                .filter { it.site.equals("YouTube", ignoreCase = true) && !it.key.isNullOrBlank() }
                .sortedWith(
                    compareByDescending<TmdbVideoResult> { it.official == true }
                        .thenByDescending { it.type.equals("Trailer", ignoreCase = true) }
                        .thenByDescending { it.type.equals("Teaser", ignoreCase = true) }
                        .thenByDescending { it.size ?: 0 }
                )
                .mapNotNull { video ->
                    val key = video.key?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    TmdbVideoInfo(
                        name = video.name ?: "Trailer",
                        key = key,
                        type = video.type ?: "Video",
                        thumbnail = "https://img.youtube.com/vi/$key/hqdefault.jpg"
                    )
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            com.saab.tv.AppDiagnostics.failure("TMDB", "Videos Failed", e)
            Log.w(TAG, "Failed to fetch videos for $tmdbId: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetch the best single trailer key (YouTube video ID) for a title.
     * Tries the preferred language first, falls back to English.
     * Only considers trailers and teasers, ranked by: trailer > teaser, official first, larger size, newest.
     */
    suspend fun fetchBestTrailerKey(
        tmdbId: String,
        mediaType: String,
        language: String = "en"
    ): TmdbVideoInfo? {
        val type = if (mediaType == "tv" || mediaType == "series") "tv" else "movie"
        val normalizedLanguage = normalizeTmdbLanguage(language)
        return bestTrailerFlights.await("$tmdbId:$type:$normalizedLanguage:best-trailer") {
            loadBestTrailerKey(tmdbId, type, normalizedLanguage)
        }
    }

    private suspend fun loadBestTrailerKey(
        tmdbId: String,
        mediaType: String,
        language: String
    ): TmdbVideoInfo? = withContext(Dispatchers.IO) {
        val numericId = tmdbId.toIntOrNull() ?: return@withContext null
        val tmdbType = if (mediaType == "tv" || mediaType == "series") "tv" else "movie"
        val normalizedLanguage = normalizeTmdbLanguage(language)

        try {
            // Try preferred language first
            val localizedResult = fetchAndRankTrailers(numericId, tmdbType, normalizedLanguage)
            if (localizedResult != null) return@withContext localizedResult

            // Fallback to English if different
            if (!normalizedLanguage.equals("en", ignoreCase = true) && !normalizedLanguage.startsWith("en-")) {
                val englishResult = fetchAndRankTrailers(numericId, tmdbType, "en-US")
                if (englishResult != null) return@withContext englishResult
            }

            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            com.saab.tv.AppDiagnostics.failure("TMDB", "Trailer Lookup Failed", e)
            Log.w(TAG, "Failed to fetch best trailer for $tmdbId: ${e.message}")
            null
        }
    }

    private suspend fun fetchAndRankTrailers(tmdbId: Int, tmdbType: String, language: String): TmdbVideoInfo? {
        val response = when (tmdbType) {
            "tv" -> tmdbApi.getTvVideos(tmdbId, apiKey, language)
            else -> tmdbApi.getMovieVideos(tmdbId, apiKey, language)
        }
        val results = response.body()?.results.orEmpty()

        return results
            .filter { it.site.equals("YouTube", ignoreCase = true) && !it.key.isNullOrBlank() }
            .filter {
                val t = it.type?.trim()?.lowercase()
                t == "trailer" || t == "teaser"
            }
            .sortedWith(
                compareBy<TmdbVideoResult> { if (it.type.equals("Trailer", ignoreCase = true)) 0 else 1 }
                    .thenBy { if (it.official == true) 0 else 1 }
                    .thenByDescending { it.size ?: 0 }
            )
            .firstOrNull()
            ?.let { video ->
                val key = video.key?.takeIf { it.isNotBlank() } ?: return@let null
                TmdbVideoInfo(
                    name = video.name ?: "Trailer",
                    key = key,
                    type = video.type ?: "Trailer",
                    thumbnail = "https://img.youtube.com/vi/$key/hqdefault.jpg"
                )
            }
    }

    /**
     * Fetch company or network detail.
     */
    suspend fun fetchEntityDetail(
        entityId: Int,
        kind: String // "company" or "network"
    ): TmdbEntityDetail? = entityFlights.await("$kind:$entityId:entity") {
        loadEntityDetail(entityId, kind)
    }

    private suspend fun loadEntityDetail(
        entityId: Int,
        kind: String
    ): TmdbEntityDetail? = withContext(Dispatchers.IO) {
        try {
            if (kind == "network") {
                val resp = tmdbApi.getNetworkDetails(entityId, apiKey).body() ?: return@withContext null
                TmdbEntityDetail(
                    tmdbId = resp.id, kind = kind, name = resp.name ?: "Unknown",
                    logo = buildImageUrl(resp.logoPath, "w500"),
                    originCountry = resp.originCountry?.takeIf { it.isNotBlank() },
                    headquarters = resp.headquarters?.takeIf { it.isNotBlank() },
                    description = null
                )
            } else {
                val resp = tmdbApi.getCompanyDetails(entityId, apiKey).body() ?: return@withContext null
                TmdbEntityDetail(
                    tmdbId = resp.id, kind = kind, name = resp.name ?: "Unknown",
                    logo = buildImageUrl(resp.logoPath, "w500"),
                    originCountry = resp.originCountry?.takeIf { it.isNotBlank() },
                    headquarters = resp.headquarters?.takeIf { it.isNotBlank() },
                    description = resp.description?.takeIf { it.isNotBlank() }
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            com.saab.tv.AppDiagnostics.failure("TMDB", "Entity Details Failed", e)
            Log.e(TAG, "Failed to fetch $kind detail $entityId: ${e.message}", e)
            null
        }
    }

    /**
     * Fetch discover results for a company or network.
     */
    suspend fun fetchDiscover(
        entityId: Int,
        kind: String,
        mediaType: String,
        sortBy: String,
        page: Int = 1,
        voteCountGte: Int? = null,
        dateLte: String? = null,
        language: String = "en"
    ): Pair<List<TitleCardProjection>, Int> {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val key = "$kind:$entityId:$mediaType:$sortBy:$page:$voteCountGte:$dateLte:$normalizedLanguage"
        return entityDiscoveryFlights.await(key) {
            loadDiscover(entityId, kind, mediaType, sortBy, page, voteCountGte, dateLte, normalizedLanguage)
        }
    }

    private suspend fun loadDiscover(
        entityId: Int,
        kind: String,
        mediaType: String,
        sortBy: String,
        page: Int,
        voteCountGte: Int?,
        dateLte: String?,
        language: String
    ): Pair<List<TitleCardProjection>, Int> = withContext(Dispatchers.IO) {
        try {
            val normalizedLanguage = normalizeTmdbLanguage(language)
            val resp = if (mediaType == "movie") {
                tmdbApi.discoverMovies(
                    apiKey = apiKey, language = normalizedLanguage, page = page,
                    sortBy = sortBy, withCompanies = entityId.toString(),
                    releaseDateLte = dateLte, voteCountGte = voteCountGte
                ).body()
            } else {
                tmdbApi.discoverTv(
                    apiKey = apiKey, language = normalizedLanguage, page = page,
                    sortBy = sortBy,
                    withCompanies = if (kind == "company") entityId.toString() else null,
                    withNetworks = if (kind == "network") entityId.toString() else null,
                    firstAirDateLte = dateLte, voteCountGte = voteCountGte
                ).body()
            }
            val items = resp?.results?.mapNotNull { r ->
                val title = (if (mediaType == "tv") r.name ?: r.title else r.title ?: r.name) ?: return@mapNotNull null
                val poster = buildImageUrl(r.posterPath, "w500") ?: return@mapNotNull null
                val year = if (mediaType == "tv") r.firstAirDate?.take(4) else r.releaseDate?.take(4)
                TmdbMetaPreview(
                    tmdbId = r.id, type = if (mediaType == "tv") "series" else "movie",
                    name = title, poster = poster,
                    backdrop = buildImageUrl(r.backdropPath, "w1280"),
                    description = r.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = year, rating = r.voteAverage
                )
            }?.mapNotNull(TmdbMetaPreview::toCanonicalProjection) ?: emptyList()
            Pair(items, resp?.totalPages ?: 1)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            com.saab.tv.AppDiagnostics.failure("TMDB", "Entity Discovery Failed", e)
            Log.w(TAG, "Failed to discover $mediaType for $kind $entityId: ${e.message}")
            Pair(emptyList(), 1)
        }
    }

    suspend fun discoverByIntent(
        intent: TmdbNaturalQuery,
        language: String = "en",
        maxItems: Int = 30
    ): List<TitleCardProjection> {
        return discoverByIntentPage(intent, language, page = 1, maxItems = maxItems).items
    }

    suspend fun discoverByIntentPage(
        intent: TmdbNaturalQuery,
        language: String = "en",
        page: Int,
        maxItems: Int = 30
    ): TmdbDiscoveryPage {
        val responseLanguage = normalizeTmdbLanguage(language)
        val safePage = page.coerceIn(1, 500)
        val key = "$intent:$responseLanguage:$safePage:${maxItems.coerceAtLeast(0)}"
        return discoverFlights.await(key) {
            loadDiscoverByIntent(intent, responseLanguage, safePage, maxItems)
        }
    }

    private suspend fun loadDiscoverByIntent(
        intent: TmdbNaturalQuery,
        language: String,
        page: Int,
        maxItems: Int
    ): TmdbDiscoveryPage = withContext(Dispatchers.IO) {
        val responseLanguage = normalizeTmdbLanguage(language)
        val cacheKey = "$intent:$responseLanguage:$page:$maxItems"
        discoverCache.get(cacheKey)?.let { return@withContext it }
        val types = intent.mediaType?.let(::listOf) ?: listOf("movie", "tv")
        try {
            val results = coroutineScope {
                types.map { type -> async {
                    val genreIds = if (type == "tv") intent.tvGenreIds ?: intent.genreIds else intent.genreIds
                    val response = if (type == "tv") {
                        tmdbApi.discoverTv(
                            apiKey = apiKey, language = responseLanguage, page = page,
                            sortBy = if (intent.sortBy == "release_date.desc") "first_air_date.desc" else intent.sortBy,
                            firstAirDateGte = intent.releaseDateGte,
                            firstAirDateLte = intent.releaseDateLte,
                            voteCountGte = 10,
                            withGenres = genreIds,
                            withOriginalLanguage = intent.originalLanguage
                        )
                    } else {
                        tmdbApi.discoverMovies(
                            apiKey = apiKey, language = responseLanguage, page = page,
                            sortBy = if (intent.sortBy == "release_date.desc") "primary_release_date.desc" else intent.sortBy,
                            releaseDateGte = intent.releaseDateGte,
                            releaseDateLte = intent.releaseDateLte,
                            voteCountGte = 10,
                            withGenres = genreIds,
                            withOriginalLanguage = intent.originalLanguage
                        )
                    }
                    val body = response.body()
                    type to Triple(body?.results.orEmpty(), body?.totalPages ?: 1, !response.isSuccessful)
                } }.awaitAll()
            }
            val previews = results.flatMap { (type, pageResult) -> pageResult.first.mapNotNull { result ->
                val title = result.title?.takeIf(String::isNotBlank)
                    ?: result.name?.takeIf(String::isNotBlank)
                    ?: result.originalTitle?.takeIf(String::isNotBlank)
                    ?: result.originalName?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                TmdbMetaPreview(
                    tmdbId = result.id,
                    type = if (type == "tv") "series" else "movie",
                    name = title,
                    poster = buildImageUrl(result.posterPath, "w500"),
                    backdrop = buildImageUrl(result.backdropPath, "w1280"),
                    description = result.overview?.takeIf(String::isNotBlank),
                    releaseInfo = result.releaseDate?.take(4) ?: result.firstAirDate?.take(4),
                    rating = result.voteAverage,
                    popularity = result.popularity
                )
            } }
            val sorted = when (intent.sortBy) {
                "popularity.desc" -> previews.sortedByDescending { it.popularity ?: 0.0 }
                "release_date.desc" -> previews.sortedByDescending { it.releaseInfo ?: "" }
                else -> previews.sortedByDescending { it.rating ?: 0.0 }
            }
            val projections = sorted.mapNotNull(TmdbMetaPreview::toCanonicalProjection)
            val items = if (intent.mediaType == null) mixTmdbMediaTypes(projections, maxItems) else projections.take(maxItems)
            val failed = results.any { it.second.third }
            TmdbDiscoveryPage(
                items = items,
                page = page,
                hasMore = page < (results.maxOfOrNull { it.second.second } ?: 1).coerceAtMost(500),
                failed = failed
            ).also { if (!it.failed) discoverCache.put(cacheKey, it) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            com.saab.tv.AppDiagnostics.failure("TMDB", "Intent Discovery Failed", failure)
            Log.w(TAG, "TMDB discovery failed: ${failure.message}")
            TmdbDiscoveryPage(emptyList(), page, hasMore = false, failed = true)
        }
    }

    // ── Private helpers ──

    private fun mapCredits(
        cast: List<TmdbPersonCreditCast>,
        crew: List<TmdbPersonCreditCrew>,
        targetType: String,
        preferCrew: Boolean
    ): List<TmdbMetaPreview> {
        val seenIds = mutableSetOf<Int>()

        val castCredits = cast
            .filter { it.mediaType == targetType && it.posterPath != null }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seenIds.add(credit.id)) return@mapNotNull null
                val title = (if (targetType == "tv") credit.name ?: credit.title else credit.title ?: credit.name)
                    ?: return@mapNotNull null
                val year = if (targetType == "tv") credit.firstAirDate?.take(4) else credit.releaseDate?.take(4)
                TmdbMetaPreview(
                    tmdbId = credit.id,
                    type = if (targetType == "tv") "series" else "movie",
                    name = title,
                    poster = buildImageUrl(credit.posterPath, "w500"),
                    backdrop = buildImageUrl(credit.backdropPath, "w1280"),
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = year,
                    rating = credit.voteAverage
                )
            }

        val crewSeenIds = mutableSetOf<Int>()
        val crewCredits = crew
            .filter { it.mediaType == targetType && it.posterPath != null }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!crewSeenIds.add(credit.id)) return@mapNotNull null
                val title = (if (targetType == "tv") credit.name ?: credit.title else credit.title ?: credit.name)
                    ?: return@mapNotNull null
                val year = if (targetType == "tv") credit.firstAirDate?.take(4) else credit.releaseDate?.take(4)
                TmdbMetaPreview(
                    tmdbId = credit.id,
                    type = if (targetType == "tv") "series" else "movie",
                    name = title,
                    poster = buildImageUrl(credit.posterPath, "w500"),
                    backdrop = buildImageUrl(credit.backdropPath, "w1280"),
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = year,
                    rating = credit.voteAverage
                )
            }

        return when {
            preferCrew && crewCredits.isNotEmpty() -> crewCredits
            castCredits.isNotEmpty() -> castCredits
            else -> crewCredits
        }
    }

    private fun buildImageUrl(path: String?, size: String): String? {
        val clean = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return "https://image.tmdb.org/t/p/$size$clean"
    }

    private fun normalizeTmdbLanguage(language: String?): String {
        val raw = language?.trim()?.takeIf { it.isNotBlank() }?.replace('_', '-') ?: return "en"
        val normalized = raw.split("-").let { parts ->
            if (parts.size == 2) "${parts[0].lowercase(Locale.US)}-${parts[1].uppercase(Locale.US)}"
            else raw.lowercase(Locale.US)
        }
        return when (normalized) {
            "es-419" -> "es-MX"
            else -> normalized
        }
    }

    private fun selectBestLocalizedImagePath(images: List<TmdbImage>, normalizedLanguage: String): String? {
        if (images.isEmpty()) return null
        val languageCode = normalizedLanguage.substringBefore("-")
        val regionCode = normalizedLanguage.substringAfter("-", "").uppercase(Locale.US).takeIf { it.length == 2 }
            ?: DEFAULT_LANGUAGE_REGIONS[languageCode]
        return images.sortedWith(
            compareByDescending<TmdbImage> { it.iso6391 == languageCode && it.iso31661 == regionCode }
                .thenByDescending { it.iso6391 == languageCode && it.iso31661 == null }
                .thenByDescending { it.iso6391 == languageCode }
                .thenByDescending { it.iso6391 == "en" }
                .thenByDescending { it.iso6391 == null }
        ).firstOrNull()?.filePath
    }

    private fun preferredRegions(normalizedLanguage: String): List<String> {
        val fromLanguage = normalizedLanguage.substringAfter("-", "").uppercase(Locale.US).takeIf { it.length == 2 }
        return buildList {
            if (!fromLanguage.isNullOrBlank()) add(fromLanguage)
            add("US")
            add("GB")
        }.distinct()
    }

    private fun selectMovieAgeRating(countries: List<TmdbMovieReleaseDateCountry>, normalizedLanguage: String): AgeRatingSelection? {
        val preferred = preferredRegions(normalizedLanguage)
        val byRegion = countries.associateBy { it.iso31661?.uppercase(Locale.US) }
        preferred.forEach { region ->
            val rating = byRegion[region]?.releaseDates.orEmpty()
                .mapNotNull { it.certification?.trim() }
                .firstOrNull { it.isNotBlank() }
            if (!rating.isNullOrBlank()) return AgeRatingSelection(rating, region)
        }
        return countries.asSequence().mapNotNull { country ->
            val rating = country.releaseDates.orEmpty().mapNotNull { it.certification?.trim() }
                .firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
            AgeRatingSelection(rating, country.iso31661?.uppercase(Locale.US))
        }.firstOrNull()
    }

    private fun selectTvAgeRating(ratings: List<TmdbTvContentRatingItem>, normalizedLanguage: String): AgeRatingSelection? {
        val preferred = preferredRegions(normalizedLanguage)
        val byRegion = ratings.associateBy { it.iso31661?.uppercase(Locale.US) }
        preferred.forEach { region ->
            val rating = byRegion[region]?.rating?.trim()
            if (!rating.isNullOrBlank()) return AgeRatingSelection(rating, region)
        }
        return ratings.firstNotNullOfOrNull { item ->
            item.rating?.trim()?.takeIf(String::isNotBlank)?.let {
                AgeRatingSelection(it, item.iso31661?.uppercase(Locale.US))
            }
        }
    }

    companion object {
        private const val HOME_METADATA_MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1_000
        private const val DETAILS_METADATA_MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1_000
        private val DEFAULT_LANGUAGE_REGIONS = mapOf("pt" to "PT", "es" to "ES")
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
private data class AgeRatingSelection(val value: String, val region: String?)
