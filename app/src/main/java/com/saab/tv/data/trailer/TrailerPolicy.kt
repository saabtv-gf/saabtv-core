package com.saab.tv.data.trailer

import java.net.URI
import java.net.URLDecoder
import com.saab.tv.data.model.stremio.MetaItem

/** Cinemeta supplies YouTube IDs, not a 4K media URL. Never accept arbitrary hosts. */
object TrailerPolicy {
    /** Repeating a rejected/expired URL cannot fix permission or range errors. */
    fun terminalHttpStatus(code: Int): Boolean = code in listOf(400, 401, 403, 404, 410, 416)
    fun manifestHeight(xml: String): Int = Regex("height=[\"'](\\d+)[\"']")
        .findAll(xml).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0
    private val idPattern = Regex("^[A-Za-z0-9_-]{11}$")

    fun videoId(raw: String): String? {
        val value = raw.trim()
        if (idPattern.matches(value)) return value
        return runCatching {
            val uri = URI(if (value.contains("://")) value else "https://$value")
            if (uri.scheme?.lowercase() !in listOf("http", "https")) return null
            val host = uri.host?.lowercase() ?: return null
            val path = uri.path.orEmpty().trim('/').split('/')
            val candidate = when {
                host == "youtu.be" -> path.firstOrNull()
                host == "youtube.com" || host.endsWith(".youtube.com") ||
                    host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") -> {
                    if (path.firstOrNull() in listOf("embed", "shorts", "live")) path.getOrNull(1)
                    else uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("v=") }
                        ?.substringAfter("=")?.let { URLDecoder.decode(it, "UTF-8") }
                }
                else -> null
            }
            candidate?.takeIf(idPattern::matches)
        }.getOrNull()
    }

    fun rank(variants: List<TrailerPlaybackVariant>): List<TrailerPlaybackVariant> =
        variants.distinctBy { it.videoUrl to it.audioUrl }.sortedByDescending { it.height }

    fun metadataTrailer(meta: MetaItem): Pair<String, String>? {
        for (stream in meta.trailerStreams.orEmpty()) {
            val id = sequenceOf(stream.ytId, stream.externalUrl, stream.url)
                .filterNotNull().mapNotNull(::videoId).firstOrNull() ?: continue
            return id to (stream.title?.takeIf { it.isNotBlank() } ?: "${meta.name} Trailer")
        }
        val trailers = meta.trailers.orEmpty().sortedBy { !it.type.equals("Trailer", true) }
        return trailers.firstNotNullOfOrNull { trailer ->
            trailer.source?.let(::videoId)?.let { it to "${meta.name} Trailer" }
        }
    }
}
