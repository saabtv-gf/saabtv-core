package com.saab.tv.data.stream

import com.google.gson.JsonParser
import com.saab.tv.data.model.stremio.Stream
import org.junit.Assert.*
import org.junit.Test

class TorBoxAvailabilityPolicyTest {
    private val hash = "a".repeat(40)
    @Test fun cacheMissRequiresSuccessfulValidResponse() {
        assertEquals(false, TorBoxAvailabilityPolicy.cached(JsonParser.parseString("""{"success":true,"data":{}}""").asJsonObject, hash))
        assertNull(TorBoxAvailabilityPolicy.cached(JsonParser.parseString("""{"success":false,"data":{}}""").asJsonObject, hash))
        assertNull(TorBoxAvailabilityPolicy.cached(JsonParser.parseString("""{"success":true,"data":{"message":"invalid"}}""").asJsonObject, hash))
    }
    @Test fun malformedCacheEntryStaysUnknown() {
        assertNull(TorBoxAvailabilityPolicy.cached(JsonParser.parseString("""{"success":true,"data":{"$hash":null}}""").asJsonObject, hash))
    }
    @Test fun validCacheEntryIsConfirmed() {
        assertEquals(true, TorBoxAvailabilityPolicy.cached(JsonParser.parseString("""{"success":true,"data":{"$hash":{"hash":"$hash"}}}""").asJsonObject, hash))
    }
    @Test fun structuredHashIsNormalized() { assertEquals(hash, TorBoxAvailabilityPolicy.hash(Stream(infoHash = hash.uppercase()))) }
    @Test fun magnetHashIsRead() { assertEquals(hash, TorBoxAvailabilityPolicy.hash(Stream(url = "magnet:?xt=urn:btih:$hash"))) }
    @Test fun addonDebridPathRetainsHash() { assertEquals(hash, TorBoxAvailabilityPolicy.hash(Stream(url = "https://torrentio.strem.fun/torbox/token/$hash/0/movie.mkv", addonTransportUrl = "https://torrentio.strem.fun"))) }
    @Test fun arbitraryCdnPathIsNotAssumedToBeTorrentHash() { assertNull(TorBoxAvailabilityPolicy.hash(Stream(url = "https://example.com/$hash"))) }
    @Test fun onlyConfirmedUncachedZeroIsRemoved() {
        assertTrue(TorBoxAvailabilityPolicy.remove(Stream(torBoxCached = false, torBoxSeeders = 0)))
        assertFalse(TorBoxAvailabilityPolicy.remove(Stream(torBoxCached = true, torBoxSeeders = 0)))
        assertFalse(TorBoxAvailabilityPolicy.remove(Stream(torBoxCached = null, torBoxSeeders = 0)))
        assertFalse(TorBoxAvailabilityPolicy.remove(Stream(torBoxCached = false, torBoxSeeders = null)))
    }
    @Test fun trackerResponseMustMatchRequestedHash() {
        val response = JsonParser.parseString("""{"success":true,"data":{"hash":"$hash","seeds":0}}""").asJsonObject
        assertEquals(0, TorBoxAvailabilityPolicy.seeds(response, hash))
        assertNull(TorBoxAvailabilityPolicy.seeds(response, "b".repeat(40)))
    }
    @Test fun failedResponseNeverMeansZero() {
        assertNull(TorBoxAvailabilityPolicy.seeds(JsonParser.parseString("""{"success":false,"data":{"hash":"$hash","seeds":0}}""").asJsonObject, hash))
    }
    @Test fun checkedUnknownDoesNotFallBackToScrapedSeederCount() {
        val stream = Stream(title = "Movie 1080p 👤 900", seeders = 900, torBoxChecked = true)
        assertNull(StreamParser.parse(stream).seeds)
    }
    @Test fun cachedWinsInsideResolutionTier() {
        val cached = Stream(title = "Movie 1080p", torBoxChecked = true, torBoxCached = true, torBoxSeeders = 0)
        val uncached = cached.copy(torBoxCached = false, torBoxSeeders = 10000)
        assertTrue(StreamScoreCalculator.score(cached) > StreamScoreCalculator.score(uncached))
    }
    @Test fun cacheBoostDoesNotOverrideResolution() {
        val cached = Stream(title = "Movie 720p", torBoxChecked = true, torBoxCached = true)
        val higher = Stream(title = "Movie 4k", torBoxChecked = true, torBoxCached = false, torBoxSeeders = 1)
        assertTrue(StreamScoreCalculator.score(higher) > StreamScoreCalculator.score(cached))
    }
}
