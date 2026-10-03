package com.saab.tv.data.cache

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import dev.jdtech.mpv.MPVLib
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException

/**
 * Persistent, low-resource secondary mpv instance inspired by ThumbFast's
 * architecture. Android owns the lifecycle, while a null video output and
 * mpv's in-memory screenshot-raw command captures decoded video frames without
 * a hidden UI or an encoder/output file.
 */
internal class LibMpvThumbnailEngine(
    private val context: Context,
    private val createSession: (Context) -> LibMpvSession? = { appContext ->
        MPVLib.create(appContext)?.let(::NativeLibMpvSession)
    }
) : Closeable, MPVLib.EventObserver {
    private var mpv: LibMpvSession? = null

    @Volatile private var fileLoaded = false
    @Volatile private var fileEnded = false
    @Volatile private var closed = false
    @Volatile private var playbackRestartGeneration = 0L
    private var recentSeekLogs = ""
    var lastCapturePositionMs: Long? = null
        private set
    var lastError: String? = null
        private set
    var initializationTrace: String = ""
        private set

    suspend fun open(mediaUrl: String): Boolean {
        if (closed || mediaUrl.isBlank()) return false
        lastError = null
        initializationTrace = ""
        return try {
            val instance = createSession(context) ?: run {
                lastError = "MPVLib.create returned null"
                return false
            }
            mpv = instance
            initializationTrace = configure(instance)
            instance.addObserver(this)
            val initializationError = instance.initDetailed()
            if (initializationError != null) {
                lastError = "mpv_initialize ${sanitizeNativeLogs(initializationError)}"
                close()
                return false
            }
            val loadResult = instance.commandDetailed(arrayOf("loadfile", mediaUrl, "replace"))
            if (loadResult < 0) {
                lastError = commandFailure(instance, "loadfile", loadResult)
                return false
            }

            val loaded = waitUntil(LOAD_TIMEOUT_MS) {
                fileLoaded || fileEnded || runCatching {
                    !instance.getPropertyString("path").isNullOrBlank() &&
                        (instance.getPropertyDouble("duration") ?: 0.0) > 0.0
                }.getOrDefault(false)
            }
            if (!loaded || fileEnded) {
                val nativeLogs = sanitizeNativeLogs(instance.drainDiagnosticLogs())
                lastError = buildString {
                    append(if (fileEnded) "stream ended while opening" else "open timed out")
                    if (nativeLogs.isNotBlank()) append(" logs=$nativeLogs")
                }
                return false
            }
            // Property polling is the fallback for wrapper builds that do not
            // deliver FILE_LOADED callbacks reliably from a service process.
            fileLoaded = true
            instance.setPropertyBoolean("pause", true)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            lastError = errorSummary(error)
            close()
            false
        }
    }

    suspend fun capture(positionMs: Long): Bitmap? {
        val instance = mpv ?: return null
        if (closed || !fileLoaded || fileEnded) return null
        lastError = null
        lastCapturePositionMs = null
        return try {
            instance.drainDiagnosticLogs()
            recentSeekLogs = ""
            val previousRestart = playbackRestartGeneration
            val seconds = String.format(Locale.US, "%.3f", positionMs.coerceAtLeast(0L) / 1_000.0)
            val seekResult = instance.commandDetailed(
                arrayOf("seek", seconds, "absolute+exact")
            )
            if (seekResult < 0) {
                lastError = commandFailure(instance, "seek", seekResult)
                return null
            }
            val seekSettled = waitForSeek(
                instance = instance,
                previousRestart = previousRestart,
                targetPositionSeconds = positionMs.coerceAtLeast(0L) / 1_000.0
            )
            if (!seekSettled) {
                lastError = "exact seek did not settle target=${positionMs}ms actual=${instance.getPropertyDouble("time-pos")} restart=${playbackRestartGeneration > previousRestart} logs=$recentSeekLogs"
                return null
            }
            var rawFrame = instance.screenshotRaw()
            if (rawFrame == null && !fileEnded) {
                val stepResult = instance.commandDetailed(arrayOf("frame-step"))
                if (stepResult < 0) {
                    lastError = commandFailure(instance, "frame-step", stepResult)
                    return null
                }
                rawFrame = waitForRawFrame(instance)
            }
            if (rawFrame == null || fileEnded) {
                val nativeLogs = sanitizeNativeLogs(instance.drainDiagnosticLogs())
                lastError = if (fileEnded) {
                    "stream ended during seek"
                } else {
                    "screenshot-raw returned no frame ${instance.screenshotRawError()}"
                }
                lastError += " seekSettled=$seekSettled " +
                    "timePos=${instance.getPropertyDouble("time-pos") ?: -1.0}"
                if (nativeLogs.isNotBlank()) lastError += " logs=$nativeLogs"
                return null
            }
            lastCapturePositionMs = instance.getPropertyDouble("time-pos")?.let { (it * 1_000).toLong() }
            if (!ThumbnailTimelinePolicy.captureMatches(positionMs, lastCapturePositionMs)) {
                lastError = "frame timestamp mismatch requested=${positionMs}ms actual=${lastCapturePositionMs}ms"
                return null
            }
            decodeThumbnail(rawFrame).also { bitmap ->
                if (bitmap == null) lastError = "invalid screenshot-raw payload=${rawFrame.size}"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            lastError = errorSummary(error)
            null
        }
    }

    private fun configure(instance: LibMpvSession): String {
        val options = mapOf(
            "config" to "no",
            "load-scripts" to "no",
            "load-auto-profiles" to "no",
            "osc" to "no",
            "input-default-bindings" to "no",
            "input-vo-keyboard" to "no",
            "profile" to "fast",
            "vo" to "null",
            "hwdec" to "no",
            "audio" to "no",
            "aid" to "no",
            "sid" to "no",
            "pause" to "yes",
            "idle" to "yes",
            "keep-open" to "always",
            "hr-seek" to "yes",
            // The isolated service runs at Android background priority. Two decode
            // threads keep 720p thumbnail generation fast while the scheduler still
            // gives the foreground player/UI precedence.
            "vd-lavc-threads" to "3",
            "vd-lavc-fast" to "yes",
            "vd-lavc-skiploopfilter" to "all",
            "vd-lavc-software-fallback" to "1",
            "cache" to "yes",
            "demuxer-max-bytes" to "2MiB",
            "demuxer-max-back-bytes" to "0",
            "demuxer-readahead-secs" to "2",
            "network-timeout" to "15",
            "user-agent" to BROWSER_USER_AGENT,
            "ytdl" to "no",
            "sws-scaler" to "fast-bilinear",
            "sws-allow-zimg" to "no",
            "screenshot-high-bit-depth" to "no",
            // Raw Android bitmaps have no embedded colour profile. Force mpv to
            // convert screenshots to sRGB instead of returning wide-gamut values
            // that Android would otherwise display as flat/undersaturated.
            "screenshot-tag-colorspace" to "no"
        )
        val rejected = mutableListOf<String>()
        options.forEach { (name, value) ->
            val result = instance.setOptionString(name, value)
            if (result < 0) rejected += "$name=$result"
        }
        return if (rejected.isEmpty()) {
            "options=${options.size} accepted=${options.size} rejected=0"
        } else {
            "options=${options.size} accepted=${options.size - rejected.size} " +
                "rejected=${rejected.size} [${rejected.joinToString()}]"
        }
    }

    internal fun decodeThumbnail(payload: ByteArray): Bitmap? {
        if (payload.size < RAW_FRAME_HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val width = buffer.int
        val height = buffer.int
        val stride = buffer.int
        val pixelBytes = width.toLong() * height.toLong() * 4L
        if (width <= 0 || height <= 0 || stride != width * 4 ||
            pixelBytes > Int.MAX_VALUE || payload.size != RAW_FRAME_HEADER_BYTES + pixelBytes.toInt()
        ) return null
        val decoded = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        decoded.copyPixelsFromBuffer(buffer)
        if (decoded.width == THUMBNAIL_WIDTH && decoded.height == THUMBNAIL_HEIGHT) {
            return decoded
        }
        return runCatching {
            Bitmap.createScaledBitmap(decoded, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT, true)
        }.also {
            if (!decoded.isRecycled) decoded.recycle()
        }.getOrNull()
    }

    private suspend fun waitForRawFrame(instance: LibMpvSession): ByteArray? {
        var frame: ByteArray? = null
        waitUntil(RAW_FRAME_TIMEOUT_MS) {
            frame = instance.screenshotRaw()
            frame != null || fileEnded
        }
        return frame
    }

    private suspend fun waitForSeek(
        instance: LibMpvSession,
        previousRestart: Long,
        targetPositionSeconds: Double
    ): Boolean = waitUntil(SEEK_SETTLE_TIMEOUT_MS) {
        if (fileEnded) return@waitUntil true
        // initDetailed has no event thread. Pump the bridge's queued lifecycle
        // callbacks while waiting for a genuinely decoded post-seek frame.
        val logs = sanitizeNativeLogs(instance.drainDiagnosticLogs())
        if (logs.isNotBlank()) recentSeekLogs = logs
        val current = instance.getPropertyDouble("time-pos") ?: return@waitUntil false
        val seeking = instance.getPropertyBoolean("seeking") == true
        ThumbnailTimelinePolicy.seekReady((targetPositionSeconds * 1_000).toLong(),
            (current * 1_000).toLong(), seeking, playbackRestartGeneration > previousRestart)
    }

    private suspend fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!closed && SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            delay(POLL_INTERVAL_MS)
        }
        return !closed && condition()
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> playbackRestartGeneration++
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> fileLoaded = true
            MPVLib.MpvEvent.MPV_EVENT_END_FILE,
            MPVLib.MpvEvent.MPV_EVENT_SHUTDOWN -> fileEnded = true
        }
    }

    override fun eventProperty(property: String) = Unit
    override fun eventProperty(property: String, value: Long) = Unit
    override fun eventProperty(property: String, value: Double) = Unit
    override fun eventProperty(property: String, value: Boolean) = Unit
    override fun eventProperty(property: String, value: String) = Unit

    override fun close() {
        if (closed) return
        closed = true
        val instance = mpv
        mpv = null
        if (instance != null) {
            runCatching { instance.removeObserver(this) }
            runCatching { instance.command(arrayOf("stop")) }
            runCatching { instance.destroy() }
        }
    }

    private fun errorSummary(error: Throwable): String =
        "${error::class.java.simpleName}: ${error.message.orEmpty()}".take(1_200)

    private fun commandFailure(instance: LibMpvSession, command: String, result: Int): String {
        val nativeLogs = sanitizeNativeLogs(instance.drainDiagnosticLogs())
        return buildString {
            append("command=$command code=$result")
            if (nativeLogs.isNotBlank()) append(" logs=$nativeLogs")
        }
    }

    private fun sanitizeNativeLogs(value: String): String = value
        .replace(Regex("https?://[^\\s|]+")) { match ->
            com.saab.tv.data.player.PlaybackDiagnostics.describeUrl(match.value)
        }
        .replace('\n', ' ')
        .replace('\r', ' ')
        .take(1_200)

    private companion object {
        const val THUMBNAIL_WIDTH = 320
        const val THUMBNAIL_HEIGHT = 180
        const val RAW_FRAME_HEADER_BYTES = 12
        const val LOAD_TIMEOUT_MS = 30_000L
        const val SEEK_SETTLE_TIMEOUT_MS = 8_000L
        const val RAW_FRAME_TIMEOUT_MS = 7_000L
        const val POLL_INTERVAL_MS = 25L
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}

/** Thin seam around the JNI library so lifecycle/error paths can be tested without a TV codec. */
internal interface LibMpvSession {
    fun addObserver(observer: MPVLib.EventObserver)
    fun removeObserver(observer: MPVLib.EventObserver)
    fun setOptionString(name: String, value: String): Int
    fun initDetailed(): String?
    fun commandDetailed(args: Array<String>): Int
    fun command(args: Array<String>)
    fun setPropertyBoolean(name: String, value: Boolean)
    fun getPropertyString(name: String): String?
    fun getPropertyDouble(name: String): Double?
    fun getPropertyBoolean(name: String): Boolean?
    fun drainDiagnosticLogs(): String
    fun screenshotRaw(): ByteArray?
    fun screenshotRawError(): String
    fun destroy()
}

private class NativeLibMpvSession(private val instance: MPVLib) : LibMpvSession {
    override fun addObserver(observer: MPVLib.EventObserver) = instance.addObserver(observer)
    override fun removeObserver(observer: MPVLib.EventObserver) = instance.removeObserver(observer)
    override fun setOptionString(name: String, value: String) = instance.setOptionString(name, value)
    override fun initDetailed() = instance.initDetailed()
    override fun commandDetailed(args: Array<String>) = instance.commandDetailed(args)
    override fun command(args: Array<String>) { instance.command(args) }
    override fun setPropertyBoolean(name: String, value: Boolean) = instance.setPropertyBoolean(name, value)
    override fun getPropertyString(name: String) = instance.getPropertyString(name)
    override fun getPropertyDouble(name: String) = instance.getPropertyDouble(name)
    override fun getPropertyBoolean(name: String) = instance.getPropertyBoolean(name)
    override fun drainDiagnosticLogs() = instance.drainDiagnosticLogs()
    override fun screenshotRaw() = instance.screenshotRaw()
    override fun screenshotRawError() = instance.screenshotRawError()
    override fun destroy() = instance.destroy()
}
