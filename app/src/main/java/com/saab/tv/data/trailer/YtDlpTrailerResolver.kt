package com.saab.tv.data.trailer

import android.content.Context
import com.saab.tv.AppDiagnostics
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** One bounded metadata process, initialized lazily off the UI thread. No media downloads. */
internal object YtDlpTrailerResolver {
    private var context: Context? = null
    private val mutex = Mutex()
    fun configure(context: Context) { this.context = context.applicationContext }

    suspend fun resolve(videoId: String): List<TrailerPlaybackVariant> = mutex.withLock {
        if (TrailerPolicy.videoId(videoId) != videoId) return@withLock emptyList()
        val app = context ?: return@withLock emptyList()
        val processId = "trailer-${UUID.randomUUID()}"
        try {
            withTimeoutOrNull(12_000L) {
                val json = runInterruptible(Dispatchers.IO) {
                    YoutubeDL.getInstance().init(app)
                    val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId")
                    request.addOption("--ignore-config")
                    request.addOption("--no-playlist")
                    request.addOption("--skip-download")
                    request.addOption("--dump-single-json")
                    request.addOption("--no-cache-dir")
                    request.addOption("--socket-timeout", "4")
                    request.addOption("--retries", "0")
                    request.addOption("--extractor-retries", "0")
                    request.addOption("--no-warnings")
                    YoutubeDL.getInstance().execute(request, processId, null).out
                }
                YtDlpTrailerFormats.parse(json).also {
                    AppDiagnostics.event("Trailer", "yt-dlp Resolved", "variants=${it.size} highest=${it.firstOrNull()?.height ?: 0}")
                }
            }.orEmpty()
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) {
            // yt-dlp errors may contain signed URLs; do not log raw output.
            AppDiagnostics.event("Trailer", "yt-dlp Failed", "type=${failure.javaClass.simpleName}")
            emptyList()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { YoutubeDL.getInstance().destroyProcessById(processId) }
        }
    }
}
