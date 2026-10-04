package com.saab.tv.data.cache

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.Closeable

/**
 * Isolated fallback for devices or containers where libmpv cannot produce its
 * raw image2 output. The service process contains any platform codec failure.
 */
internal class PlatformThumbnailEngine : Closeable {
    private var retriever: MediaMetadataRetriever? = null
    var lastError: String? = null
        private set

    fun open(mediaUrl: String): Boolean {
        if (mediaUrl.isBlank()) return false
        lastError = null
        return try {
            val instance = MediaMetadataRetriever()
            instance.setDataSource(mediaUrl, mapOf("User-Agent" to BROWSER_USER_AGENT))
            retriever = instance
            true
        } catch (error: Throwable) {
            lastError = errorSummary(error)
            close()
            false
        }
    }

    fun capture(positionMs: Long, outputWidth: Int = THUMBNAIL_WIDTH): Bitmap? {
        val instance = retriever ?: return null
        lastError = null
        return try {
            val positionUs = positionMs.coerceAtLeast(0L) * 1_000L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                instance.getScaledFrameAtTime(
                    positionUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    outputWidth.coerceIn(THUMBNAIL_WIDTH, 960),
                    (outputWidth.coerceIn(THUMBNAIL_WIDTH, 960) * 9 / 16).coerceAtLeast(1)
                ).also { if (it == null) lastError = "decoder returned no frame" }
            } else {
                val original = instance.getFrameAtTime(
                    positionUs,
                    MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: return null
                Bitmap.createScaledBitmap(
                    original,
                    outputWidth.coerceIn(THUMBNAIL_WIDTH, 960),
                    (outputWidth.coerceIn(THUMBNAIL_WIDTH, 960) * 9 / 16).coerceAtLeast(1),
                    false
                ).also { scaled ->
                    if (scaled !== original && !original.isRecycled) original.recycle()
                }
            }
        } catch (error: Throwable) {
            lastError = errorSummary(error)
            null
        }
    }

    override fun close() {
        val instance = retriever
        retriever = null
        if (instance != null) runCatching { instance.release() }
    }

    private fun errorSummary(error: Throwable): String =
        "${error::class.java.simpleName}: ${error.message.orEmpty()}".take(240)

    private companion object {
        const val THUMBNAIL_WIDTH = 320
        const val THUMBNAIL_HEIGHT = 180
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
