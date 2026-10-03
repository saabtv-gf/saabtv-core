package com.saab.tv.data.ott

import android.app.Application
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class OttRepositoryOrchestrationP1Test {
    private val requests=Collections.synchronizedList(mutableListOf<Request>())
    private val cinemeta=AtomicInteger()
    private var failNetflix=false
    private var unresolved=false
    private lateinit var client:OkHttpClient
    private lateinit var context:Application
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("ott_justwatch_catalog_cache",0).edit().clear().commit()
        client=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request();requests+=request
            val (code,body)=when {
                request.url.host=="apis.justwatch.com" -> {
                    val buffer=Buffer();request.body!!.writeTo(buffer)
                    val query=com.google.gson.JsonParser.parseString(buffer.readUtf8()).asJsonObject["query"].asString
                    if(query.contains("newTitleBuckets")) 200 to """{"data":{"newTitleBuckets":{"edges":[]}}}"""
                    else if(failNetflix && query.contains("""packages: ["nfx"]""")) 503 to "{}"
                    else {
                        val id=if(unresolved) "not-imdb" else "tt1234567"
                        200 to """{"data":{"popularTitles":{"edges":[{"node":{"__typename":"Movie","content":{"title":"Candidate","originalReleaseYear":2024,"externalIds":{"imdbId":"$id"},"genres":[{"shortName":"drm"}]},"offers":[{"audioLanguages":["en"],"package":{"clearName":"Netflix"}}]}}]}}}"""
                    }
                }
                request.url.encodedPath=="/meta/movie/tt1234567.json" -> {
                    cinemeta.incrementAndGet();200 to """{"meta":{"id":"tt1234567","type":"movie","name":"Resolved Movie","poster":"https://img.invalid/poster.jpg","genres":["Drama"],"imdbRating":"7.5"}}"""
                }
                else -> 404 to "{}"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
    }
    private fun repository()=OttCatalogRepository(context,client)
    @Test fun mapsCinemetaResolvedIdsIntoCatalogsAndCachesRepeatedCalls()= kotlinx.coroutines.runBlocking {
        val repo=repository();val rows=repo.getCatalogRows()
        assertTrue(rows.isNotEmpty());assertTrue(rows.all { it.items.all { item->item.id=="tt1234567"&&item.name=="Resolved Movie"&&item.poster!!.contains("poster") } })
        assertTrue(requests.any { it.url.host=="apis.justwatch.com" });assertEquals(1,cinemeta.get())
        val before=requests.size;assertEquals(rows.map { it.configId },repo.getCatalogRows().map { it.configId });assertEquals(before,requests.size)
    }
    @Test fun unresolvedIMDbIdentityIsNeverShownAsCatalogTitle()= kotlinx.coroutines.runBlocking {
        unresolved=true
        try { repository().getCatalogRows();fail("Unresolved results should not produce catalogs") }
        catch(e:OttCatalogException) { assertTrue(e.message!!.contains("temporarily unavailable")) }
        assertEquals(0,cinemeta.get())
    }
    @Test fun partialProviderFailureKeepsHealthyRowsAndForceRefreshBypassesCache()= kotlinx.coroutines.runBlocking {
        failNetflix=true
        val repo=repository();val rows=repo.getCatalogRows()
        assertTrue(rows.isNotEmpty());assertFalse(rows.any { it.title.startsWith("Netflix Top 10") })
        assertEquals(1,cinemeta.get())
        val before=requests.size;repo.getCatalogRows(forceRefresh=true);assertTrue(requests.size>before)
    }
    @Test fun failedRefreshFallsBackToLastGoodRows()= kotlinx.coroutines.runBlocking {
        val repo=repository();val rows=repo.getCatalogRows();failNetflix=true;unresolved=true
        assertEquals(rows.map { it.configId },repo.getCatalogRows(forceRefresh=true).map { it.configId })
    }
}
