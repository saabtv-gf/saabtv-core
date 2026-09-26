package com.saab.tv.data.stream

import com.saab.tv.data.model.StreamQuality
import com.saab.tv.data.model.stremio.Stream
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Produces a compact, explainable source-quality score for the source picker.
 *
 * Audio language contributes only when the user configured a priority and the
 * addon advertised a matching language. Missing metadata is not penalized.
 */
object StreamScoreCalculator {
    private const val BYTES_PER_GB = 1_073_741_824.0

    /**
     * Full precision score used for ordering. Keeping this as a Double prevents
     * different seeder counts from collapsing into the same whole-number rank.
     */
    fun score(stream: Stream, preferredLanguages: List<String> = emptyList()): Double {
        val info = StreamParser.parse(stream)
        val secondaryScore = availabilityScore(stream, info.seeds) +
            sizeEfficiencyScore(info.sizeBytes) +
            playbackTechnologyScore(info.formats) +
            providerScore(stream) +
            metadataConfidenceScore(info.quality, info.sizeBytes, info.seeds) +
            languagePreferenceScore(stream, preferredLanguages)
        val normalizedSecondary = secondaryScore.coerceIn(0.0, MAX_SECONDARY_SCORE) /
            MAX_SECONDARY_SCORE
        // Inside a resolution tier, a torrent that actually reports seeders must
        // always outrank one whose availability is unknown. Resolution bands stay
        // non-overlapping, preserving the higher-resolution-first contract.
        val requiresSeeders = StreamSourceProviderResolver.requiresSeederMetadata(stream)
        val tierPosition = when {
            !requiresSeeders -> normalizedSecondary
            info.seeds != null -> KNOWN_SEEDER_BAND_START +
                normalizedSecondary * (1.0 - KNOWN_SEEDER_BAND_START)
            else -> normalizedSecondary * UNKNOWN_SEEDER_BAND_END
        }
        val score = qualityTierBase(info.quality) +
            tierPosition * qualityTierWidth(info.quality)
        return score.coerceIn(0.0, 100.0)
    }

    fun scorePercent(stream: Stream, preferredLanguages: List<String> = emptyList()): Int {
        return score(stream, preferredLanguages).roundToInt().coerceIn(0, 100)
    }

    fun scoreLabel(stream: Stream, preferredLanguages: List<String> = emptyList()): String =
        String.format(Locale.US, "%.2f", score(stream, preferredLanguages))

    /**
     * Canonical ordering used wherever a user can choose or auto-play a source.
     *
     * The exact score shown in the source panel is always the primary key. Raw
     * seeders are deliberately the first tie-break so two sources whose labels
     * round to the same percentage never put the less healthy torrent first.
     * Original addon order is retained only as the final stable tie-break.
     */
    fun sortDescending(
        streams: List<Stream>,
        preferredLanguages: List<String> = emptyList()
    ): List<Stream> = streams
        .mapIndexed { index, stream ->
            RankedForDisplay(
                stream = stream,
                score = score(stream, preferredLanguages),
                seeders = StreamParser.parse(stream).seeds ?: -1,
                originalIndex = index
            )
        }
        .sortedWith(
            compareByDescending<RankedForDisplay> { it.score }
                .thenByDescending { it.seeders }
                .thenBy { it.originalIndex }
        )
        .map(RankedForDisplay::stream)

    private data class RankedForDisplay(
        val stream: Stream,
        val score: Double,
        val seeders: Int,
        val originalIndex: Int
    )

    /**
     * Resolution owns a non-overlapping score band. Seeders, size, HDR, codec,
     * provider, and metadata can reorder streams inside the band but can never
     * promote a lower resolution above a higher resolution.
     */
    private fun qualityTierBase(quality: StreamQuality): Double = when (quality) {
        StreamQuality.UHD_4K -> 80.0
        StreamQuality.FHD_1080P -> 60.0
        StreamQuality.HD_720P -> 40.0
        StreamQuality.SD_480P -> 20.0
        StreamQuality.UNKNOWN -> 10.0
        StreamQuality.CAM -> 0.0
    }

    private fun qualityTierWidth(quality: StreamQuality): Double = when (quality) {
        StreamQuality.UHD_4K,
        StreamQuality.FHD_1080P,
        StreamQuality.HD_720P,
        StreamQuality.SD_480P -> 19.0
        StreamQuality.UNKNOWN,
        StreamQuality.CAM -> 9.0
    }

    private fun availabilityScore(stream: Stream, seeders: Int?): Double {
        if (seeders != null) {
            if (seeders <= 0) return 0.0
            return (25.0 * ln(seeders + 1.0) / ln(1_001.0)).coerceIn(0.01, 25.0)
        }
        return if (StreamSourceProviderResolver.requiresSeederMetadata(stream)) 0.0 else 22.0
    }

    private fun sizeEfficiencyScore(sizeBytes: Long?): Double {
        if (sizeBytes == null || sizeBytes <= 0L) return 7.0
        val sizeGb = sizeBytes / BYTES_PER_GB
        if (sizeGb < 10.0) {
            // Below 10 GB, additional bitrate/data is treated as a quality signal.
            return (5.0 + sizeGb).coerceIn(5.0, 15.0)
        }

        // From 10 GB onward, use ±5 GB bands around 10 GB steps:
        // 10–15 GB, 15–25 GB, 25–35 GB, and so on. Each larger band starts
        // two points lower, while the smaller file also wins inside its band.
        val bucket = kotlin.math.floor((sizeGb + 5.0) / 10.0)
            .toInt()
            .coerceAtLeast(1)
        val bucketStart = if (bucket == 1) 10.0 else bucket * 10.0 - 5.0
        val bucketEnd = bucket * 10.0 + 5.0
        val bucketTopScore = 15.0 - (bucket - 1) * 2.0
        val withinBucketPenalty =
            ((sizeGb - bucketStart) / (bucketEnd - bucketStart)).coerceIn(0.0, 1.0) * 2.0
        return (bucketTopScore - withinBucketPenalty).coerceIn(2.0, 15.0)
    }

    private fun playbackTechnologyScore(formats: Set<String>): Double {
        val dynamicRange = when {
            "dv" in formats -> 9
            "hdr10plus" in formats -> 8
            "hdr10" in formats -> 7
            "hlg" in formats -> 6
            "hdr" in formats -> 5
            else -> 0
        }
        val codec = when {
            "hevc" in formats || "av1" in formats -> 2
            "h264" in formats -> 1
            else -> 0
        }
        val audio = when {
            "dolby" in formats -> 1
            "dts" in formats -> 1
            else -> 0
        }
        val unsupportedPenalty = if ("3d" in formats) 4 else 0
        return (dynamicRange + codec + audio - unsupportedPenalty).coerceIn(0, 12).toDouble()
    }

    private fun providerScore(stream: Stream): Double = when (StreamSourceProviderResolver.detect(stream)) {
        StreamSourceProvider.TORRENTIO -> 5.0
        StreamSourceProvider.MEDIA_FUSION -> 3.0
        null -> if (!StreamSortingService.isTorrent(stream)) 4.0 else 2.0
    }

    private fun metadataConfidenceScore(
        quality: StreamQuality,
        sizeBytes: Long?,
        seeders: Int?
    ): Double = listOf(
        quality != StreamQuality.UNKNOWN,
        sizeBytes != null,
        seeders != null
    ).count { it }.toDouble()

    private fun languagePreferenceScore(stream: Stream, preferredLanguages: List<String>): Double {
        if (preferredLanguages.isEmpty()) return 0.0
        val detected = StreamParser.extractAudioLanguages(StreamParser.combinedText(stream))
        if (detected.isEmpty()) return 0.0
        val normalizedPreferences = preferredLanguages
            .map { it.trim().lowercase(Locale.ROOT).substringBefore('-') }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(3)
        val rank = normalizedPreferences.indexOfFirst(detected::contains)
        return when (rank) {
            0 -> 6.0
            1 -> 4.0
            2 -> 2.0
            else -> 0.0
        }
    }

    private const val MAX_SECONDARY_SCORE = 66.0
    private const val KNOWN_SEEDER_BAND_START = 0.50
    private const val UNKNOWN_SEEDER_BAND_END = 0.49
}
