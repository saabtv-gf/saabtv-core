package com.saab.tv.data.tmdb

import android.app.Application
import com.saab.tv.data.model.CanonicalTitleEntity
import com.saab.tv.data.model.tmdb.*
import com.saab.tv.data.remote.TmdbApiService
import com.saab.tv.data.repository.CanonicalTitleStore
import com.saab.tv.domain.schema.ContentIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.Response
import java.lang.reflect.Proxy
import java.io.IOException
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TmdbHomePersistentCacheTest {
    @Test fun homeArtworkAndMetadataAreReusedAcrossServiceInstancesWithoutRequests() = runBlocking {
        val store = Store()
        val api = Api()
        val first = api.service(store).fetchHomeEnrichment("33", "movie", "te-IN", true)
        val second = api.service(store).fetchHomeEnrichment("033", "film", "te-IN", true)
        assertEquals(first, second)
        assertEquals("Movie", second?.localizedTitle)
        assertEquals(listOf(18), second?.genreIds)
        assertEquals("https://image.tmdb.org/t/p/w500/logo.png", second?.logo)
        assertEquals(1, api.calls.count { it == "getMovieDetails" })
        assertEquals(1, api.calls.count { it == "getMovieImages" })
        assertEquals("TMDB", store.rows.values.single().titleSource)
    }

    @Test fun mediaKindAndLocaleNeverShareAnUnqualifiedNumericCacheKey() = runBlocking {
        val store = Store()
        val api = Api()
        api.service(store).fetchHomeEnrichment("33", "movie", "en")
        val series = api.service(store).fetchHomeEnrichment("33", "TV", "en")
        api.service(store).fetchHomeEnrichment("33", "movie", "te")
        assertEquals("Series", series?.localizedTitle)
        assertEquals(3, store.rows.size)
        assertEquals(2, api.calls.count { it == "getMovieDetails" })
        assertEquals(1, api.calls.count { it == "getTvDetails" })
    }

    @Test fun landscapeRequestUpgradesBasicCacheButMissingLogoDoesNotCauseRepeatedRequests() = runBlocking {
        val store = Store()
        val api = Api(withLogo = false)
        api.service(store).fetchHomeEnrichment("33", "movie")
        assertEquals(0L, store.rows.values.single().logoObservedAtMillis)
        api.service(store).fetchHomeEnrichment("33", "movie", includeLogo = true)
        assertTrue(store.rows.values.single().logoObservedAtMillis > 0)
        assertNull(api.service(store).fetchHomeEnrichment("33", "movie", includeLogo = true)?.logo)
        assertEquals(2, api.calls.count { it == "getMovieDetails" })
        assertEquals(1, api.calls.count { it == "getMovieImages" })
    }

    @Test fun staleFutureCorruptAndWrongKindRowsAreRefetched() = runBlocking {
        val store = Store()
        val api = Api()
        api.service(store).fetchHomeEnrichment("33", "movie")
        val good = store.rows.values.single()
        listOf(
            good.copy(lastFetchedAtMillis = 1),
            good.copy(lastFetchedAtMillis = System.currentTimeMillis() + 60_000),
            good.copy(genresJson = "not json"),
            good.copy(kind = "series")
        ).forEach { invalid ->
            store.putTitle(invalid)
            assertEquals("Movie", api.service(store).fetchHomeEnrichment("33", "movie")?.localizedTitle)
        }
        assertEquals(5, api.calls.count { it == "getMovieDetails" })
    }

    @Test fun readAndWriteFailuresNeverDiscardSuccessfulNetworkMetadata() = runBlocking {
        val store = Store().apply { readFailure = IOException("read"); writeFailure = IOException("full") }
        val api = Api()
        val service = api.service(store)
        assertEquals("Movie", service.fetchHomeEnrichment("33", "movie")?.localizedTitle)
        assertEquals("Movie", service.fetchHomeEnrichment("33", "movie")?.localizedTitle)
        assertEquals(1, api.calls.count { it == "getMovieDetails" })
    }

    @Test fun cancelledCacheWorkPropagatesToCaller() = runBlocking {
        val api = Api()
        listOf(Store().apply { readFailure = CancellationException("obsolete read") },
            Store().apply { writeFailure = CancellationException("obsolete write") }).forEach { store ->
            try {
                api.service(store).fetchHomeEnrichment("33", "movie")
                fail("Cancellation must propagate")
            } catch (_: CancellationException) { }
        }
        assertEquals(1, api.calls.count { it == "getMovieDetails" })
    }

    @Test fun invalidIdsOrKindsNeverReachNetworkOrStorage() = runBlocking {
        val api = Api()
        val store = Store().apply { readFailure = AssertionError("Invalid lookup") }
        listOf("0" to "movie", "-1" to "movie", "bad" to "movie", "2147483648" to "movie",
            "33" to "unknown", "33" to "person", "33" to "episode").forEach { (id, type) ->
            assertNull(api.service(store).fetchHomeEnrichment(id, type))
        }
        assertTrue(api.calls.isEmpty())
    }

    @Test fun wrongTitleResponseNeverPollutesCanonicalCache() = runBlocking {
        val store = Store()
        val api = Api(detailsId = 99)
        assertNull(api.service(store).fetchHomeEnrichment("33", "movie"))
        assertTrue(store.rows.isEmpty())
    }

    @Test fun fullDetailsStillFetchCreditsInsteadOfReturningTheCardProjection() = runBlocking {
        val api = Api()
        val store = Store()
        api.service(store).fetchHomeEnrichment("33", "movie")
        val full = api.service(store).fetchEnrichment("33", "movie")
        assertEquals("Actor", full?.castMembers?.single()?.name)
        assertTrue(api.calls.contains("getMovieCredits"))
    }

    @Test fun fullDetailsPersistCommonFactsForOtherSurfacesWithoutCachingCreditsAsTitleFields() = runBlocking {
        val store = Store()
        val api = Api()
        val full = api.service(store).fetchEnrichment("33", "movie", "te-IN")
        val persisted = store.rows.values.single()

        assertEquals("Movie", full?.localizedTitle)
        assertEquals("tmdb/movie//33", persisted.identityKey)
        assertEquals("te-in", persisted.locale)
        assertEquals("Movie", persisted.title)
        assertEquals("Synopsis", persisted.synopsis)
        assertEquals("https://image.tmdb.org/t/p/w500/logo.png", persisted.logo)
        assertEquals("TMDB", persisted.ratingSource)
        assertEquals("0-10", persisted.ratingScale)

        val requestsBeforeWarmRead = api.calls.size
        val home = api.service(store).fetchHomeEnrichment("33", "movie", "te-IN", includeLogo = true)
        assertEquals("Movie", home?.localizedTitle)
        assertEquals(requestsBeforeWarmRead, api.calls.size)
    }

    @Test fun fullDetailsRejectUnsupportedIdentityAndMismatchedRemoteTitle() = runBlocking {
        val invalidKindApi = Api()
        assertNull(invalidKindApi.service(Store()).fetchEnrichment("33", "person"))
        assertTrue(invalidKindApi.calls.isEmpty())

        val store = Store()
        val mismatchedApi = Api(detailsId = 99)
        assertNull(mismatchedApi.service(store).fetchEnrichment("33", "movie"))
        assertTrue("A mismatched response must not become canonical metadata", store.rows.isEmpty())
    }

    private class Store : CanonicalTitleStore {
        val rows = mutableMapOf<Pair<String, String>, CanonicalTitleEntity>()
        var readFailure: Throwable? = null
        var writeFailure: Throwable? = null
        override suspend fun getTitle(identity: ContentIdentity, locale: String): CanonicalTitleEntity? {
            readFailure?.let { throw it }
            return rows[identity.stableKey to locale.lowercase()]
        }
        override suspend fun putTitle(title: CanonicalTitleEntity) {
            writeFailure?.let { throw it }
            rows[title.identityKey to title.locale] = title
        }
    }

    private class Api(withLogo: Boolean = true, detailsId: Int = 33) {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        private val api = Proxy.newProxyInstance(TmdbApiService::class.java.classLoader,
            arrayOf(TmdbApiService::class.java)) { _, method, _ ->
            calls += method.name
            val response: Any? = when (method.name) {
                "getMovieDetails", "getTvDetails" -> TmdbDetailsResponse(
                    id = detailsId, title = if (method.name == "getMovieDetails") "Movie" else "Series",
                    genres = listOf(TmdbGenre(18, "Drama")), overview = "Synopsis", posterPath = "/poster.jpg",
                    backdropPath = "/wide.jpg", runtime = 42, releaseDate = "2024-01-02", voteAverage = 7.8,
                    status = "Released", originCountry = listOf("IN"), originalLanguage = "te",
                    belongsToCollection = TmdbCollectionSummary(8, "Collection")
                )
                "getMovieImages", "getTvImages" -> TmdbImagesResponse(logos =
                    if (withLogo) listOf(TmdbImage("/logo.png", "en", null)) else emptyList())
                "getMovieCredits" -> TmdbCreditsResponse(cast = listOf(TmdbCastMember(9, "Actor")))
                "getMovieReleaseDates" -> TmdbMovieReleaseDatesResponse(emptyList())
                else -> error("Unexpected request: ${method.name}")
            }
            Response.success(200, response)
        } as TmdbApiService
        fun service(store: CanonicalTitleStore) = TmdbMetadataService(api,
            TmdbService(RuntimeEnvironment.getApplication(), api), store)
    }
}
