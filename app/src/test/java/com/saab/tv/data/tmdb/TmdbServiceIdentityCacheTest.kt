package com.saab.tv.data.tmdb

import android.app.Application
import com.saab.tv.data.remote.TmdbApiService
import com.saab.tv.data.model.tmdb.TmdbFindResponse
import com.saab.tv.data.model.tmdb.TmdbFindResult
import com.saab.tv.data.model.tmdb.TmdbExternalIdsResponse
import com.saab.tv.data.local.CanonicalMetadataDao
import com.saab.tv.data.repository.CanonicalMetadataRepository
import java.io.IOException
import kotlinx.coroutines.CancellationException
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
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TmdbServiceIdentityCacheTest {
    @Test
    fun sameNumericIdAndImdbAliasRemainSeparateForMovieAndSeries() = runBlocking {
        val api = noResponseApi()
        val service = TmdbService(RuntimeEnvironment.getApplication(), api)
        service.preCacheMapping("tt1234567", 42, "movie")
        service.preCacheMapping("tt7654321", 42, "series")

        assertEquals("tt1234567", service.tmdbToImdb(42, "movie"))
        assertEquals("tt7654321", service.tmdbToImdb(42, "tv"))
        assertEquals(42, service.imdbToTmdb("tt1234567", "film"))
        assertEquals(42, service.imdbToTmdb("tt7654321", "show"))
        assertTrue("cached aliases should avoid external lookups", calls.isEmpty())
    }

    @Test
    fun ensureTmdbIdHandlesSupportedPrefixesEpisodeSuffixesAndInvalidValues() = runBlocking {
        val service = TmdbService(RuntimeEnvironment.getApplication(), noResponseApi())
        service.preCacheMapping("tt1234567", 987, "movie")

        assertEquals("987", service.ensureTmdbId("movie:tt1234567:1:3", "movie"))
        assertEquals("987", service.ensureTmdbId("tmdb:movie:987", "movie"))
        assertEquals("123", service.ensureTmdbId("series:123:2:1", "series"))
        assertNull(service.ensureTmdbId("", "movie"))
        assertNull(service.ensureTmdbId("ttinvalid", "movie"))
        assertNull(service.ensureTmdbId("123", "person"))
        assertNull(service.ensureTmdbId("not-an-id", "movie"))
    }

    @Test
    fun malformedCacheMappingsAreRejectedAndCacheCanBeCleared() = runBlocking {
        val service = TmdbService(RuntimeEnvironment.getApplication(), noResponseApi())
        service.preCacheMapping("not-imdb", 0, "movie")
        assertNull(service.tmdbToImdb(0, "movie"))

        service.preCacheMapping("tt1234567", 42, "movie")
        assertEquals("tt1234567", service.tmdbToImdb(42, "movie"))
        service.clearCache()
        assertNull(service.tmdbToImdb(42, "movie"))
        assertEquals(listOf("getMovieExternalIds"), calls.toList())
    }

    @Test
    fun mediaTypeNormalizationKeepsUnknownLabelsButCanonicalizesAliases() {
        val service = TmdbService(RuntimeEnvironment.getApplication(), noResponseApi())
        assertEquals("movie", service.normalizeMediaType(" Film "))
        assertEquals("tv", service.normalizeMediaType("Series"))
        assertEquals("documentary", service.normalizeMediaType(" Documentary "))
    }

    @Test fun invalidRemoteIdsAreNotReturnedOrCached() = runBlocking {
        listOf(0, -1).forEach { invalid ->
            var requests = 0
            val api = Proxy.newProxyInstance(TmdbApiService::class.java.classLoader,
                arrayOf(TmdbApiService::class.java)) { _, _, _ ->
                requests++
                Response.success(TmdbFindResponse(movieResults = listOf(TmdbFindResult(invalid))))
            } as TmdbApiService
            val service = TmdbService(RuntimeEnvironment.getApplication(), api)
            repeat(2) { assertNull(service.imdbToTmdb("tt1234567", "movie")) }
            assertEquals(2, requests)
        }
    }

    @Test fun wrongTitleOrMalformedImdbResponseCannotPoisonAliasCache() = runBlocking {
        listOf(TmdbExternalIdsResponse(99, "tt1234567"), TmdbExternalIdsResponse(42, "wrong"),
            TmdbExternalIdsResponse(42, null)).forEach { invalid ->
            var requests = 0
            val api = Proxy.newProxyInstance(TmdbApiService::class.java.classLoader,
                arrayOf(TmdbApiService::class.java)) { _, _, _ ->
                requests++
                Response.success(invalid)
            } as TmdbApiService
            val service = TmdbService(RuntimeEnvironment.getApplication(), api)
            repeat(2) { assertNull(service.tmdbToImdb(42, "movie")) }
            assertEquals(2, requests)
        }
    }

    @Test fun validRemoteIdsAreNormalizedAndPopulateBothDirections() = runBlocking {
        var requests = 0
        val api = Proxy.newProxyInstance(TmdbApiService::class.java.classLoader,
            arrayOf(TmdbApiService::class.java)) { _, _, _ ->
            requests++
            Response.success(TmdbExternalIdsResponse(42, " tt1234567 "))
        } as TmdbApiService
        val service = TmdbService(RuntimeEnvironment.getApplication(), api)
        assertEquals("tt1234567", service.tmdbToImdb(42, "movie"))
        assertEquals(42, service.imdbToTmdb("tt1234567", "movie"))
        assertEquals("tt1234567", service.tmdbToImdb(42, "movie"))
        assertEquals(1, requests)
    }

    @Test fun concurrentAliasResolutionsShareTheSameExternalIdRequest() = runBlocking {
        val requests = AtomicInteger()
        val api = Proxy.newProxyInstance(
            TmdbApiService::class.java.classLoader,
            arrayOf(TmdbApiService::class.java)
        ) { _, method, _ ->
            if (method.name != "findByExternalId") error("Unexpected TMDB request: ${method.name}")
            requests.incrementAndGet()
            Thread.sleep(100)
            Response.success(TmdbFindResponse(movieResults = listOf(TmdbFindResult(42))))
        } as TmdbApiService
        val service = TmdbService(RuntimeEnvironment.getApplication(), api)

        val ids = coroutineScope {
            listOf(
                async(Dispatchers.Default) { service.imdbToTmdb("tt1234567", "movie") },
                async(Dispatchers.Default) { service.imdbToTmdb("tt1234567", "film") }
            ).awaitAll()
        }

        assertEquals(listOf(42, 42), ids)
        assertEquals("media aliases for one canonical identity should coalesce", 1, requests.get())
    }

    @Test fun aliasCacheWriteFailureDoesNotDiscardKnownVerifiedMapping() = runBlocking {
        val dao = Proxy.newProxyInstance(CanonicalMetadataDao::class.java.classLoader,
            arrayOf(CanonicalMetadataDao::class.java)) { _, _, _ -> throw IOException("Unavailable cache") } as CanonicalMetadataDao
        val service = TmdbService(RuntimeEnvironment.getApplication(), noResponseApi(), CanonicalMetadataRepository(dao))
        service.preCacheMapping("tt1234567", 42, "movie")
        assertEquals("tt1234567", service.tmdbToImdb(42, "movie"))
        assertEquals(42, service.imdbToTmdb("tt1234567", "movie"))
        assertTrue(calls.isEmpty())
    }

    @Test fun aliasCacheCancellationPropagatesInsteadOfBeingLoggedAsFailure() = runBlocking {
        val dao = Proxy.newProxyInstance(CanonicalMetadataDao::class.java.classLoader,
            arrayOf(CanonicalMetadataDao::class.java)) { _, _, _ -> throw CancellationException("Obsolete") } as CanonicalMetadataDao
        val service = TmdbService(RuntimeEnvironment.getApplication(), noResponseApi(), CanonicalMetadataRepository(dao))
        try {
            service.preCacheMapping("tt1234567", 42, "movie")
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertTrue(calls.isEmpty())
    }

    private val calls = mutableListOf<String>()

    private fun noResponseApi(): TmdbApiService = Proxy.newProxyInstance(
        TmdbApiService::class.java.classLoader,
        arrayOf(TmdbApiService::class.java)
    ) { _, method, _ ->
        calls += method.name
        Response.error<Any>(404, "not found".toResponseBody())
    } as TmdbApiService
}
