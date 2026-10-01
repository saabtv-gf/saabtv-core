package com.saab.tv.data.trailer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.AudioTrackType
import kotlin.coroutines.coroutineContext
import java.util.concurrent.TimeUnit

/** Serializes the maintained extractor's global player-JS cache off the UI thread. */
internal object MaintainedYoutubeResolver {
    private val mutex = Mutex()
    private var initialized = false
    private var extractionJob: Job? = null
    private var deadlineNanos = 0L

    suspend fun resolve(videoId: String): List<TrailerPlaybackVariant> = withContext(Dispatchers.IO) {
        mutex.withLock {
            coroutineContext.ensureActive()
            extractionJob = coroutineContext[Job]
            deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
            try {
                if (!initialized) {
                    NewPipe.init(object : Downloader() {
                        override fun execute(request: Request): Response {
                            extractionJob?.ensureActive()
                            val remaining = deadlineNanos - System.nanoTime()
                            if (remaining <= 0) throw java.net.SocketTimeoutException("Extraction deadline")
                            val builder = okhttp3.Request.Builder().url(request.url())
                                .header("User-Agent", TrailerHttpTransport.USER_AGENT)
                            request.headers().forEach { (name, values) ->
                                builder.removeHeader(name)
                                values.forEach { builder.addHeader(name, it) }
                            }
                            builder.method(request.httpMethod(), request.dataToSend()?.toRequestBody())
                            val call = TrailerHttpTransport.client.newCall(builder.build())
                            call.timeout().timeout(minOf(remaining, TimeUnit.SECONDS.toNanos(6)), TimeUnit.NANOSECONDS)
                            call.execute().use { response ->
                                return Response(response.code, response.message, response.headers.toMultimap(),
                                    response.body?.string().orEmpty(), response.request.url.toString())
                            }
                        }
                    })
                    initialized = true
                }
                // Fetch only playback information, not comments/recommendations.
                val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
                extractor.fetchPage()
                coroutineContext.ensureActive()
                val headers = mapOf("User-Agent" to TrailerHttpTransport.USER_AGENT)
                val variants = mutableListOf<TrailerPlaybackVariant>()
                val audio = extractor.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                    .sortedWith(compareByDescending<org.schabi.newpipe.extractor.stream.AudioStream> {
                        it.audioTrackType == AudioTrackType.ORIGINAL || it.audioTrackType == null
                    }.thenByDescending { it.averageBitrate }).firstOrNull()
                extractor.videoOnlyStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }.forEach { video ->
                    if (audio != null) {
                        val height = Regex("(\\d{2,4})p").find(video.resolution)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        variants += TrailerPlaybackVariant(video.content, audio.content, video.resolution, height, headers)
                    }
                }
                extractor.videoStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }.forEach { video ->
                    val height = Regex("(\\d{2,4})p").find(video.resolution)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    variants += TrailerPlaybackVariant(video.content, qualityLabel = video.resolution, height = height, requestHeaders = headers)
                }
                extractor.hlsUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    variants += TrailerPlaybackVariant(url, qualityLabel = "Adaptive", requestHeaders = headers)
                }
                com.saab.tv.AppDiagnostics.event("Trailer", "Maintained Extraction", "variants=${variants.size}")
                variants
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: Exception) {
                com.saab.tv.AppDiagnostics.failure("Trailer", "Maintained Extraction Failed", failure)
                emptyList()
            } finally {
                extractionJob = null
            }
        }
    }
}
