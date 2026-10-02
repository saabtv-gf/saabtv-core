package com.saab.tv.data.repository

import com.saab.tv.data.model.introdb.IntroDbSegment
import com.saab.tv.data.model.introdb.IntroDbSegmentsResponse
import com.saab.tv.data.remote.IntroDbService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Collections

class IntroRepositoryTest {
    private class FakeApi : IntroDbService {
        val urls = Collections.synchronizedList(mutableListOf<String>())
        var failure: Exception? = null
        var response = IntroDbSegmentsResponse(IntroDbSegment(1_000, 20_000, .9, 3))
        override suspend fun getSegments(url: String): IntroDbSegmentsResponse {
            urls += url
            failure?.let { throw it }
            return response
        }
    }

    @Test fun constructsCanonicalEpisodeUrlAndReturnsAllSegments() = runBlocking {
        val api = FakeApi().apply {
            response = IntroDbSegmentsResponse(
                IntroDbSegment(1_000, 20_000), IntroDbSegment(0, 1_000),
                IntroDbSegment(100_000, 110_000))
        }
        assertEquals(api.response, IntroRepository(api).getSegments("tt1234567", 2, 3))
        assertEquals(listOf("https://api.introdb.app/segments?imdb_id=tt1234567&season=2&episode=3"), api.urls)
    }

    @Test fun repeatedEpisodeUsesCachedResponseWithoutAnotherNetworkCall() = runBlocking {
        val api = FakeApi()
        val repository = IntroRepository(api)
        val first = repository.getSegments("tt1", 1, 1)
        api.failure = IOException("network unavailable")
        assertSame(first, repository.getSegments("tt1", 1, 1))
        assertEquals(1, api.urls.size)
    }

    @Test fun cacheKeysIsolateTitleSeasonAndEpisode() = runBlocking {
        val api = FakeApi()
        val repository = IntroRepository(api)
        listOf(Triple("tt1", 1, 1), Triple("tt1", 1, 2), Triple("tt1", 2, 1), Triple("tt2", 1, 1))
            .forEach { (id, season, episode) -> repository.getSegments(id, season, episode) }
        assertEquals(4, api.urls.distinct().size)
    }

    @Test fun emptySegmentsAreCachedAsAValidResponse() = runBlocking {
        val api = FakeApi().apply { response = IntroDbSegmentsResponse() }
        val repository = IntroRepository(api)
        repeat(2) { assertEquals(IntroDbSegmentsResponse(), repository.getSegments("tt1", 1, 1)) }
        assertEquals(1, api.urls.size)
    }

    @Test fun failedRequestDoesNotPoisonCacheAndCanBeRetried() = runBlocking {
        val api = FakeApi().apply { failure = IOException("disconnected") }
        val repository = IntroRepository(api)
        assertNull(repository.getSegments("tt1", 1, 1))
        api.failure = null
        assertEquals(api.response, repository.getSegments("tt1", 1, 1))
        assertEquals(2, api.urls.size)
    }

    @Test fun unexpectedProviderExceptionIsHandledAsMissingSegments() = runBlocking {
        val api = FakeApi().apply { failure = IllegalArgumentException("invalid provider response") }
        assertNull(IntroRepository(api).getSegments("tt1", 1, 1))
    }
}
