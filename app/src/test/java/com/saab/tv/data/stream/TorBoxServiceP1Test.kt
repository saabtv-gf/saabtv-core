package com.saab.tv.data.stream

import android.app.Application
import com.saab.tv.data.model.stremio.Stream
import com.saab.tv.data.security.SecurePreferences
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class TorBoxServiceP1Test {
    private val hash="a".repeat(40)
    private val stream=Stream(infoHash=hash,title="1080p English",seeders=12)
    private val requests=Collections.synchronizedList(mutableListOf<Request>())
    private lateinit var service: TorBoxAvailabilityService
    private var code=200
    private var payload=""
    @Before fun setup() {
        val context=RuntimeEnvironment.getApplication()
        service=TorBoxAvailabilityService(context); service.clear()
        val client=OkHttpClient.Builder().addInterceptor {
            requests+=it.request()
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                .body(payload.toResponseBody("application/json".toMediaType())).build()
        }.build()
        TorBoxAvailabilityService::class.java.getDeclaredField("client").apply { isAccessible=true }.set(service,client)
    }
    @After fun cleanup() { service.clear() }
    private fun configure() {
        // Seed test-only fallback preferences; do not claim to qualify Android Keystore.
        SecurePreferences.create(RuntimeEnvironment.getApplication(),"tmdb_credentials","saabtv_tmdb_master_key")
            .preferences.edit().putString("torbox_api_key","fixture-secret").commit()
    }
    @Test fun noIntegrationNeverQueriesTorboxOrChangesStreams() = runBlocking {
        assertEquals(listOf(stream),service.enrich(listOf(stream))); assertTrue(requests.isEmpty())
    }
    @Test fun successfulCacheEvidenceIsBatchedAuthenticatedAndReused() = runBlocking {
        configure(); payload="""{"success":true,"data":{"$hash":{"hash":"$hash"}}}"""
        val result=service.enrich(listOf(stream,stream.copy(url="second")))
        assertTrue(result.all { it.torBoxChecked && it.torBoxCached == true })
        service.enrich(listOf(stream)); assertEquals(1,requests.size)
        assertTrue(requests.single().url.encodedPath.endsWith("/checkcached"))
        assertEquals("Bearer fixture-secret",requests.single().header("Authorization"))
        assertEquals(hash,requests.single().url.queryParameter("hash"))
        assertTrue(result.all { it.torBoxSeeders == null && it.seeders == 12 })
    }
    @Test fun failedCacheRequestLeavesUnknownInsteadOfInventingCacheMissOrSeeders() = runBlocking {
        configure(); code=503; payload="{}"
        val result=service.enrich(listOf(stream)).single()
        assertTrue(result.torBoxChecked); assertNull(result.torBoxCached); assertNull(result.torBoxSeeders)
        assertEquals(12,result.seeders)
        service.enrich(listOf(stream)); assertEquals(2,requests.size)
    }
    @Test fun successfulEmptyObjectEstablishesCacheMiss() = runBlocking {
        configure(); payload="{\"success\":true,\"data\":{}}"
        assertEquals(false,service.enrich(listOf(stream)).single().torBoxCached)
    }
    @Test fun clearingIntegrationInvalidatesCachedEvidence() = runBlocking {
        configure(); payload="""{"success":true,"data":{"$hash":{"hash":"$hash"}}}"""
        service.enrich(listOf(stream)); service.clear(); configure(); service.enrich(listOf(stream))
        assertEquals(2,requests.size)
    }
}
