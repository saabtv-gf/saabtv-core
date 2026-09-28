package com.saab.tv.data.stream

import com.saab.tv.data.model.StreamQuality
import com.saab.tv.data.model.stremio.Stream
import java.util.Locale

data class StreamDisplayText(
    val title: String,
    val details: String
)

object StreamDisplayFormatter {
    private const val BYTES_PER_GB = 1_073_741_824.0
    private const val BYTES_PER_MB = 1_048_576.0

    private val declaredLanguageCountPattern = Regex(
        """(?i)\b(\d{1,2})\s*(?:audio\s*)?languages?\b"""
    )
    private val dualAudioPattern = Regex("""(?i)\bdual[\s._-]*(?:audio|language)?\b""")
    private val tripleAudioPattern = Regex("""(?i)\b(?:tri|triple)[\s._-]*(?:audio|language)?\b""")
    private val multiAudioPattern = Regex("""(?i)\bmulti[\s._-]*(?:audio|language)?\b""")

    fun format(contentTitle: String?, stream: Stream): StreamDisplayText {
        val title = contentTitle
            ?.replace('\n', ' ')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "Source"
        val parsed = StreamParser.parse(stream)
        val details = buildList {
            StreamSourceProviderResolver.detect(stream)?.label?.let(::add)
            if (stream.torBoxChecked) add(when (stream.torBoxCached) {
                true -> "TorBox Cached"
                false -> "TorBox Not Cached"
                null -> "TorBox Cache Unknown"
            }) else if (StreamSourceProviderResolver.requiresSeederMetadata(stream)) add("TorBox Not Checked")
            add(languageSummary(stream))
            qualityLabel(parsed.quality)?.let(::add)
            addAll(dynamicRangeLabels(parsed.formats))
            parsed.sizeBytes?.let(::formatSize)?.let(::add)
            parsed.seeds?.let { seeds ->
                add("$seeds ${if (seeds == 1) "Seeder" else "Seeders"} · ${if (stream.torBoxSeeders != null) "TorBox" else "Provider"}")
            } ?: if (StreamSourceProviderResolver.requiresSeederMetadata(stream)) {
                add("Seeders Not Reported")
            } else Unit
        }.joinToString(" • ")
        return StreamDisplayText(title = title, details = details)
    }

    fun languageSummary(stream: Stream): String {
        val text = StreamParser.combinedText(stream)
        val detected = StreamParser.extractAudioLanguages(text)
        val preferredLabels = listOf(
            "en" to "English",
            "te" to "Telugu",
            "hi" to "Hindi"
        ).mapNotNull { (code, label) -> label.takeIf { code in detected } }

        val declaredCount = declaredLanguageCountPattern.findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .maxOrNull()
        val inferredCount = when {
            declaredCount != null -> declaredCount
            tripleAudioPattern.containsMatchIn(text) -> 3
            dualAudioPattern.containsMatchIn(text) -> 2
            multiAudioPattern.containsMatchIn(text) -> maxOf(2, detected.size)
            else -> detected.size
        }
        val totalCount = maxOf(inferredCount, detected.size)
        val otherCount = (totalCount - preferredLabels.size).coerceAtLeast(0)

        return buildString {
            if (preferredLabels.isNotEmpty()) append(preferredLabels.joinToString(", "))
            if (otherCount > 0) {
                if (isNotEmpty()) append(' ')
                append("+$otherCount Other Language")
                if (otherCount != 1) append('s')
            }
            if (isEmpty()) append("Language Not Specified")
        }
    }

    private fun qualityLabel(quality: StreamQuality): String? = when (quality) {
        StreamQuality.UHD_4K -> "4K"
        StreamQuality.FHD_1080P -> "1080P"
        StreamQuality.HD_720P -> "720P"
        StreamQuality.SD_480P -> "480P"
        StreamQuality.CAM -> "Cam"
        StreamQuality.UNKNOWN -> null
    }

    private fun dynamicRangeLabels(formats: Set<String>): List<String> = buildList {
        if ("dv" in formats) add("DV")
        when {
            "hdr10plus" in formats -> add("HDR10+")
            "hdr10" in formats -> add("HDR10")
            "hlg" in formats -> add("HLG")
            "hdr" in formats && "dv" !in formats -> add("HDR")
        }
    }

    private fun formatSize(bytes: Long): String = if (bytes >= BYTES_PER_GB) {
        val gb = bytes / BYTES_PER_GB
        val pattern = if (gb >= 10.0 || gb % 1.0 < 0.05) "%.0f GB" else "%.1f GB"
        String.format(Locale.US, pattern, gb)
    } else {
        String.format(Locale.US, "%.0f MB", bytes / BYTES_PER_MB)
    }
}
