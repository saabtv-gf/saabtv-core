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

    fun isVp9(codec: String): Boolean = codec.lowercase().let { "vp9" in it || "vp09" in it }

    /** Resolution first; hardware VP9 wins only against the same resolution. */
    fun rank(variants: List<TrailerPlaybackVariant>): List<TrailerPlaybackVariant> =
        variants.distinctBy { it.videoUrl to it.audioUrl }.sortedWith(
            compareByDescending<TrailerPlaybackVariant> { it.height }.thenBy {
                when {
                    isVp9(it.codec) && it.hardwareDecoder.isNotBlank() -> 0
                    it.codec.contains("avc", true) || it.codec.contains("h264", true) -> 1
                    else -> 2
                }
            })

    internal fun hardwareVp9Candidates(variants: List<TrailerPlaybackVariant>,
        hardwareDecoder: (TrailerPlaybackVariant) -> String?): List<TrailerPlaybackVariant> = variants.mapNotNull {
        if (!isVp9(it.codec)) it
        else hardwareDecoder(it)?.takeIf(String::isNotBlank)?.let { decoder -> it.copy(hardwareDecoder = decoder) }
    }

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
