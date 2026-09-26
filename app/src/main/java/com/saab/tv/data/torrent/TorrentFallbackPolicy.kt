package com.saab.tv.data.torrent

import com.saab.tv.data.model.stremio.Stream
import java.net.URLDecoder
import java.util.Locale

object TorrentFallbackPolicy {
    fun buildAttempts(
        selected: Stream,
        rankedStreams: List<Stream>,
        maxRetries: Int = 2
    ): List<Stream> {
        val maxAttempts = maxRetries.coerceAtLeast(0) + 1
        return (listOf(selected) + rankedStreams)
            .asSequence()
            .filter(::isTorrent)
            .distinctBy(::torrentFileIdentity)
            .take(maxAttempts)
            .toList()
    }

    private fun isTorrent(stream: Stream): Boolean =
        !stream.infoHash.isNullOrBlank() || stream.url?.startsWith("magnet:", ignoreCase = true) == true

    private fun torrentFileIdentity(stream: Stream): String {
        val hash = stream.infoHash?.trim()?.lowercase(Locale.ROOT)
            ?: stream.url?.let { magnet ->
                val decoded = runCatching {
                    URLDecoder.decode(magnet, Charsets.UTF_8.name())
                }.getOrDefault(magnet)
                INFO_HASH.find(decoded)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT)
            }
            ?: stream.url.orEmpty().lowercase(Locale.ROOT)
        return "$hash:${stream.fileIdx ?: -1}"
    }

    private val INFO_HASH = Regex("""(?i)(?:[?&])xt=urn:btih:([a-z0-9]+)""")
}
