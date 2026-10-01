package com.saab.tv.data.cache

import com.saab.tv.ui.player.base.PlayerSourceOption
import java.security.MessageDigest
import kotlin.math.abs

/** Preview timestamps and file identity describe the chosen thumbnail timeline. */
object ThumbnailTimelinePolicy {
    const val MAX_CAPTURE_ERROR_MS = 100L
    private const val SOURCE_MARKER = "|timeline:"
    fun cacheId(contentId: String, mediaUrl: String, source: PlayerSourceOption?): String {
        val identity = if (!source?.infoHash.isNullOrBlank() &&
            (source!!.fileIdx >= 0 || source.fileName.isNotBlank())) {
            "${source.infoHash!!.lowercase()}:${source.fileIdx}:${source.fileName}"
        } else mediaUrl
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return contentId + SOURCE_MARKER + digest
    }
    fun belongsTo(cacheId: String, contentId: String) =
        cacheId == contentId || cacheId.startsWith(contentId + SOURCE_MARKER)
    fun isSourceScoped(contentId: String) = contentId.contains(SOURCE_MARKER)
    fun gridPosition(positionMs: Long, intervalSeconds: Int): Long {
        val interval = intervalSeconds.coerceAtLeast(1) * 1_000L
        val safe = positionMs.coerceAtLeast(0)
        val base = safe / interval * interval
        return if (safe - base >= interval / 2 && base <= Long.MAX_VALUE - interval) base + interval else base
    }
    fun captureMatches(targetMs: Long, actualMs: Long?) = targetMs >= 0 && actualMs != null &&
        actualMs >= 0 && abs(actualMs - targetMs) <= MAX_CAPTURE_ERROR_MS
    fun seekReady(targetMs: Long, actualMs: Long?, seeking: Boolean, restarted: Boolean) =
        !seeking && restarted && captureMatches(targetMs, actualMs)
}
