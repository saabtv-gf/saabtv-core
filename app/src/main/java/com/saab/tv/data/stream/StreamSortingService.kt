package com.saab.tv.data.stream

import com.saab.tv.data.model.ParsedStreamInfo
import com.saab.tv.data.model.StreamQuality
import com.saab.tv.data.model.stremio.Stream
import java.util.Locale
import kotlin.math.ln
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamSortingService @Inject constructor() {

    private data class RankedStream(
        val stream: Stream,
        val info: ParsedStreamInfo,
        val originalIndex: Int,
        val comparableSizeBytes: Long?,
        val smartSizeBucket: Long?
    )

    fun sortAndFilter(
        streams: List<Stream>,
        enabledQualities: Set<StreamQuality>,
        excludePhrases: List<String>,
        addonSortOrders: Map<String, Int>,
        sortBy: String = "quality",
        maxSizeGb: Int = 0,
        excludedFormats: Set<String> = emptySet(),
        seasonPackTorrentsOnly: Boolean = false,
        hideZeroSeeders: Boolean = false,
        preferredAudioLanguages: List<String> = emptyList()
    ): List<Stream> {
        val lowerPhrases = excludePhrases
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
        val maxSizeBytes = if (maxSizeGb > 0) maxSizeGb.toLong() * BYTES_PER_GB else Long.MAX_VALUE
        val languagePreferences = normalizeLanguagePreferences(preferredAudioLanguages)

        return streams
            .mapIndexed { index, stream ->
                val info = StreamParser.parse(stream)
                val comparableSize = comparableSizeBytes(stream, info)
                RankedStream(
                    stream = stream,
                    info = info,
                    originalIndex = index,
                    comparableSizeBytes = comparableSize,
                    smartSizeBucket = comparableSize?.let(::smartSizeBucket)
                )
            }
            .filter { it.info.quality in enabledQualities }
            .filter { ranked ->
                if (lowerPhrases.isEmpty()) return@filter true
                val text = StreamParser.combinedText(ranked.stream).lowercase(Locale.ROOT)
                lowerPhrases.none(text::contains)
            }
            .filter { ranked ->
                if (maxSizeGb <= 0) return@filter true
                val sizeBytes = ranked.info.sizeBytes ?: return@filter true
                sizeBytes <= maxSizeBytes
            }
            .filter { ranked ->
                excludedFormats.isEmpty() || excludedFormats.intersect(ranked.info.formats).isEmpty()
            }
            .filter { ranked ->
                !seasonPackTorrentsOnly ||
                    !isTorrent(ranked.stream) ||
                    isCompleteSeasonTorrent(ranked.stream)
            }
            .filter { ranked -> !TorBoxAvailabilityPolicy.remove(ranked.stream) }
            .filter { ranked -> !hideZeroSeeders || ranked.stream.torBoxChecked || ranked.stream.torBoxCached == true || ranked.info.seeds != 0 }
            .sortedWith(buildComparator(addonSortOrders, sortBy, languagePreferences))
            .map(RankedStream::stream)
    }

    private fun buildComparator(
        addonSortOrders: Map<String, Int>,
        sortBy: String,
        languagePreferences: List<String>
    ): Comparator<RankedStream> {
        val qualityDescending = compareByDescending<RankedStream> { it.info.quality.sortOrder }
        val smallerKnownSizeFirst = compareBy<RankedStream> { it.comparableSizeBytes == null }
            .thenBy { it.comparableSizeBytes ?: Long.MAX_VALUE }
        val seedersDescending = compareByDescending<RankedStream> { it.info.seeds ?: -1 }
        val smartSizeBandFirst = compareBy<RankedStream> { it.smartSizeBucket == null }
            .thenBy { it.smartSizeBucket ?: Long.MAX_VALUE }
        val addonPreference = compareBy<RankedStream> {
            addonSortOrders[it.stream.addonTransportUrl] ?: Int.MAX_VALUE
        }
        val preferredProvider = compareBy<RankedStream> {
            StreamSourceProviderResolver.selectionRank(it.stream)
        }
        val scoreDescending = compareByDescending<RankedStream> {
            StreamScoreCalculator.score(it.stream, languagePreferences)
        }

        val selectedComparator = when (sortBy) {
            SMART_TCL_C755 -> scoreDescending
                // The exact score is the contract presented in the source panel.
                // Seeder count is the first deterministic tie-break.
                .then(seedersDescending)
                .thenBy { audioLanguageRank(it.stream, languagePreferences) }
                .then(preferredProvider)
                .thenByDescending(::tclC755CompatibilityScore)
                .then(smartSizeBandFirst)
                .then(smallerKnownSizeFirst)
                .thenByDescending { preferredLanguageCount(it.stream, languagePreferences) }
            "size" -> smallerKnownSizeFirst
                .then(qualityDescending)
                .then(seedersDescending)
            "seeds" -> seedersDescending
                .then(qualityDescending)
                .then(smallerKnownSizeFirst)
            else -> qualityDescending
                .then(smallerKnownSizeFirst)
                .then(seedersDescending)
        }

        return selectedComparator
            .then(addonPreference)
            .thenBy(RankedStream::originalIndex)
    }

    private fun audioLanguageRank(stream: Stream, preferences: List<String>): Int {
        val detected = StreamParser.extractAudioLanguages(StreamParser.combinedText(stream))
        if (detected.isEmpty()) return preferences.size
        val bestPreference = preferences.indexOfFirst(detected::contains)
        return if (bestPreference >= 0) bestPreference else preferences.size + 1
    }

    private fun preferredLanguageCount(stream: Stream, preferences: List<String>): Int {
        val detected = StreamParser.extractAudioLanguages(StreamParser.combinedText(stream))
        return preferences.count(detected::contains)
    }

    private fun availabilityScore(ranked: RankedStream): Double {
        val seeders = ranked.info.seeds
            ?: return if (isTorrent(ranked.stream)) 12.0 else 20.0
        if (seeders <= 0) return -30.0
        // Log scaling keeps a 900-vs-500 advantage meaningful without allowing
        // extreme counts to overwhelm quality and the size band.
        return 20.0 * ln(seeders + 1.0) / ln(201.0)
    }

    private fun tclC755CompatibilityScore(ranked: RankedStream): Int {
        val formats = ranked.info.formats
        var score = when {
            "hevc" in formats || "av1" in formats -> 4
            "h264" in formats -> 3
            else -> 2
        }
        score += when {
            "dv" in formats -> 12
            "hdr10plus" in formats -> 10
            "hdr10" in formats -> 8
            "hlg" in formats -> 6
            "hdr" in formats -> 5
            else -> 0
        }
        score += when {
            "dolby" in formats -> 2
            "dts" in formats -> 1
            else -> 0
        }
        if ("3d" in formats) score -= 6
        return score
    }

    private fun comparableSizeBytes(stream: Stream, info: ParsedStreamInfo): Long? {
        stream.behaviorHints?.videoSize?.takeIf { it > 0L }?.let { return it }
        val reportedSize = info.sizeBytes ?: return null
        if (!isCompleteSeasonTorrent(stream)) return reportedSize

        val episodeCount = extractPackEpisodeCount(StreamParser.combinedText(stream))
        return if (episodeCount != null && episodeCount > 1) reportedSize / episodeCount else null
    }

    /**
     * Smart sorting treats sizes within a ±5 GB window around each 10 GB step as
     * comparable. Seeder health, language, provider, and playback compatibility
     * decide order inside a band; materially smaller bands still rank first.
     */
    private fun smartSizeBucket(sizeBytes: Long): Long {
        return (sizeBytes + SMART_SIZE_HALF_WINDOW_BYTES) / SMART_SIZE_WINDOW_BYTES
    }

    private fun extractPackEpisodeCount(text: String): Int? {
        explicitEpisodeCount.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { count ->
            if (count in 2..100) return count
        }
        episodeRange.find(text)?.let { match ->
            val first = match.groupValues[1].toIntOrNull() ?: return@let
            val last = match.groupValues[2].toIntOrNull() ?: return@let
            val count = last - first + 1
            if (count in 2..100) return count
        }
        return null
    }

    private fun normalizeLanguagePreferences(languages: List<String>): List<String> {
        return languages.mapNotNull(::canonicalLanguage).distinct().take(3)
    }

    companion object {
        const val SMART_TCL_C755 = "smart_tcl_c755"
        private const val BYTES_PER_GB = 1_073_741_824L
        private const val SMART_SIZE_HALF_WINDOW_BYTES = 5L * BYTES_PER_GB
        private const val SMART_SIZE_WINDOW_BYTES = 2L * SMART_SIZE_HALF_WINDOW_BYTES

        private val completeCollectionPattern = Regex(
            """(?i)\b(?:complete|full)[\s._-]+(?:season|series|collection)\b|\bseason[\s._-]+pack\b"""
        )
        private val seasonRangePattern = Regex("""(?i)\bS\d{1,2}[\s._-]*[-–][\s._-]*S?\d{1,2}\b""")
        private val seasonTokenPattern = Regex(
            """(?i)\bS\d{1,2}\b(?![\s._-]*(?:E|EP|EPISODE)[\s._-]*\d)"""
        )
        private val seasonWordPattern = Regex(
            """(?i)\bSEASON[\s._-]*\d{1,2}\b(?![\s._-]*(?:E|EP|EPISODE)[\s._-]*\d)"""
        )
        private val explicitEpisodeCount = Regex("""(?i)\b(\d{1,3})\s*episodes?\b""")
        private val episodeRange = Regex("""(?i)\bE(?:P)?\s*(\d{1,3})\s*[-–]\s*(?:E(?:P)?\s*)?(\d{1,3})\b""")

        fun isTorrent(stream: Stream): Boolean =
            !stream.infoHash.isNullOrBlank() || stream.url?.startsWith("magnet:", ignoreCase = true) == true

        fun isCompleteSeasonTorrent(stream: Stream): Boolean {
            if (!isTorrent(stream)) return false
            val text = StreamParser.combinedText(stream)
            return completeCollectionPattern.containsMatchIn(text) ||
                seasonRangePattern.containsMatchIn(text) ||
                seasonTokenPattern.containsMatchIn(text) ||
                seasonWordPattern.containsMatchIn(text)
        }

        fun smartLanguagePreferences(
            primary: String?,
            secondary: String?,
            tertiary: String?
        ): List<String> =
            listOfNotNull(primary, secondary, tertiary)
                .mapNotNull(::canonicalLanguage)
                .distinct()
                .take(3)

        private fun canonicalLanguage(raw: String?): String? {
            val value = raw?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')
                ?.takeIf { it.isNotEmpty() } ?: return null
            return when (value) {
                "english", "eng" -> "en"
                "hindi", "hin" -> "hi"
                "telugu", "tel" -> "te"
                "tamil", "tam" -> "ta"
                "malayalam", "mal" -> "ml"
                "kannada", "kan" -> "kn"
                "bengali", "bangla", "ben" -> "bn"
                "spanish", "spa" -> "es"
                "french", "fra", "fre" -> "fr"
                "german", "deu", "ger" -> "de"
                "italian", "ita" -> "it"
                "portuguese", "por" -> "pt"
                "russian", "rus" -> "ru"
                "japanese", "jpn" -> "ja"
                "chinese", "mandarin", "zho", "chi" -> "zh"
                "arabic", "ara" -> "ar"
                else -> value
            }
        }

        fun parseEnabledQualities(qualitiesString: String): Set<StreamQuality> {
            if (qualitiesString.isBlank()) return StreamQuality.entries.toSet()
            return qualitiesString.split(",")
                .mapNotNull { StreamQuality.fromKey(it.trim()) }
                .toSet()
        }

        fun parseExcludePhrases(phrasesString: String): List<String> {
            if (phrasesString.isBlank()) return emptyList()
            return phrasesString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }

        fun parseExcludedFormats(formatsString: String): Set<String> {
            if (formatsString.isBlank()) return emptySet()
            return formatsString.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
    }
}
