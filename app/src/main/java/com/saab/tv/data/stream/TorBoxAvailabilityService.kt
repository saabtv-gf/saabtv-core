package com.saab.tv.data.stream

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.saab.tv.data.model.stremio.Stream
import com.saab.tv.data.security.SecurePreferences
import com.saab.tv.data.account.AccountStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Read-only checks: never creates downloads, changes torrents or logs credentials. */
@Singleton
class TorBoxAvailabilityService @Inject constructor(@ApplicationContext private val context: Context) {
    // Separate key in an existing cloud-synced encrypted store preserves snapshot compatibility.
    private fun store() = SecurePreferences.create(context, "tmdb_credentials", "saabtv_tmdb_master_key")
    fun configured(): Boolean {
        val secure = store()
        val present = !secure.preferences.getString("torbox_api_key", null).isNullOrBlank()
        com.saab.tv.AppDiagnostics.torBoxEvent(context, "Integration Key Probe",
            "present=$present persistent=${secure.isPersistent} accountBound=${AccountStorage.userId(context) != null}")
        return present
    }
    fun save(key: String): Boolean {
        val secure = store()
        if (!secure.isPersistent || key.isBlank() || key.any { it.isWhitespace() }) {
            com.saab.tv.AppDiagnostics.torBoxEvent(context, "Key Save Rejected",
                "persistent=${secure.isPersistent} blank=${key.isBlank()} whitespace=${key.any { it.isWhitespace() }}")
            return false
        }
        results.clear()
        val saved = secure.preferences.edit().putString("torbox_api_key", key.trim()).commit()
        com.saab.tv.AppDiagnostics.torBoxEvent(context, "Key Save Result", "saved=$saved")
        return saved
    }
    fun clear() {
        val cleared = store().preferences.edit().remove("torbox_api_key").commit()
        results.clear()
        com.saab.tv.AppDiagnostics.torBoxEvent(context, "Key Clear Result", "cleared=$cleared")
    }
    private val client = OkHttpClient.Builder().callTimeout(9, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    private data class Evidence(val cached: Boolean?, val seeds: Int?, val at: Long)
    private val results = ConcurrentHashMap<String, Evidence>()
    private val permits = Semaphore(4)

    private suspend fun request(path: String, key: String?, query: Map<String, String>): JsonObject? = withContext(Dispatchers.IO) {
        val url = "https://api.torbox.app/v1/api/torrents/$path".toHttpUrl().newBuilder().apply {
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        val request = Request.Builder().url(url).apply { key?.let { header("Authorization", "Bearer $it") } }.build()
        val call = client.newCall(request)
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { continuation.resume(null) }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val value = response.use { runCatching {
                        if (!it.isSuccessful) null else it.body?.string()?.let { text ->
                            JsonParser.parseString(text).takeIf { parsed -> parsed.isJsonObject }?.asJsonObject
                        }
                    }.getOrNull() }
                    continuation.resume(value)
                }
            })
        }
    }

    suspend fun enrich(streams: List<Stream>): List<Stream> = coroutineScope {
        val secure = store()
        val key = secure.preferences.getString("torbox_api_key", null)?.takeIf { it.isNotBlank() }
        com.saab.tv.AppDiagnostics.torBoxEvent(context, "Source Key Probe",
            "present=${key != null} persistent=${secure.isPersistent} accountBound=${AccountStorage.userId(context) != null} sources=${streams.size}")
        if (key == null) {
            com.saab.tv.AppDiagnostics.torBoxEvent(context, "Enrichment Skipped", "reason=key_unavailable sources=${streams.size}")
            return@coroutineScope streams
        }
        val hashes = streams.mapNotNull(TorBoxAvailabilityPolicy::hash).distinct()
        com.saab.tv.AppDiagnostics.torBoxEvent(context, "Enrichment Started", "sources=${streams.size} usableHashes=${hashes.size}")
        if (hashes.isEmpty()) return@coroutineScope streams.map { stream ->
            if (StreamSourceProviderResolver.requiresSeederMetadata(stream))
                stream.copy(torBoxChecked = true, torBoxCached = null, torBoxSeeders = null)
            else stream
        }.also { output ->
            com.saab.tv.AppDiagnostics.torBoxEvent(context, "Enrichment Completed",
                "sources=${output.size} checked=${output.count { it.torBoxChecked }} missingHash=${output.count { it.torBoxChecked && TorBoxAvailabilityPolicy.hash(it) == null }}")
        }
        val evidence = ConcurrentHashMap<String, Evidence>()
        // Bound source-picker latency. Unfinished checks remain unknown, never zero.
        withTimeoutOrNull(15_000L) {
            hashes.chunked(80).forEach { chunk ->
                val response = request("checkcached", key, mapOf("hash" to chunk.joinToString(","), "format" to "object", "list_files" to "false"))
                chunk.forEach { hash ->
                    val prior = results[hash]?.takeIf { System.currentTimeMillis() - it.at in 0..60_000L }
                    // Only a successful object response can establish a cache miss.
                    val cached = TorBoxAvailabilityPolicy.cached(response, hash)
                    evidence[hash] = Evidence(cached, prior?.seeds, System.currentTimeMillis())
                }
            }
            hashes.map { hash -> async {
                permits.withPermit {
                    val prior = results[hash]?.takeIf { System.currentTimeMillis() - it.at in 0..60_000L }
                    val seeds = prior?.seeds ?: request("torrentinfo", key,
                        mapOf("hash" to hash, "timeout" to "7", "use_cache_lookup" to "false"))?.let { TorBoxAvailabilityPolicy.seeds(it, hash) }
                    val value = Evidence(evidence[hash]?.cached, seeds, System.currentTimeMillis())
                    evidence[hash] = value
                    if (value.cached != null || value.seeds != null) results[hash] = value
                }
            } }.awaitAll()
        }
        val enriched = streams.map { stream ->
            val hash = TorBoxAvailabilityPolicy.hash(stream) ?: return@map if (StreamSourceProviderResolver.requiresSeederMetadata(stream)) {
                stream.copy(torBoxChecked = true, torBoxCached = null, torBoxSeeders = null)
            } else stream
            val value = evidence[hash]
            stream.copy(torBoxChecked = true, torBoxCached = value?.cached, torBoxSeeders = value?.seeds)
        }.filterNot(TorBoxAvailabilityPolicy::remove)
        if (results.size > 2_000) results.clear()
        com.saab.tv.AppDiagnostics.torBoxEvent(context, "Enrichment Completed",
            "sources=${streams.size} hashes=${hashes.size} output=${enriched.size} checked=${enriched.count { it.torBoxChecked }} " +
                "cached=${enriched.count { it.torBoxCached == true }} removed=${streams.size - enriched.size} " +
                "unknownCache=${enriched.count { it.torBoxChecked && it.torBoxCached == null }} " +
                "torBoxSeeders=${enriched.count { it.torBoxSeeders != null }} " +
                "missingHash=${enriched.count { it.torBoxChecked && TorBoxAvailabilityPolicy.hash(it) == null }}")
        enriched
    }
}
