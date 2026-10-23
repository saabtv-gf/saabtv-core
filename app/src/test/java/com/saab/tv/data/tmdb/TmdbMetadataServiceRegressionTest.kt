package com.saab.tv.data.tmdb

import android.app.Application
import com.saab.tv.data.model.tmdb.*
import com.saab.tv.data.model.CanonicalTitleEntity
import com.saab.tv.data.remote.TmdbApiService
import com.saab.tv.data.repository.CanonicalTitleStore
import com.saab.tv.domain.schema.ContentIdentity
import com.saab.tv.domain.schema.ContentKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.lang.reflect.Proxy
import java.util.Collections
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import java.net.SocketException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TmdbMetadataServiceRegressionTest {

    @Test fun movieEnrichmentMapsDetailsCreditsImagesAndRegionalRating() = runBlocking {
        val stub = ApiStub(mapOf(
            "getMovieDetails" to TmdbDetailsResponse(
                id = 10, title = "Localized Film", overview = "A short synopsis",
                genres = listOf(TmdbGenre(1, "Drama"), TmdbGenre(2, " ")),
                releaseDate = "2024-05-01", runtime = 111, voteAverage = 7.8,
                productionCompanies = listOf(TmdbCompany(5, "Studio", "/studio.png")),
                productionCountries = listOf(TmdbCountry("in", "India")),
                originalLanguage = "hi", posterPath = "/poster.jpg", backdropPath = "/backdrop.jpg",
                status = "Released", belongsToCollection = TmdbCollectionSummary(44, "Film Series")
            ),
            "getMovieCredits" to TmdbCreditsResponse(
                cast = listOf(TmdbCastMember(9, "Actor", "Lead", "/actor.jpg"), TmdbCastMember(10, " ")),
                crew = listOf(
                    TmdbCrewMember(20, "Director", "Director", profilePath = "/director.jpg"),
                    TmdbCrewMember(21, "Writer", "Screenplay"),
                    TmdbCrewMember(22, "Ignored", "Producer")
                )
            ),
            "getMovieImages" to TmdbImagesResponse(logos = listOf(
                TmdbImage("/english.png", "en", null), TmdbImage("/localized.png", "hi", "IN")
            )),
            "getMovieReleaseDates" to TmdbMovieReleaseDatesResponse(listOf(
                TmdbMovieReleaseDateCountry("US", listOf(
                    TmdbMovieReleaseDateItem(certification = "PG-13"),
                    TmdbMovieReleaseDateItem(releaseDate = "2024-05-02T00:00:00.000Z", type = 4),
                    TmdbMovieReleaseDateItem(releaseDate = "2024-06-01T00:00:00.000Z", type = 5)
                )),
                TmdbMovieReleaseDateCountry("IN", listOf(
                    TmdbMovieReleaseDateItem(certification = "U/A 16+"),
                    TmdbMovieReleaseDateItem(releaseDate = "2024-05-10T00:00:00.000Z", type = 4),
                    TmdbMovieReleaseDateItem(releaseDate = "2024-05-20T00:00:00.000Z", type = 5)
                ))
            ))
        ))

        val result = service(stub).fetchEnrichment("10", "movie", "hi-IN")!!

        assertEquals("Localized Film", result.localizedTitle)
        assertEquals(listOf("Drama"), result.genres)
        assertEquals(listOf(1, 2), result.genreIds)
        assertEquals("U/A 16+", result.ageRating)
        assertEquals("IN", result.ageRatingRegion)
        assertEquals("2024-05-10", result.digitalReleaseDate)
        assertEquals("IN", result.digitalReleaseRegion)
        assertEquals("2024-05-20", result.physicalReleaseDate)
        assertEquals("IN", result.physicalReleaseRegion)
        assertEquals("https://image.tmdb.org/t/p/w500/localized.png", result.logo)
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", result.poster)
        assertEquals("https://image.tmdb.org/t/p/w1280/backdrop.jpg", result.backdrop)
        assertEquals("Director", result.directorMembers.single().name)
        assertTrue("A movie with director credits should not duplicate them as writers", result.writerMembers.isEmpty())
        assertEquals("Actor", result.castMembers.single().name)
        assertEquals("IN", result.countries?.single())
        assertEquals(44, result.collectionId)
        assertEquals(listOf("getMovieDetails", "getMovieCredits", "getMovieImages", "getMovieReleaseDates").toSet(), stub.calls.toSet())
    }

    @Test fun playbackCastFetchUsesOnlyCreditsAndCachesTheResult() = runBlocking {
        val stub = ApiStub(mapOf(
            "getMovieCredits" to TmdbCreditsResponse(cast = listOf(
                TmdbCastMember(21, "Lead Actor", "Hero", "/lead.jpg"),
                TmdbCastMember(22, " ", "Ignored")
            ))
        ))
        val service = service(stub)

        val first = service.fetchCastMembers("10", "movie", "en")
        val second = service.fetchCastMembers("10", "movie", "en")

        assertEquals(first, second)
        assertEquals(listOf(TmdbCastInfo("Lead Actor", "Hero", "https://image.tmdb.org/t/p/w500/lead.jpg", 21)), first)
        assertEquals(listOf("getMovieCredits"), stub.calls.toList())
    }

    @Test fun fullDetailsDoNotReuseHomePayloadAndAreReusableAfterServiceRestart() = runBlocking {
        val store = object : CanonicalTitleStore {
            var row: CanonicalTitleEntity? = null
            override suspend fun getTitle(identity: ContentIdentity, locale: String) =
                row?.takeIf { it.identityKey == identity.stableKey && it.locale == locale }
            override suspend fun putTitle(title: CanonicalTitleEntity) { row = title }
        }
        val stub = ApiStub(mapOf(
            "getMovieDetails" to TmdbDetailsResponse(91, title = "Cached detail", overview = "Overview"),
            "getMovieCredits" to TmdbCreditsResponse(cast = listOf(TmdbCastMember(5, "Actor", "Lead"))),
            "getMovieImages" to TmdbImagesResponse(),
            "getMovieReleaseDates" to TmdbMovieReleaseDatesResponse(emptyList())
        ))
        val metadata = TmdbMetadataService(stub.api, TmdbService(RuntimeEnvironment.getApplication(), stub.api), store)

        metadata.fetchHomeEnrichment("91", "movie")
        val details = metadata.fetchEnrichment("91", "movie")

        assertEquals("Actor", details?.castMembers?.single()?.name)
        assertEquals(2, stub.calls.count { it == "getMovieDetails" })
        assertTrue(stub.calls.containsAll(listOf("getMovieCredits", "getMovieImages", "getMovieReleaseDates")))

        val restartedStub = ApiStub(emptyMap())
        val restarted = TmdbMetadataService(
            restartedStub.api,
            TmdbService(RuntimeEnvironment.getApplication(), restartedStub.api),
            store
        )
        assertEquals(details, restarted.fetchEnrichment("91", "movie"))
        assertTrue("fresh durable detail hit must not make network requests", restartedStub.calls.isEmpty())
    }

    @Test fun fullDetailsRefetchOlderCacheRowsWithoutReleaseAvailabilityMarker() = runBlocking {
        val identity = requireNotNull(ContentIdentity.tmdb(ContentKind.MOVIE, 92))
        val now = System.currentTimeMillis()
        val store = object : CanonicalTitleStore {
            var row: CanonicalTitleEntity? = CanonicalTitleEntity(
                identityKey = identity.stableKey,
                locale = "en",
                kind = "movie",
                lastFetchedAtMillis = now,
                creditsJson = "[]",
                creditsSource = "TMDB",
                creditsObservedAtMillis = now
            )
            override suspend fun getTitle(identity: ContentIdentity, locale: String) = row
            override suspend fun putTitle(title: CanonicalTitleEntity) { row = title }
        }
        val stub = ApiStub(mapOf(
            "getMovieDetails" to TmdbDetailsResponse(92, title = "Updated detail"),
            "getMovieCredits" to TmdbCreditsResponse(),
            "getMovieImages" to TmdbImagesResponse(),
            "getMovieReleaseDates" to TmdbMovieReleaseDatesResponse(emptyList())
        ))

        val result = TmdbMetadataService(
            stub.api,
            TmdbService(RuntimeEnvironment.getApplication(), stub.api),
            store
        ).fetchEnrichment("92", "movie")

        assertEquals("Updated detail", result?.localizedTitle)
        assertTrue("pre-release-metadata cache rows must be refreshed after upgrade", stub.calls.isNotEmpty())
    }

    @Test fun movieWithoutDirectorFallsBackToWriterCredits() = runBlocking {
        val stub = ApiStub(mapOf(
            "getMovieDetails" to TmdbDetailsResponse(10, title = "Film", overview = "Overview"),
            "getMovieCredits" to TmdbCreditsResponse(crew = listOf(
                TmdbCrewMember(21, "Screenwriter", "Screenplay")
            )),
            "getMovieImages" to TmdbImagesResponse(),
            "getMovieReleaseDates" to TmdbMovieReleaseDatesResponse(emptyList())
        ))

        val result = service(stub).fetchEnrichment("10", "movie")!!

        assertTrue(result.directorMembers.isEmpty())
        assertEquals("Screenwriter", result.writerMembers.single().name)
    }

    @Test fun movieReleaseWindowSelectionIgnoresInvalidDatesAndDoesNotInventMissingReleaseTypes() = runBlocking {
        val stub = ApiStub(mapOf(
            "getMovieDetails" to TmdbDetailsResponse(11, title = "Film"),
            "getMovieCredits" to TmdbCreditsResponse(),
            "getMovieImages" to TmdbImagesResponse(),
            "getMovieReleaseDates" to TmdbMovieReleaseDatesResponse(listOf(
                TmdbMovieReleaseDateCountry("IN", listOf(
                    TmdbMovieReleaseDateItem(releaseDate = "", type = 4),
                    TmdbMovieReleaseDateItem(releaseDate = "2024-02-30T00:00:00.000Z", type = 4),
                    TmdbMovieReleaseDateItem(releaseDate = "2024-05-10T00:00:00.000Z", type = 3)
                ))
            ))
        ))

        val result = service(stub).fetchEnrichment("11", "movie", "hi-IN")!!

        assertNull(result.digitalReleaseDate)
        assertNull(result.digitalReleaseRegion)
        assertNull(result.physicalReleaseDate)
        assertNull(result.physicalReleaseRegion)
    }

    @Test fun homeCardEnrichmentUsesOneEndpointAndCachesTheLightweightResult() = runBlocking {
        val stub = ApiStub(mapOf(
            "getMovieDetails" to TmdbDetailsResponse(
                33, title = "Home Title", overview = "Home synopsis",
                genres = listOf(TmdbGenre(18, "Drama")), backdropPath = "/wide.jpg",
                originalLanguage = "ml", runtime = 101
            )
        ))
        val service = service(stub)

        val first = service.fetchHomeEnrichment("33", "movie", "ml")
        val cached = service.fetchHomeEnrichment("33", "movie", "ml")

        assertEquals(first, cached)
        assertEquals("Home Title", first?.localizedTitle)
        assertEquals(listOf(18), first?.genreIds)
        assertEquals("https://image.tmdb.org/t/p/w1280/wide.jpg", first?.backdrop)
        assertEquals(listOf("getMovieDetails"), stub.calls.toList())
    }

    @Test fun landscapeHomeEnrichmentFetchesAndCachesTheTitleLogo() = runBlocking {
        val stub = ApiStub(mapOf(
            "getMovieDetails" to TmdbDetailsResponse(34, title = "Landscape Title", backdropPath = "/wide.jpg"),
            "getMovieImages" to TmdbImagesResponse(logos = listOf(
                TmdbImage("/logo.png", "en", null)
            ))
        ))
        val service = service(stub)

        val first = service.fetchHomeEnrichment("34", "movie", "en", includeLogo = true)
        val cached = service.fetchHomeEnrichment("34", "movie", "en", includeLogo = true)

        assertEquals("https://image.tmdb.org/t/p/w500/logo.png", first?.logo)
        assertEquals(first, cached)
        assertEquals(1, stub.calls.count { it == "getMovieDetails" })
        assertEquals(1, stub.calls.count { it == "getMovieImages" })
    }

    @Test fun concurrentHomeAndDetailsConsumersShareTheSameTmdbRead() = runBlocking {
        val requests = AtomicInteger()
        val api = Proxy.newProxyInstance(
            TmdbApiService::class.java.classLoader,
            arrayOf(TmdbApiService::class.java)
        ) { _, method, _ ->
            if (method.name != "getMovieDetails") error("Unexpected TMDB request: ${method.name}")
            requests.incrementAndGet()
            // Keep the first request in flight long enough for the second screen to join it.
            Thread.sleep(100)
            Response.success(200, TmdbDetailsResponse(90, title = "Shared title", overview = "Synopsis"))
        } as TmdbApiService
        val metadata = TmdbMetadataService(api, TmdbService(RuntimeEnvironment.getApplication(), api))

        val results = coroutineScope {
            listOf(
                async(Dispatchers.Default) { metadata.fetchHomeEnrichment("90", "movie") },
                async(Dispatchers.Default) { metadata.fetchHomeEnrichment("90", "movie") }
            ).awaitAll()
        }

        assertEquals(listOf("Shared title", "Shared title"), results.map { it?.localizedTitle })
        assertEquals("same title/language requests should share an in-flight fetch", 1, requests.get())
    }

    @Test fun metadataEnrichmentPropagatesCancellationInsteadOfConvertingItToEmptyData() = runBlocking {
        val api = Proxy.newProxyInstance(
            TmdbApiService::class.java.classLoader,
            arrayOf(TmdbApiService::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getMovieDetails" -> throw CancellationException("navigation cancelled")
                "getMovieCredits" -> Response.success(200, TmdbCreditsResponse())
                "getMovieImages" -> Response.success(200, TmdbImagesResponse())
                "getMovieReleaseDates" -> Response.success(200, TmdbMovieReleaseDatesResponse(emptyList()))
                else -> Response.success<TmdbFindResponse>(200, TmdbFindResponse())
            }
        } as TmdbApiService
        val service = TmdbMetadataService(api, TmdbService(RuntimeEnvironment.getApplication(), api))

        try {
            service.fetchEnrichment("10", "movie")
            fail("Cancellation must propagate to the caller")
        } catch (_: CancellationException) {
            // Expected: the caller can stop obsolete metadata work on navigation.
        }
    }

    @Test fun imdbLookupContainsTlsSocketFailuresAsRecoverableNetworkErrors() = runBlocking {
        val api = Proxy.newProxyInstance(
            TmdbApiService::class.java.classLoader,
            arrayOf(TmdbApiService::class.java)
        ) { _, method, _ ->
            if (method.name == "findByExternalId") throw SocketException("TLS handshake reset")
            error("Unexpected TMDB request: ${method.name}")
        } as TmdbApiService
        val tmdbService = TmdbService(RuntimeEnvironment.getApplication(), api)

        assertNull(tmdbService.imdbToTmdb("tt1234567", "movie"))
    }

    @Test fun episodeEnrichmentDropsMalformedEntriesDeduplicatesSeasonsAndCachesResults() = runBlocking {
        val stub = ApiStub(mapOf(
            "getTvSeasonDetails" to TmdbSeasonResponse(episodes = listOf(
                TmdbEpisode(1, "Pilot", "First episode", "/still.jpg", "2024-01-02", 48),
                TmdbEpisode(null, "Invalid"),
                TmdbEpisode(2, "", "", "", "", null)
            ))
        ))
        val service = service(stub)

        val result = service.fetchEpisodeEnrichment("42", listOf(1, 1, 2))
        val cached = service.fetchEpisodeEnrichment("42", listOf(1, 1, 2))

        assertEquals(4, result.size)
        assertEquals("Pilot", result[1 to 1]?.title)
        assertEquals("https://image.tmdb.org/t/p/w500/still.jpg", result[1 to 1]?.thumbnail)
        assertNull(result[1 to 2]?.title)
        assertEquals(result, cached)
        assertEquals(2, stub.calls.count { it == "getTvSeasonDetails" })
        assertTrue(service.fetchEpisodeEnrichment("not-numeric", listOf(1)).isEmpty())
    }

    @Test fun seasonMetadataLoadsInParallelButNeverExceedsThreeRequests() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val api = Proxy.newProxyInstance(
            TmdbApiService::class.java.classLoader,
            arrayOf(TmdbApiService::class.java)
        ) { _, method, _ ->
            if (method.name == "getTvSeasonDetails") {
                val now = active.incrementAndGet()
                peak.updateAndGet { previous -> maxOf(previous, now) }
                try {
                    Thread.sleep(40)
                    Response.success(200, TmdbSeasonResponse(episodes = listOf(
                        TmdbEpisode(1, "Season episode")
                    )))
                } finally {
                    active.decrementAndGet()
                }
            } else {
                Response.success(200, TmdbSeasonResponse())
            }
        } as TmdbApiService
        val service = TmdbMetadataService(api, TmdbService(RuntimeEnvironment.getApplication(), api))

        val episodes = service.fetchEpisodeEnrichment("42", (1..10).toList())

        assertEquals(10, episodes.size)
        assertTrue("Season detail calls should overlap", peak.get() > 1)
        assertTrue("TMDB season requests must stay bounded", peak.get() <= 3)
    }

    @Test fun recommendationsApplyRelevanceOrderingAndCacheWhileCollectionsSortByDate() = runBlocking {
        val stub = ApiStub(mapOf(
            "getMovieRecommendations" to TmdbRecommendationsResponse(listOf(
                TmdbRecommendationResult(1, title = "Low Signal", originalLanguage = "fr", voteAverage = 2.0, voteCount = 1),
                TmdbRecommendationResult(2, title = "English Match", originalLanguage = "en", voteAverage = 4.0, voteCount = 2),
                TmdbRecommendationResult(3, title = "Popular", originalLanguage = "fr", voteAverage = 8.0, voteCount = 100)
            )),
            "getCollectionDetails" to TmdbCollectionResponse(9, parts = listOf(
                TmdbCollectionPart(3, "Later", releaseDate = "2022-01-01"),
                TmdbCollectionPart(2, "Earlier", releaseDate = "2001-01-01"),
                TmdbCollectionPart(4, null, releaseDate = "1990-01-01")
            ))
        ))
        val service = service(stub)

        val recommendations = service.fetchRecommendations("2", "movie", language = "en_us", maxItems = 2)
        assertEquals(listOf("English Match", "Popular"), recommendations.map { it.title })
        assertEquals(recommendations, service.fetchRecommendations("2", "movie", language = "en-US", maxItems = 2))
        assertEquals(listOf("Earlier", "Later"), service.fetchCollection(9).map { it.title })
        assertEquals(1, stub.calls.count { it == "getMovieRecommendations" })
        assertEquals(1, stub.calls.count { it == "getCollectionDetails" })
    }

    @Test fun recommendationAndIntentPagesExposeMoreResultsAndRequestTheRequestedPage() = runBlocking {
        val recommendationStub = ApiStub(mapOf(
            "getMovieRecommendations" to TmdbRecommendationsResponse(
                results = listOf(TmdbRecommendationResult(31, title = "Recommendation page two")),
                totalPages = 3
            )
        ))
        val recommendationPage = service(recommendationStub).fetchRecommendationsPage(
            "2", "movie", page = 2, maxItems = 20
        )

        assertEquals(2, recommendationPage.page)
        assertTrue(recommendationPage.hasMore)
        assertEquals("Recommendation page two", recommendationPage.items.single().title)
        assertEquals(2, recommendationStub.arguments.getValue("getMovieRecommendations")[3])

        val discoverStub = ApiStub(mapOf(
            "discoverMovies" to TmdbDiscoverResponse(
                page = 2,
                totalPages = 4,
                results = listOf(TmdbDiscoverResult(42, title = "Discovery page two", posterPath = "/42.jpg"))
            )
        ))
        val discoveryPage = service(discoverStub).discoverByIntentPage(
            TmdbNaturalQuery("movie", null, null, "popularity.desc"),
            page = 2,
            maxItems = 20
        )

        assertEquals(2, discoveryPage.page)
        assertTrue(discoveryPage.hasMore)
        assertEquals("Discovery page two", discoveryPage.items.single().title)
        assertEquals(2, discoverStub.arguments.getValue("discoverMovies")[2])
    }

    @Test fun intentDiscoverySurfacesUnsuccessfulHttpResponsesForSearchRecovery() = runBlocking {
        val api = Proxy.newProxyInstance(
            TmdbApiService::class.java.classLoader,
            arrayOf(TmdbApiService::class.java)
        ) { _, method, _ ->
            if (method.name != "discoverMovies") error("Unexpected TMDB request: ${method.name}")
            Response.error<TmdbDiscoverResponse>(503, "temporarily unavailable".toResponseBody())
        } as TmdbApiService
        val metadata = TmdbMetadataService(api, TmdbService(RuntimeEnvironment.getApplication(), api))

        val result = metadata.discoverByIntentPage(
            TmdbNaturalQuery("movie", null, null, "popularity.desc"),
            page = 1,
            maxItems = 15
        )

        assertTrue(result.failed)
        assertTrue(result.items.isEmpty())
    }

    @Test fun personDetailUsesCastCreditsAndSeparatesMovieAndTvFilmography() = runBlocking {
        val stub = ApiStub(mapOf(
            "getPersonDetails" to TmdbPersonResponse(5, "Performer", "Biography", "1990-01-01", profilePath = "/person.jpg", knownForDepartment = "Acting"),
            "getPersonCombinedCredits" to TmdbPersonCreditsResponse(cast = listOf(
                TmdbPersonCreditCast(1, title = "Film", mediaType = "movie", posterPath = "/film.jpg", releaseDate = "2020-01-01", voteAverage = 7.1),
                TmdbPersonCreditCast(2, name = "Show", mediaType = "tv", posterPath = "/show.jpg", firstAirDate = "2021-01-01", voteAverage = 8.2),
                TmdbPersonCreditCast(3, title = "No Poster", mediaType = "movie")
            ))
        ))

        val person = service(stub).fetchPersonDetail(5)!!

        assertEquals("Performer", person.name)
        assertEquals("Biography", person.biography)
        assertEquals("https://image.tmdb.org/t/p/w500/person.jpg", person.profilePhoto)
        assertEquals("Film", person.movieCredits.single().title)
        assertEquals("movie", person.movieCredits.single().kind.keyPart)
        assertEquals("Show", person.tvCredits.single().title)
        assertEquals("series", person.tvCredits.single().kind.keyPart)
    }

    @Test fun trailersPreferOfficialYoutubeAndDiscoverMapsPosterRequiredResults() = runBlocking {
        val stub = ApiStub(mapOf(
            "getMovieVideos" to TmdbVideosResponse(10, listOf(
                TmdbVideoResult(key = "not-youtube", site = "Vimeo", type = "Trailer", official = true),
                TmdbVideoResult(key = "unofficial", site = "YouTube", type = "Trailer", official = false, size = 2160),
                TmdbVideoResult(key = "official", site = "YouTube", type = "Trailer", official = true, size = 1080)
            )),
            "discoverMovies" to TmdbDiscoverResponse(totalPages = 4, results = listOf(
                TmdbDiscoverResult(1, title = "Has Poster", posterPath = "/poster.jpg", releaseDate = "2023-06-01"),
                TmdbDiscoverResult(2, title = "No Poster"),
                TmdbDiscoverResult(3, name = "Alternate Title", posterPath = "/alt.jpg")
            ))
        ))
        val service = service(stub)

        assertEquals("official", service.fetchBestTrailerKey("10", "movie")?.key)
        assertEquals("official", service.fetchVideos("10", "movie").first().key)
        val (discover, pages) = service.fetchDiscover(77, "company", "movie", "popularity.desc", page = 2)
        assertEquals(4, pages)
        assertEquals(listOf("Has Poster", "Alternate Title"), discover.map { it.title })
        assertEquals("2023", discover.first().year)
        assertNull(service.fetchBestTrailerKey("bad-id", "movie"))
    }

    @Test fun personalizedDiscoveryUsesMediaSpecificDateSortsAndDateWindows() = runBlocking {
        val response = TmdbDiscoverResponse(results = listOf(
            TmdbDiscoverResult(1, title = "Movie", name = "Show", posterPath = "/poster.jpg")
        ))
        val stub = ApiStub(mapOf("discoverMovies" to response, "discoverTv" to response))
        val dateRange = TmdbNaturalQuery(
            mediaType = null,
            genreIds = "18",
            originalLanguage = "en",
            sortBy = "release_date.desc",
            tvGenreIds = "18",
            releaseDateGte = "2025-10-08",
            releaseDateLte = "2026-10-08"
        )

        service(stub).discoverByIntent(dateRange, maxItems = 10)

        val movieArgs = stub.arguments.getValue("discoverMovies")
        assertEquals("primary_release_date.desc", movieArgs[3])
        assertEquals("2026-10-08", movieArgs[5])
        assertEquals("2025-10-08", movieArgs[6])
        assertEquals("18", movieArgs[8])
        val tvArgs = stub.arguments.getValue("discoverTv")
        assertEquals("first_air_date.desc", tvArgs[3])
        assertEquals("2026-10-08", tvArgs[6])
        assertEquals("2025-10-08", tvArgs[7])
        assertEquals("18", tvArgs[9])
    }

    @Test fun mixedDiscoveryBalancesMoviesAndSeriesWhenOneTypeHasMoreResults() = runBlocking {
        val stub = ApiStub(mapOf(
            "discoverMovies" to TmdbDiscoverResponse(results = (1..5).map { id ->
                TmdbDiscoverResult(id, title = "Movie $id", posterPath = "/movie$id.jpg", popularity = 100.0 - id)
            }),
            "discoverTv" to TmdbDiscoverResponse(results = (11..12).map { id ->
                TmdbDiscoverResult(id, name = "Series $id", posterPath = "/series$id.jpg", popularity = 90.0 - id)
            })
        ))

        val mixed = service(stub).discoverByIntent(
            TmdbNaturalQuery(null, null, null, "popularity.desc"),
            maxItems = 4
        )
        val moviesOnly = service(stub).discoverByIntent(
            TmdbNaturalQuery("movie", null, null, "popularity.desc"),
            maxItems = 4
        )

        assertEquals(listOf("movie", "series", "movie", "series"), mixed.map { it.kind.keyPart })
        assertEquals(listOf("1", "11", "2", "12"), mixed.map { it.identity.externalId })
        assertTrue(moviesOnly.all { it.kind.keyPart == "movie" })
    }

    @Test fun entityDetailsDistinguishCompaniesAndNetworksAndDiscoverTvRoutesToNetwork() = runBlocking {
        val stub = ApiStub(mapOf(
            "getCompanyDetails" to TmdbCompanyDetailsResponse(8, "Studio", "Company bio", "London", logoPath = "/co.png", originCountry = "GB"),
            "getNetworkDetails" to TmdbNetworkDetailsResponse(9, "Network", "New York", logoPath = "/net.png", originCountry = "US"),
            "discoverTv" to TmdbDiscoverResponse(totalPages = 2, results = listOf(
                TmdbDiscoverResult(22, name = "Series", posterPath = "/series.jpg", firstAirDate = "2022-03-04")
            ))
        ))
        val service = service(stub)

        val company = service.fetchEntityDetail(8, "company")!!
        val network = service.fetchEntityDetail(9, "network")!!
        val (series, pages) = service.fetchDiscover(9, "network", "tv", "vote_average.desc", dateLte = "2025-01-01")

        assertEquals("Company bio", company.description)
        assertEquals("company", company.kind)
        assertNull(network.description)
        assertEquals("network", network.kind)
        assertEquals(listOf("Series"), series.map { it.title })
        assertEquals("series", series.single().kind.keyPart)
        assertEquals("2022", series.single().year)
        assertEquals(2, pages)
    }

    private fun service(stub: ApiStub): TmdbMetadataService {
        val context = RuntimeEnvironment.getApplication()
        val tmdbService = TmdbService(context, stub.api)
        return TmdbMetadataService(stub.api, tmdbService)
    }

    private class ApiStub(responses: Map<String, Any?>) {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val arguments = Collections.synchronizedMap(mutableMapOf<String, Array<out Any?>>())
        val api: TmdbApiService = Proxy.newProxyInstance(
            TmdbApiService::class.java.classLoader,
            arrayOf(TmdbApiService::class.java)
        ) { _, method, args ->
            calls += method.name
            if (args != null) arguments[method.name] = args
            @Suppress("UNCHECKED_CAST")
            Response.success(200, responses[method.name])
        } as TmdbApiService
    }
}
