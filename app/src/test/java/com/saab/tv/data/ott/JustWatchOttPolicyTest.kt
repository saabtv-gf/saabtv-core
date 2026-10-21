package com.saab.tv.data.ott

import com.google.gson.JsonParser
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JustWatchOttPolicyTest {
    @Test
    fun mediaKindsUseLiveJustWatchAndCinemetaTypes() {
        assertEquals("MOVIE", OttMediaKind.MOVIE.justWatchType)
        assertEquals("movie", OttMediaKind.MOVIE.cinemetaType)
        assertEquals("SHOW", OttMediaKind.SERIES.justWatchType)
        assertEquals("series", OttMediaKind.SERIES.cinemetaType)
    }

    @Test
    fun newlyAddedSeriesKeepsParentShowIdentity() {
        val candidate = JustWatchCandidate(
            imdbId = "tt1234567",
            kind = OttMediaKind.SERIES,
            title = "Example Show",
            year = 2025,
            platforms = listOf("Netflix"),
            genres = setOf("drm"),
            ageCertification = "UA16+",
            description = null,
            addedDate = "2026-09-14"
        )

        assertTrue(candidate.imdbId.startsWith("tt"))
        assertEquals(OttMediaKind.SERIES, candidate.kind)
        assertEquals("Example Show", candidate.title)
    }

    @Test
    fun seasonResultUsesParentShowMetadataAndAllowsMissingAudioMetadata() {
        val edges = JsonParser.parseString(
            """
            [{
              "node": {
                "__typename": "Season",
                "content": { "genres": [{"shortName": "drm"}] },
                "show": { "content": {
                  "title": "Parent Show",
                  "originalReleaseYear": 2024,
                  "externalIds": {"imdbId": "tt7654321"},
                  "genres": [{"shortName": "drm"}]
                }},
                "offers": [{
                  "audioLanguages": [],
                  "package": {"clearName": "Netflix"}
                }]
              }
            }]
            """.trimIndent()
        ).asJsonArray

        val result = JustWatchOttClient(okhttp3.OkHttpClient()).parseEdges(
            edges = edges,
            expectedKind = OttMediaKind.SERIES,
            provider = OttProvider("Netflix", "nfx"),
            addedDate = "2026-09-14"
        )

        assertEquals(1, result.size)
        assertEquals("tt7654321", result.single().imdbId)
        assertEquals("Parent Show", result.single().title)
    }

    @Test
    fun explicitUnsupportedAudioAndAnimationAreExcluded() {
        val edges = JsonParser.parseString(
            """
            [
              {"node": {"__typename": "Movie", "content": {
                "title": "Spanish Only", "externalIds": {"imdbId": "tt1111111"},
                "genres": [{"shortName": "drm"}]
              }, "offers": [{"audioLanguages": ["Spanish"]}]}},
              {"node": {"__typename": "Movie", "content": {
                "title": "Animated", "externalIds": {"imdbId": "tt2222222"},
                "genres": [{"shortName": "ani"}]
              }, "offers": [{"audioLanguages": ["English"]}]}}
            ]
            """.trimIndent()
        ).asJsonArray

        val result = JustWatchOttClient(okhttp3.OkHttpClient()).parseEdges(
            edges = edges,
            expectedKind = OttMediaKind.MOVIE,
            provider = null,
            addedDate = null
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun undocumentedEndpointPermanentFailureOpensCircuitWithoutRetryStorm() = runBlocking {
        val requests = AtomicInteger()
        val circuit = ProviderFailureCircuit()
        val client = JustWatchOttClient(
            okHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
                requests.incrementAndGet()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(400).message("bad query")
                    .body("{}".toResponseBody("application/json".toMediaType())).build()
            }.build(),
            circuit = circuit
        )

        val first = runCatching { client.popular(null, OttMediaKind.MOVIE, trending = false) }
        val second = runCatching { client.popular(null, OttMediaKind.MOVIE, trending = false) }

        assertTrue(first.isFailure)
        assertTrue(second.exceptionOrNull() is ProviderCircuitOpenException)
        assertEquals("Permanent query errors are not retried or repeated while open", 1, requests.get())
    }

    @Test
    fun rateLimitRetryAfterPausesRequestsUntilProviderWindowExpires() = runBlocking {
        val requests = AtomicInteger()
        var now = 1_000L
        val client = JustWatchOttClient(
            okHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
                requests.incrementAndGet()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(429).message("rate limited").header("Retry-After", "2")
                    .body("{}".toResponseBody("application/json".toMediaType())).build()
            }.build(),
            nowMs = { now }
        )
        suspend fun query() = client.popular(null, OttMediaKind.MOVIE, trending = false)

        assertTrue(runCatching { query() }.isFailure)
        assertTrue(runCatching { query() }.exceptionOrNull() is ProviderCircuitOpenException)
        assertEquals(1, requests.get())
        now += TimeUnit.SECONDS.toMillis(2)
        assertTrue(runCatching { query() }.isFailure)
        assertEquals(2, requests.get())
    }

    @Test
    fun changedGraphQlResponseShapeOpensCircuitInsteadOfReturningEmptyCatalogs() = runBlocking {
        val requests = AtomicInteger()
        val client = JustWatchOttClient(OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("ok")
                .body("{\"data\":{}}".toResponseBody("application/json".toMediaType())).build()
        }.build())

        assertTrue(runCatching { client.popular(null, OttMediaKind.MOVIE, trending = false) }.isFailure)
        assertTrue(runCatching { client.popular(null, OttMediaKind.MOVIE, trending = false) }
            .exceptionOrNull() is ProviderCircuitOpenException)
        assertEquals(1, requests.get())
    }

    @Test
    fun endpointCircuitRecoversAfterCooldownAndOnSuccess() {
        val circuit = ProviderFailureCircuit(failureThreshold = 2, openDurationMs = 10_000)
        circuit.recordFailure(100)
        circuit.check(101)
        circuit.recordFailure(102)
        assertTrue(runCatching { circuit.check(10_101) }.isFailure)
        circuit.check(10_102)
        circuit.recordFailure(10_103)
        circuit.recordSuccess()
        circuit.check(10_104)
    }
}
