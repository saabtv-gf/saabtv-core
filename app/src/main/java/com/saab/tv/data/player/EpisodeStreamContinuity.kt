package com.saab.tv.data.player

import com.saab.tv.data.model.stremio.Stream
import java.net.URLDecoder
import java.util.Locale

object EpisodeStreamContinuity {
    fun findContinuation(previous: Stream?, candidates: List<Stream>): Stream? {
        if (previous == null) return null
        val playableCandidates = candidates.filter(::isPlayable)
        val previousTorrent = torrentIdentity(previous)

        if (previousTorrent != null) {
            playableCandidates.firstOrNull { torrentIdentity(it) == previousTorrent }?.let { return it }
        }

        val previousBingeGroup = normalize(previous.behaviorHints?.bingeGroup)
        val previousAddon = normalize(previous.addonTransportUrl)
        if (previousBingeGroup != null) {
            playableCandidates.firstOrNull { candidate ->
                normalize(candidate.behaviorHints?.bingeGroup) == previousBingeGroup &&
                    (previousAddon == null || normalize(candidate.addonTransportUrl) == previousAddon)
            }?.let { return it }
        }

        val release = StreamIdentity.episodeRelease(previous)
        if (release != null) return playableCandidates.firstOrNull {
            StreamIdentity.episodeRelease(it) == release &&
                (previousAddon == null || normalize(it.addonTransportUrl) == previousAddon)
        }

        return null
    }

    fun torrentIdentity(stream: Stream): String? {
        normalize(stream.infoHash)?.let { return it }
        val magnet = stream.url?.takeIf { it.startsWith("magnet:", ignoreCase = true) } ?: return null
        val decoded = runCatching { URLDecoder.decode(magnet, Charsets.UTF_8.name()) }.getOrDefault(magnet)
        return magnetInfoHash.find(decoded)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT)
    }

    private fun isPlayable(stream: Stream): Boolean =
        !stream.url.isNullOrBlank() || !stream.infoHash.isNullOrBlank()

    private fun normalize(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.lowercase(Locale.ROOT)

    private val magnetInfoHash = Regex("""(?i)(?:[?&])xt=urn:btih:([a-z0-9]+)""")
}
