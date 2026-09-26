package com.saab.tv.data.cache

import com.saab.tv.ui.player.base.PlayerSourceOption

/**
 * Chooses a lightweight secondary stream for seek-preview extraction. The selected
 * playback stream is never changed. A 320x180 preview gains no useful detail from
 * decoding a 4K remux, so AVC SDR sources with small files and modest resolution
 * are deliberately preferred.
 */
object ThumbnailSourceSelector {
    data class Selection(
        val source: PlayerSourceOption,
        val reason: String
    )

    fun select(
        playbackUrl: String,
        sources: List<PlayerSourceOption>
    ): Selection? {
        val playback = sources.firstOrNull { it.url == playbackUrl }
        val playbackEdition = editionSignature(playback?.searchableText.orEmpty())
        val candidates = sources
            .asSequence()
            .filter { TorBoxStreamUrlPolicy.isRemoteHttpStream(it.url) }
            .filterNot(::isUnsafePreviewSource)
            .filter { editionIsCompatible(playbackEdition, editionSignature(it.searchableText)) }
            .distinctBy { it.url }
            .toList()

        // A tiny file is useless if its torrent cannot supply the random ranges
        // required by the thumbnail decoder. Only compare size after candidates
        // have passed an availability floor. Explicitly cached/instant sources are
        // reliable without live seeders; otherwise require a known healthy swarm.
        val reliableCandidates = candidates.filter(::hasReliableAvailability)
        // Prefer cheap-to-decode AVC SDR at 720p or below, then choose the least
        // file size inside that fast pool. A slightly smaller HEVC/AV1 file can be
        // materially slower to decode on 32-bit TV hardware.
        val fastDecodeCandidates = reliableCandidates.filter {
            (it.qualityHeight ?: Int.MAX_VALUE) <= 720 && codecPenalty(it) == 0 && !isHdr(it)
        }
        val lightweightCandidates = reliableCandidates.filter {
            (it.qualityHeight ?: Int.MAX_VALUE) <= 720 && !isHdr(it)
        }
        val selected = fastDecodeCandidates
            .ifEmpty { lightweightCandidates }
            .ifEmpty { reliableCandidates }
            .minWithOrNull(sourceComparator)
            ?: playback?.takeIf {
                TorBoxStreamUrlPolicy.isRemoteHttpStream(it.url) && !isUnsafePreviewSource(it)
            }
            ?: return null
        return Selection(
            source = selected,
            reason = buildString {
                append("quality=${selected.qualityHeight ?: 0}p")
                append(" codec=${codecLabel(selected)}")
                append(" range=${if (isHdr(selected)) "HDR" else "SDR"}")
                append(" size=${selected.videoSize ?: -1}")
                append(" seeders=${selected.seeders ?: -1}")
                append(" alternate=${selected.url != playbackUrl}")
            }
        )
    }

    private val sourceComparator = compareBy<PlayerSourceOption>(
        { if (it.videoSize != null && it.videoSize > 0L) 0 else 1 },
        { it.videoSize?.takeIf { size -> size > 0L } ?: Long.MAX_VALUE },
        { cachedPenalty(it) },
        { resolutionPenalty(it.qualityHeight) },
        { -(it.seeders ?: -1) }
    )

    private fun hasReliableAvailability(source: PlayerSourceOption): Boolean =
        isExplicitlyCached(source) || (source.seeders ?: 0) >= MIN_RELIABLE_SEEDERS

    private fun isExplicitlyCached(source: PlayerSourceOption): Boolean {
        val text = source.searchableText.lowercase()
        return "cached" in text && "uncached" !in text && "not cached" !in text ||
            "instant" in text || "⚡" in text
    }

    private fun cachedPenalty(source: PlayerSourceOption): Int {
        val text = source.searchableText.lowercase()
        return when {
            "uncached" in text || "download required" in text -> 2
            "cached" in text || "instant" in text || "⚡" in text ||
                TorBoxStreamUrlPolicy.isTorBoxUrl(source.url) -> 0
            else -> 1
        }
    }

    private fun codecPenalty(source: PlayerSourceOption): Int {
        val formats = source.formats.mapTo(hashSetOf()) { it.lowercase() }
        val text = source.searchableText.lowercase()
        return when {
            "h264" in formats || Regex("""\b(?:h\.?264|x\.?264|avc)\b""").containsMatchIn(text) -> 0
            "hevc" in formats || Regex("""\b(?:hevc|h\.?265|x\.?265)\b""").containsMatchIn(text) -> 2
            "av1" in formats || Regex("""\bav1\b""").containsMatchIn(text) -> 3
            else -> 1
        }
    }

    private fun codecLabel(source: PlayerSourceOption): String = when (codecPenalty(source)) {
        0 -> "AVC"
        2 -> "HEVC"
        3 -> "AV1"
        else -> "unknown"
    }

    private fun resolutionPenalty(height: Int?): Int = when (height) {
        480 -> 0
        720 -> 1
        1080 -> 2
        null -> 3
        2160 -> 4
        else -> if (height in 1..719) 0 else 3
    }

    private fun isHdr(source: PlayerSourceOption): Boolean {
        val formats = source.formats.mapTo(hashSetOf()) { it.lowercase() }
        if (formats.any { it == "dv" || it == "hdr" || it == "hdr10" || it == "hdr10plus" || it == "hlg" }) {
            return true
        }
        return Regex("""(?i)\b(?:dolby\s*vision|dovi|dv|hdr(?:10(?:\+|plus)?)?|hlg)\b""")
            .containsMatchIn(source.searchableText)
    }

    private fun isUnsafePreviewSource(source: PlayerSourceOption): Boolean {
        if (source.qualityHeight == 0) return true
        val text = source.searchableText
        return Regex("""(?i)\b(?:cam|camrip|hdcam|telesync|telecine|3d|sbs|half.?sbs|hou)\b""")
            .containsMatchIn(text)
    }

    private fun editionSignature(text: String): Set<String> = buildSet {
        val normalized = text.lowercase()
        EDITION_MARKERS.forEach { (key, pattern) ->
            if (pattern.containsMatchIn(normalized)) add(key)
        }
    }

    private fun editionIsCompatible(playback: Set<String>, candidate: Set<String>): Boolean =
        playback == candidate

    private val PlayerSourceOption.searchableText: String
        get() = listOfNotNull(name, title, description, fileName, label).joinToString(" ")

    private val EDITION_MARKERS = mapOf(
        "extended" to Regex("""\bextended\b"""),
        "directors-cut" to Regex("""\bdirector'?s[ ._-]*cut\b"""),
        "unrated" to Regex("""\bunrated\b"""),
        "theatrical" to Regex("""\btheatrical\b"""),
        "imax" to Regex("""\bimax\b"""),
        "remastered" to Regex("""\bremaster(?:ed)?\b""")
    )

    private const val MIN_RELIABLE_SEEDERS = 5
}
