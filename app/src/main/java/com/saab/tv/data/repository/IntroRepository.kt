package com.saab.tv.data.repository

import com.saab.tv.data.model.introdb.IntroDbSegmentsResponse
import com.saab.tv.data.remote.IntroDbService
import com.saab.tv.data.cache.BoundedExpiringCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IntroRepository @Inject constructor(
    private val api: IntroDbService
) {
    private val cache = BoundedExpiringCache<String, IntroDbSegmentsResponse>(
        capacity = 256,
        ttlMillis = 6 * 60 * 60 * 1_000L,
        nowMillis = System::currentTimeMillis
    )

    suspend fun getSegments(
        imdbId: String,
        season: Int,
        episode: Int
    ): IntroDbSegmentsResponse? = withContext(Dispatchers.IO) {
        val cacheKey = "$imdbId:$season:$episode"
        cache[cacheKey]?.let { return@withContext it }

        try {
            val url = "$BASE_URL/segments?imdb_id=$imdbId&season=$season&episode=$episode"
            val response = withTimeout(TIMEOUT_MS) { api.getSegments(url) }
            cache[cacheKey] = response
            response
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val BASE_URL = "https://api.introdb.app"
        private const val TIMEOUT_MS = 5_000L
    }
}
