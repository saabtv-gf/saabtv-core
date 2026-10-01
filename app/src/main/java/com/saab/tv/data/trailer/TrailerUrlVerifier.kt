package com.saab.tv.data.trailer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** At most four tiny probes at once; never downloads the trailer into memory. */
internal object TrailerUrlVerifier {
    private val slots = Semaphore(4)
    private val client = TrailerHttpTransport.client.newBuilder().callTimeout(4, TimeUnit.SECONDS).build()

    suspend fun playable(variants: List<TrailerPlaybackVariant>): List<TrailerPlaybackVariant> = coroutineScope {
        // Probe each distinct video/audio URL once, even when multiple renditions
        // share the same audio. Both halves must be readable before selection.
        val urls = variants.flatMap { variant ->
            listOfNotNull(variant.videoUrl, variant.audioUrl).map { it to variant.requestHeaders }
        }.distinct()
        val results = urls.map { key -> async(Dispatchers.IO) {
            slots.withPermit { key to probe(key.first, key.second) }
        } }.awaitAll().toMap()
        variants.filter { results[it.videoUrl to it.requestHeaders] == true &&
            (it.audioUrl == null || results[it.audioUrl to it.requestHeaders] == true) }
    }

    private suspend fun probe(url: String, headers: Map<String, String>): Boolean = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        try {
            val builder = Request.Builder().url(url).get().header("Range", "bytes=0-1023")
            headers.forEach { (name, value) -> builder.header(name, value) }
            client.newCall(builder.build()).execute().use { response ->
                com.saab.tv.AppDiagnostics.event("Trailer", "CDN Probe", "httpStatus=${response.code} host=${response.request.url.host}")
                response.isSuccessful && response.body?.source()?.exhausted() == false
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: java.io.IOException) {
            coroutineContext.ensureActive()
            com.saab.tv.AppDiagnostics.event("Trailer", "CDN Probe Failed", "type=${failure.javaClass.simpleName}")
            false
        }
    }
}
