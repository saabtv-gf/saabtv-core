package com.saab.tv.data.trailer

import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "YouTubeExtractor"
private const val EXTRACTOR_TIMEOUT_MS = 30_000L
private const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
// Pair audio/video from the same client and rank across the full adaptive ladder.
private val CLIENT_PREFERENCE = listOf("visionos", "android", "ios", "android_vr")

private val API_KEY_REGEX = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"")
private val VISITOR_DATA_REGEX = Regex("\"VISITOR_DATA\":\"([^\"]+)\"")
private val QUALITY_LABEL_REGEX = Regex("(\\d{2,4})p")

private data class YouTubeClient(
    val key: String,
    val id: String,
    val version: String,
    val userAgent: String,
    val context: Map<String, Any>,
    val priority: Int
)

private data class WatchConfig(val apiKey: String?, val visitorData: String?)

private data class StreamCandidate(
    val client: String, val priority: Int, val url: String,
    val score: Double, val hasN: Boolean, val itag: String,
    val height: Int, val fps: Int, val ext: String,
    val mimeType: String,
    val isDefaultAudio: Boolean = true
)

private data class ManifestBestVariant(
    val url: String, val width: Int, val height: Int, val bandwidth: Long
)

private data class ManifestCandidate(
    val client: String, val priority: Int, val manifestUrl: String,
    val selectedVariantUrl: String, val height: Int, val bandwidth: Long
)

private val DEFAULT_HEADERS = mapOf(
    "accept-language" to "en-US,en;q=0.9",
    "user-agent" to DEFAULT_USER_AGENT
)

private val CLIENTS = listOf(
    // Adapted from NuvioTV's InAppYouTubeExtractor (GPL-3.0), revision
    // 7f32b3c548a5d7e7771f654ac963bc530497708f. No iframe or resolver backend.
    YouTubeClient(
        key = "visionos", id = "101", version = "1.02",
        userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/26.0 Safari/605.1.15",
        context = mapOf(
            "clientName" to "VISIONOS", "clientVersion" to "1.02",
            "deviceMake" to "Apple", "deviceModel" to "RealityDevice17,1",
            "osName" to "visionOS", "osVersion" to "26.5.23O471", "hl" to "en", "gl" to "US"
        ), priority = -1
    ),
    YouTubeClient(
        key = "android_vr", id = "28", version = "1.56.21",
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.56.21 " +
            "(Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1) gzip",
        context = mapOf(
            "clientName" to "ANDROID_VR", "clientVersion" to "1.56.21",
            "deviceMake" to "Oculus", "deviceModel" to "Quest 3",
            "osName" to "Android", "osVersion" to "12",
            "platform" to "MOBILE", "androidSdkVersion" to 32,
            "hl" to "en", "gl" to "US"
        ),
        priority = 0
    ),
    YouTubeClient(
        key = "android", id = "3", version = "20.10.35",
        userAgent = "com.google.android.youtube/20.10.35 (Linux; U; Android 14; en_US) gzip",
        context = mapOf(
            "clientName" to "ANDROID", "clientVersion" to "20.10.35",
            "osName" to "Android", "osVersion" to "14",
            "platform" to "MOBILE", "androidSdkVersion" to 34,
            "hl" to "en", "gl" to "US"
        ),
        priority = 1
    ),
    YouTubeClient(
        key = "ios", id = "5", version = "20.10.1",
        userAgent = "com.google.ios.youtube/20.10.1 (iPhone16,2; U; CPU iOS 17_4 like Mac OS X)",
        context = mapOf(
            "clientName" to "IOS", "clientVersion" to "20.10.1",
            "deviceModel" to "iPhone16,2", "osName" to "iPhone",
            "osVersion" to "17.4.0.21E219", "platform" to "MOBILE",
            "hl" to "en", "gl" to "US"
        ),
        priority = 2
    )
)

@Singleton
class YouTubeExtractor @Inject constructor() {
    companion object {
        private val sources = com.saab.tv.data.cache.PreviewWarmupCache<TrailerPlaybackSource?>(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO),
            { System.nanoTime() / 1_000_000 }, ttlMs = 120_000, capacity = 6)
    }
    private val gson = Gson()

    private val httpClient = TrailerHttpTransport.client.newBuilder()
        .callTimeout(6, TimeUnit.SECONDS).build()

    suspend fun extractPlaybackSource(youtubeKey: String): TrailerPlaybackSource? = withContext(Dispatchers.IO) {
        val videoId = TrailerPolicy.videoId(youtubeKey) ?: return@withContext null
        sources.prefetch(videoId) { extractUncached(videoId) }
        val result = sources.awaitIfPresent(videoId)
        // A transient resolver failure must not poison the next explicit retry.
        if (result == null) sources.invalidate(videoId)
        result
    }

    private suspend fun extractUncached(youtubeKey: String): TrailerPlaybackSource? {

        Log.d(TAG, "Extracting playback source for key: $youtubeKey")
        return try {
            withTimeout(EXTRACTOR_TIMEOUT_MS) {
                extractInternal(youtubeKey)
            }
        } catch (e: TimeoutCancellationException) {
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Extraction failed for $youtubeKey: ${e.message}")
            null
        }
    }

    private suspend fun extractInternal(videoKey: String): TrailerPlaybackSource? {
        val videoId = TrailerPolicy.videoId(videoKey) ?: return null

        val maintained = TrailerPolicy.rank(MaintainedYoutubeResolver.resolve(videoId)).take(16)
        val verified = TrailerUrlVerifier.bestWithFallbacks(maintained)
        // The maintained resolver already exposes the highest adaptive quality.
        // A second complete extraction cannot improve a missing 4K rendition in
        // this ladder reliably; reserve the legacy round for extraction failure.
        val legacy = if (verified.isEmpty()) {
            try { withTimeoutOrNull(12_000) { extractLegacy(videoId) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        } else null
        val candidates = verified + legacy?.let { source ->
            listOf(TrailerPlaybackVariant(source.videoUrl, source.audioUrl, source.qualityLabel,
                Regex("(\\d{2,4})p").find(source.qualityLabel)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                source.requestHeaders)) + source.fallbackVariants
        }.orEmpty()
        val ranked = TrailerPolicy.rank(candidates)
        val primary = ranked.firstOrNull() ?: return null
        com.saab.tv.AppDiagnostics.event("Trailer", "Verified Source Selected",
            "quality=${primary.qualityLabel} fallbacks=${ranked.size - 1} resolver=${if (verified.isNotEmpty()) "maintained" else "legacy"}")
        return TrailerPlaybackSource(primary.videoUrl, primary.audioUrl, ranked.drop(1),
            primary.qualityLabel, primary.requestHeaders)
    }

    private suspend fun extractLegacy(videoId: String): TrailerPlaybackSource? {

        val watchUrl = "https://www.youtube.com/watch?v=$videoId&hl=en"
        val watchResponse = performRequest(watchUrl, "GET", DEFAULT_HEADERS)
        val watchConfig = getWatchConfig(watchResponse.body)
        // Public web-client key from Nuvio, not an account credential. Consent pages
        // can lack watch config even when the public player endpoint works.
        val apiKey = watchConfig.apiKey ?: "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"

        val progressive = mutableListOf<StreamCandidate>()
        val adaptiveVideo = mutableListOf<StreamCandidate>()
        val adaptiveAudio = mutableListOf<StreamCandidate>()
        val manifestUrls = mutableListOf<Triple<String, Int, String>>()
        val dashUrls = mutableListOf<Pair<String, String>>()

        // Bound latency to one request round, not four serial client timeouts.
        val responses = coroutineScope {
            CLIENTS.map { client -> async(Dispatchers.IO) {
                try {
                    client to fetchPlayerResponse(apiKey, videoId, client, watchConfig.visitorData)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    Log.w(TAG, "Client ${client.key} failed: ${e.javaClass.simpleName}")
                    client to emptyMap<String, Any>()
                }
            } }.awaitAll()
        }
        for ((client, playerResponse) in responses) {
            coroutineContext.ensureActive()
            try {
                val status = playerResponse.mapValue("playabilityStatus")?.stringValue("status")
                if (status != null && status != "OK") continue
                val streamingData = playerResponse.mapValue("streamingData") ?: continue
                streamingData.stringValue("dashManifestUrl")?.takeIf { it.isNotBlank() }?.let {
                    dashUrls += client.key to it
                }
                com.saab.tv.AppDiagnostics.event("Trailer", "Resolved Client",
                    "videoId=$videoId client=${client.key} heights=" +
                        streamingData.listMapValue("adaptiveFormats").mapNotNull { it.numberValue("height")?.toInt() }
                            .distinct().sortedDescending().joinToString(",") +
                        " cipherFormats=" + streamingData.listMapValue("adaptiveFormats")
                            .count { it.stringValue("signatureCipher") != null } +
                        " dash=${streamingData.stringValue("dashManifestUrl") != null}")

                streamingData.stringValue("hlsManifestUrl")?.takeIf { it.isNotBlank() }?.let {
                    manifestUrls += Triple(client.key, client.priority, it)
                }

                for (format in streamingData.listMapValue("formats")) {
                    val url = format.stringValue("url") ?: continue
                    val mimeType = format.stringValue("mimeType").orEmpty()
                    if (!mimeType.contains("video/") && mimeType.isNotBlank()) continue

                    val height = (format.numberValue("height")
                        ?: parseQualityLabel(format.stringValue("qualityLabel"))?.toDouble() ?: 0.0).toInt()
                    val fps = (format.numberValue("fps") ?: 0.0).toInt()
                    val bitrate = format.numberValue("bitrate") ?: format.numberValue("averageBitrate") ?: 0.0

                    progressive += StreamCandidate(
                        client.key, client.priority, url, videoScore(height, fps, bitrate),
                        hasNParam(url), format.stringValue("itag").orEmpty(),
                        height, fps, if (mimeType.contains("webm")) "webm" else "mp4",
                        mimeType
                    )
                }

                for (format in streamingData.listMapValue("adaptiveFormats")) {
                    val url = format.stringValue("url") ?: continue
                    val mimeType = format.stringValue("mimeType").orEmpty()

                    if (mimeType.contains("video/")) {
                        val height = (format.numberValue("height")
                            ?: parseQualityLabel(format.stringValue("qualityLabel"))?.toDouble() ?: 0.0).toInt()
                        val fps = (format.numberValue("fps") ?: 0.0).toInt()
                        val bitrate = format.numberValue("bitrate") ?: format.numberValue("averageBitrate") ?: 0.0

                        adaptiveVideo += StreamCandidate(
                            client.key, client.priority, url, videoScore(height, fps, bitrate),
                            hasNParam(url), format.stringValue("itag").orEmpty(),
                            height, fps, if (mimeType.contains("webm")) "webm" else "mp4",
                            mimeType
                        )
                    } else if (mimeType.contains("audio/")) {
                        val bitrate = format.numberValue("bitrate") ?: format.numberValue("averageBitrate") ?: 0.0
                        val asr = format.numberValue("audioSampleRate") ?: 0.0

                        adaptiveAudio += StreamCandidate(
                            client.key, client.priority, url, audioScore(bitrate, asr),
                            hasNParam(url), format.stringValue("itag").orEmpty(),
                            0, 0, if (mimeType.contains("webm")) "webm" else "m4a",
                            mimeType,
                            format.mapValue("audioTrack")?.get("audioIsDefault") as? Boolean ?: true
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Client ${client.key} failed: ${e.message}")
            }
        }

        Log.d(TAG, "Streams found: progressive=${progressive.size} adaptiveVideo=${adaptiveVideo.size} adaptiveAudio=${adaptiveAudio.size} hls=${manifestUrls.size}")

        if (manifestUrls.isEmpty() && dashUrls.isEmpty() && progressive.isEmpty() && adaptiveVideo.isEmpty() && adaptiveAudio.isEmpty()) {
            Log.w(TAG, "No playable streams found for $videoId")
            return null
        }

        val manifests = coroutineScope {
            manifestUrls.map { (clientKey, priority, manifestUrl) -> async(Dispatchers.IO) {
                try {
                    parseHlsManifest(manifestUrl, clientHeaders(clientKey))?.let { variant ->
                        ManifestCandidate(clientKey, priority, manifestUrl, variant.url, variant.height, variant.bandwidth)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    Log.w(TAG, "Manifest parse failed: ${e.javaClass.simpleName}")
                    null
                }
            } }.awaitAll().filterNotNull()
        }
        val bestManifest = manifests.maxWithOrNull(compareBy<ManifestCandidate> { it.height }.thenBy { it.bandwidth })

        val variants = buildAdaptiveVariants(adaptiveVideo, adaptiveAudio).toMutableList()
        // Some clients expose their highest rendition only through a signed DASH
        // manifest, rather than a direct adaptiveFormats URL.
        variants += coroutineScope {
            dashUrls.map { (client, url) -> async(Dispatchers.IO) {
                try {
                    val headers = clientHeaders(client)
                    val response = performRequest(url, "GET", headers)
                    if (!response.ok) null else {
                        val height = TrailerPolicy.manifestHeight(response.body)
                        TrailerPlaybackVariant(url, qualityLabel = "${height}p", height = height,
                            requestHeaders = headers)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            } }.awaitAll().filterNotNull()
        }

        // A combined AVC/AAC progressive stream is the most broadly compatible
        // fallback when a CDN rejects one of the adaptive URLs.
        sortCandidates(progressive).firstOrNull()?.let { progressiveSource ->
            variants += TrailerPlaybackVariant(
                videoUrl = progressiveSource.url,
                qualityLabel = qualityLabel(progressiveSource),
                height = progressiveSource.height,
                requestHeaders = clientHeaders(progressiveSource.client)
            )
        }
        bestManifest?.let { manifest ->
            variants += TrailerPlaybackVariant(
                videoUrl = manifest.manifestUrl,
                qualityLabel = manifest.height.takeIf { it > 0 }?.let { "${it}p" }.orEmpty(),
                height = manifest.height,
                requestHeaders = clientHeaders(manifest.client)
            )
        }

        val distinctVariants = TrailerUrlVerifier.bestWithFallbacks(TrailerPolicy.rank(variants).take(16))
        val primary = distinctVariants.firstOrNull() ?: return null
        com.saab.tv.AppDiagnostics.event("Trailer", "Selected Quality",
            "videoId=$videoId selected=${primary.qualityLabel} available=" +
                distinctVariants.map { it.qualityLabel }.distinct().joinToString(","))

        Log.d(
            TAG,
            "Extraction success: quality=${primary.qualityLabel}, " +
                "audioPresent=${!primary.audioUrl.isNullOrBlank()}, fallbacks=${distinctVariants.size - 1}"
        )

        return TrailerPlaybackSource(
            videoUrl = primary.videoUrl,
            audioUrl = primary.audioUrl,
            fallbackVariants = distinctVariants.drop(1),
            qualityLabel = primary.qualityLabel,
            requestHeaders = primary.requestHeaders
        )
    }

    private fun getWatchConfig(html: String): WatchConfig {
        return WatchConfig(
            apiKey = API_KEY_REGEX.find(html)?.groupValues?.getOrNull(1),
            visitorData = VISITOR_DATA_REGEX.find(html)?.groupValues?.getOrNull(1)
        )
    }

    private fun fetchPlayerResponse(apiKey: String, videoId: String, client: YouTubeClient, visitorData: String?): Map<*, *> {
        val endpoint = "https://www.youtube.com/youtubei/v1/player?key=${Uri.encode(apiKey)}"
        val headers = buildMap {
            putAll(DEFAULT_HEADERS)
            put("content-type", "application/json")
            put("origin", "https://www.youtube.com")
            put("x-youtube-client-name", client.id)
            put("x-youtube-client-version", client.version)
            put("user-agent", client.userAgent)
            if (!visitorData.isNullOrBlank()) put("x-goog-visitor-id", visitorData)
        }
        val payload = mapOf(
            "videoId" to videoId, "contentCheckOk" to true, "racyCheckOk" to true,
            "context" to mapOf("client" to client.context),
            "playbackContext" to mapOf("contentPlaybackContext" to mapOf("html5Preference" to "HTML5_PREF_WANTS"))
        )
        val response = performRequest(endpoint, "POST", headers, gson.toJson(payload))
        if (!response.ok) throw IllegalStateException("player API ${client.key} failed (${response.status})")
        return gson.fromJson(response.body, Map::class.java) ?: emptyMap<String, Any>()
    }

    private fun parseHlsManifest(manifestUrl: String, headers: Map<String, String>): ManifestBestVariant? {
        val response = performRequest(manifestUrl, "GET", headers)
        if (!response.ok) throw IllegalStateException("Failed to fetch HLS manifest (${response.status})")

        val lines = response.body.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        var best: ManifestBestVariant? = null

        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val attrs = parseHlsAttributeList(line)
            val nextLine = lines.getOrNull(i + 1) ?: continue
            if (nextLine.startsWith("#")) continue

            val (width, height) = parseResolution(attrs["RESOLUTION"].orEmpty())
            val bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L
            val candidate = ManifestBestVariant(absolutizeUrl(manifestUrl, nextLine), width, height, bandwidth)

            if (best == null || candidate.height > best.height ||
                (candidate.height == best.height && candidate.bandwidth > best.bandwidth)) {
                best = candidate
            }
        }
        return best
    }

    private fun parseHlsAttributeList(line: String): Map<String, String> {
        val index = line.indexOf(':')
        if (index == -1) return emptyMap()
        val raw = line.substring(index + 1)
        val out = LinkedHashMap<String, String>()
        val key = StringBuilder(); val value = StringBuilder()
        var inKey = true; var inQuote = false

        for (ch in raw) {
            if (inKey) { if (ch == '=') inKey = false else key.append(ch); continue }
            if (ch == '"') { inQuote = !inQuote; continue }
            if (ch == ',' && !inQuote) {
                val k = key.toString().trim(); if (k.isNotEmpty()) out[k] = value.toString().trim()
                key.clear(); value.clear(); inKey = true; continue
            }
            value.append(ch)
        }
        val lastKey = key.toString().trim(); if (lastKey.isNotEmpty()) out[lastKey] = value.toString().trim()
        return out
    }

    private fun parseResolution(raw: String): Pair<Int, Int> {
        val parts = raw.split('x')
        return if (parts.size == 2) (parts[0].toIntOrNull() ?: 0) to (parts[1].toIntOrNull() ?: 0) else 0 to 0
    }

    private fun parseQualityLabel(label: String?): Int? =
        label?.let { QUALITY_LABEL_REGEX.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

    private fun hasNParam(url: String) = runCatching { !Uri.parse(url).getQueryParameter("n").isNullOrBlank() }.getOrDefault(false)
    private fun videoScore(height: Int, fps: Int, bitrate: Double) = height * 1_000_000_000.0 + fps * 1_000_000.0 + bitrate
    private fun audioScore(bitrate: Double, asr: Double) = bitrate * 1_000_000.0 + asr

    private fun sortCandidates(items: List<StreamCandidate>) = items.sortedWith(
        compareBy<StreamCandidate> { !it.isDefaultAudio }
            .thenByDescending { it.score }
            .thenBy { if (it.hasN) 1 else 0 }
            .thenBy { when (it.ext.lowercase()) { "mp4", "m4a" -> 0; "webm" -> 1; else -> 2 } }
            .thenBy { it.priority }
    )

    private fun buildAdaptiveVariants(
        videoCandidates: List<StreamCandidate>,
        audioCandidates: List<StreamCandidate>
    ): List<TrailerPlaybackVariant> {
        val result = mutableListOf<TrailerPlaybackVariant>()

        for (client in CLIENT_PREFERENCE) {
            val videos = sortCandidates(videoCandidates.filter { it.client == client })
            val audios = sortCandidates(audioCandidates.filter { it.client == client })
            val bestAudio = audios.firstOrNull() ?: continue

            // Highest quality offered by this client.
            for (video in videos.distinctBy { it.height }.take(5)) {
                result += TrailerPlaybackVariant(
                videoUrl = video.url,
                audioUrl = bestAudio.url,
                qualityLabel = qualityLabel(video),
                height = video.height,
                requestHeaders = clientHeaders(client)
                )
            }

            // AVC + AAC is retained as a decoder-compatible fallback. It may point
            // at a different CDN URL even when its resolution matches the primary.
            val avcVideo = videos.firstOrNull { it.mimeType.contains("avc1", ignoreCase = true) }
            val aacAudio = audios.firstOrNull { it.mimeType.contains("mp4a", ignoreCase = true) }
            if (avcVideo != null && aacAudio != null) {
                result += TrailerPlaybackVariant(
                    videoUrl = avcVideo.url,
                    audioUrl = aacAudio.url,
                    qualityLabel = qualityLabel(avcVideo),
                    height = avcVideo.height,
                    requestHeaders = clientHeaders(client)
                )
            }
        }

        return result
    }

    private fun clientHeaders(client: String): Map<String, String> = mapOf(
        "User-Agent" to (CLIENTS.firstOrNull { it.key == client }?.userAgent ?: DEFAULT_USER_AGENT),
        "Accept-Language" to "en-US,en;q=0.9"
    )

    private fun qualityLabel(candidate: StreamCandidate): String = buildString {
        if (candidate.height > 0) append("${candidate.height}p")
        if (candidate.fps > 30) append("${candidate.fps}")
    }

    private fun absolutizeUrl(baseUrl: String, maybeRelative: String) =
        runCatching { URL(URL(baseUrl), maybeRelative).toString() }.getOrElse { maybeRelative }

    private fun performRequest(url: String, method: String, headers: Map<String, String>, body: String? = null): RequestResponse {
        val rb = Request.Builder().url(url).headers(buildHeaders(headers))
        when (method.uppercase()) {
            "POST" -> rb.post((body ?: "").toRequestBody())
            else -> rb.get()
        }
        httpClient.newCall(rb.build()).execute().use { r ->
            return RequestResponse(r.isSuccessful, r.code, r.message, r.request.url.toString(), r.body?.string().orEmpty())
        }
    }

    private fun buildHeaders(source: Map<String, String>): Headers {
        val b = Headers.Builder()
        source.forEach { (k, v) -> if (!k.equals("Accept-Encoding", ignoreCase = true)) b.add(k, v) }
        if (source.keys.none { it.equals("User-Agent", ignoreCase = true) }) b.add("User-Agent", DEFAULT_USER_AGENT)
        return b.build()
    }
}

private data class RequestResponse(val ok: Boolean, val status: Int, val statusText: String, val url: String, val body: String)

private fun Map<*, *>.mapValue(key: String) = this[key] as? Map<*, *>
private fun Map<*, *>.listMapValue(key: String) = (this[key] as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: emptyList()
private fun Map<*, *>.stringValue(key: String) = this[key]?.toString()
private fun Map<*, *>.numberValue(key: String): Double? = when (val v = this[key]) {
    is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null
}
