package com.saab.tv.data.stream

import com.saab.tv.data.model.ParsedStreamInfo
import com.saab.tv.data.model.StreamQuality
import com.saab.tv.data.model.stremio.Stream

object StreamParser {

    private val seedPatterns = listOf(
        Regex("""(?:👤|👥)[\uFE0E\uFE0F]?\s*(?:seed(?:er)?s?\s*[:=]?\s*)?(\d[\d,.]*)([kKmM]?)"""),
        Regex("""(?i)\bseed(?:er)?s?\s*[:=\-]?\s*(\d[\d,.]*)([kKmM]?)"""),
        Regex("""(?i)\bseeding\s*[:=\-]?\s*(\d[\d,.]*)([kKmM]?)"""),
        Regex("""(?i)\bpeers?\s*[:=\-]?\s*(\d[\d,.]*)([kKmM]?)"""),
        Regex("""(?i)\bS(?:[:=]\s*|\s+)(\d[\d,.]*)([kKmM]?)"""),
        Regex("""(?i)\bS\s*/\s*L\s*[:=]?\s*(\d[\d,.]*)([kKmM]?)\s*/\s*\d""")
    )

    private val sizePattern = Regex("""(\d+(?:\.\d+)?)\s?(KB|MB|GB|TB)""", RegexOption.IGNORE_CASE)

    private val dvPattern = Regex("""(?i)\b(dolby\s*vision|dovi|dv)\b""")
    private val hdr10PlusPattern = Regex("""(?i)\bhdr[\s._-]*10(?:\+|[\s._-]*plus\b)""")
    private val hdr10Pattern = Regex("""(?i)\bhdr[\s._-]*10(?!\+|[\s._-]*plus)\b""")
    private val genericHdrPattern = Regex("""(?i)\bhdr\b""")
    private val hlgPattern = Regex("""(?i)\bhlg\b""")
    private val dtsPattern = Regex("""(?i)\b(dts[-\s]?(hd|x|ma)?)\b""")
    private val dolbyPattern = Regex("""(?i)\b(dolby\s*(digital|atmos)?|dd[+\s]?[257]\.?1?|atmos|ac-?3|eac-?3)\b""")
    private val hevcPattern = Regex("""(?i)\b(hevc|h\.?265|x\.?265)\b""")
    private val av1Pattern = Regex("""(?i)\bav1\b""")
    private val h264Pattern = Regex("""(?i)\b(h\.?264|x\.?264|avc)\b""")
    private val threeDPattern = Regex("""(?i)\b(3d|sbs|half.?sbs|hou)\b""")

    // Full names and three-letter release tags are safe to scan globally. Two-letter
    // ISO tags are handled separately and only inside an audio/language context so
    // ordinary title words such as "It" and release markers do not become languages.
    private val audioLanguagePatterns = linkedMapOf(
        "en" to Regex("""(?i)\b(english|eng)\b|English"""),
        "hi" to Regex("""(?i)\b(hindi|hin)\b|हिन्दी|हिंदी"""),
        "te" to Regex("""(?i)\b(telugu|tel)\b|తెలుగు"""),
        "ta" to Regex("""(?i)\b(tamil|tam)\b|தமிழ்"""),
        "ml" to Regex("""(?i)\b(malayalam|mal)\b|മലയാളം"""),
        "kn" to Regex("""(?i)\b(kannada|kan)\b|ಕನ್ನಡ"""),
        "bn" to Regex("""(?i)\b(bengali|bangla|ben)\b|বাংলা"""),
        "mr" to Regex("""(?i)\b(marathi|mar)\b|मराठी"""),
        "gu" to Regex("""(?i)\b(gujarati|guj)\b|ગુજરાતી"""),
        "pa" to Regex("""(?i)\b(punjabi|pan)\b|ਪੰਜਾਬੀ"""),
        "ur" to Regex("""(?i)\b(urdu|urd)\b|اردو"""),
        "or" to Regex("""(?i)\b(odia|oriya|ori)\b|ଓଡ[଼ି]ଆ"""),
        "as" to Regex("""(?i)\b(assamese|asm)\b|অসমীয়া"""),
        "es" to Regex("""(?i)\b(spanish|espanol|español|spa)\b"""),
        "fr" to Regex("""(?i)\b(french|fra|fre)\b"""),
        "de" to Regex("""(?i)\b(german|deu|ger)\b"""),
        "it" to Regex("""(?i)\b(italian|ita)\b"""),
        "pt" to Regex("""(?i)\b(portuguese|por|brazilian)\b"""),
        "ru" to Regex("""(?i)\b(russian|rus)\b"""),
        "ja" to Regex("""(?i)\b(japanese|jpn)\b"""),
        "ko" to Regex("""(?i)\b(korean|kor)\b"""),
        "zh" to Regex("""(?i)\b(chinese|mandarin|cantonese|zho|chi)\b"""),
        "ar" to Regex("""(?i)\b(arabic|ara)\b"""),
        "tr" to Regex("""(?i)\b(turkish|tur)\b"""),
        "nl" to Regex("""(?i)\b(dutch|nld|dut)\b"""),
        "pl" to Regex("""(?i)\b(polish|pol)\b"""),
        "id" to Regex("""(?i)\b(indonesian|ind)\b"""),
        "th" to Regex("""(?i)\b(thai|tha)\b"""),
        "vi" to Regex("""(?i)\b(vietnamese|vie)\b""")
    )

    private val shortAudioLanguageCodes = linkedMapOf(
        "en" to "en", "hi" to "hi", "te" to "te", "ta" to "ta",
        "ml" to "ml", "kn" to "kn", "bn" to "bn", "mr" to "mr",
        "gu" to "gu", "pa" to "pa", "ur" to "ur", "or" to "or",
        "as" to "as", "es" to "es", "fr" to "fr", "de" to "de",
        "it" to "it", "pt" to "pt", "ru" to "ru", "ja" to "ja",
        "jp" to "ja", "ko" to "ko", "kr" to "ko", "zh" to "zh",
        "cn" to "zh", "ar" to "ar", "tr" to "tr", "nl" to "nl",
        "pl" to "pl", "id" to "id", "th" to "th", "vi" to "vi"
    )

    private val languageContextPattern = Regex(
        """(?i)(?:🌐|🗣️?|\b(?:audio|audios|language|languages|lang|langs)\b(?:\s*[:=\-]\s*|\s+))([^\n\r]{1,140})"""
    )
    private val bracketedMetadataPattern = Regex("""[\[({]([^\])}]{1,100})[\])}]""")
    private val compactCodeClusterPattern = Regex(
        """(?i)(?<![a-z0-9])(?:${shortAudioLanguageCodes.keys.joinToString("|")})(?:\s*[+/,|._-]\s*(?:${shortAudioLanguageCodes.keys.joinToString("|")}))+(?![a-z0-9])"""
    )
    private val shortCodePattern = Regex(
        """(?i)(?<![a-z0-9])(${shortAudioLanguageCodes.keys.joinToString("|")})(?![a-z0-9])"""
    )

    fun parse(stream: Stream): ParsedStreamInfo {
        val text = combinedText(stream)
        return ParsedStreamInfo(
            quality = extractQuality(stream),
            sizeBytes = stream.behaviorHints?.videoSize ?: extractSizeBytes(text),
            seeds = if (stream.torBoxChecked) stream.torBoxSeeders else (stream.seeders?.takeIf { it >= 0 }
                ?: stream.behaviorHints?.seeders?.takeIf { it >= 0 }
                ?: extractSeeds(text)),
            formats = extractFormats(text)
        )
    }

    /**
     * Extract quality by checking each stream field individually (most specific first).
     * This avoids false positives where e.g. a filename contains "2160p" but the
     * stream.name says "1080p" — the name/title from the addon is more authoritative.
     */
    private fun extractQuality(stream: Stream): StreamQuality {
        // Check each source individually; return the first that yields a known quality
        val sources = listOfNotNull(
            stream.behaviorHints?.filename,
            stream.title,
            stream.description,
            stream.name
        )
        for (source in sources) {
            val quality = StreamQuality.fromString(source)
            if (quality != StreamQuality.UNKNOWN) return quality
        }
        return StreamQuality.UNKNOWN
    }

    fun combinedText(stream: Stream): String {
        return listOfNotNull(
            stream.name,
            stream.title,
            stream.description,
            stream.behaviorHints?.filename
        ).joinToString(" ")
    }

    fun extractSizeBytes(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val match = sizePattern.find(text) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].uppercase()
        val bytes = when (unit) {
            "TB" -> value * 1_099_511_627_776L
            "GB" -> value * 1_073_741_824L
            "MB" -> value * 1_048_576L
            "KB" -> value * 1_024L
            else -> return null
        }
        return bytes.toLong().takeIf { it > 0 }
    }

    fun extractSeeds(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        for (pattern in seedPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                parseCompactCount(match.groupValues[1], match.groupValues.getOrNull(2))?.let {
                    return it
                }
            }
        }
        return null
    }

    private fun parseCompactCount(number: String, suffix: String?): Int? {
        val multiplier = when (suffix?.lowercase()) {
            "k" -> 1_000.0
            "m" -> 1_000_000.0
            else -> 1.0
        }
        val normalized = if (multiplier > 1.0) {
            number.replace(',', '.')
        } else {
            number.replace(",", "").replace(".", "")
        }
        val value = normalized.toDoubleOrNull() ?: return null
        return (value * multiplier).toLong()
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()
    }

    fun extractFormats(text: String?): Set<String> {
        if (text.isNullOrBlank()) return emptySet()
        val formats = mutableSetOf<String>()
        if (dvPattern.containsMatchIn(text)) formats.add("dv")
        if (hdr10PlusPattern.containsMatchIn(text)) formats.add("hdr10plus")
        if (hdr10Pattern.containsMatchIn(text)) formats.add("hdr10")
        if (hlgPattern.containsMatchIn(text)) formats.add("hlg")
        if (genericHdrPattern.containsMatchIn(text) ||
            formats.any { it == "hdr10plus" || it == "hdr10" || it == "hlg" }
        ) formats.add("hdr")
        if (dtsPattern.containsMatchIn(text)) formats.add("dts")
        if (dolbyPattern.containsMatchIn(text)) formats.add("dolby")
        if (hevcPattern.containsMatchIn(text)) formats.add("hevc")
        if (av1Pattern.containsMatchIn(text)) formats.add("av1")
        if (h264Pattern.containsMatchIn(text)) formats.add("h264")
        if (threeDPattern.containsMatchIn(text)) formats.add("3d")
        return formats
    }

    fun extractAudioLanguages(text: String?): Set<String> {
        if (text.isNullOrBlank()) return emptySet()
        val detected = audioLanguagePatterns.mapNotNullTo(linkedSetOf()) { (language, pattern) ->
            language.takeIf { pattern.findAll(text).any { match -> !isSubtitleOnlyContext(text, match.range) } }
        }

        val contextualRegions = buildList<Pair<String, Int>> {
            languageContextPattern.findAll(text).forEach { match ->
                match.groups[1]?.let { group -> add(group.value to group.range.first) }
            }
            bracketedMetadataPattern.findAll(text).forEach { match ->
                match.groups[1]?.let { group -> add(group.value to group.range.first) }
            }
            compactCodeClusterPattern.findAll(text).forEach { match ->
                add(match.value to match.range.first)
            }
        }
        contextualRegions.forEach { (region, regionStart) ->
            shortCodePattern.findAll(region).forEach { match ->
                val absoluteRange = (regionStart + match.range.first)..(regionStart + match.range.last)
                if (isSubtitleOnlyContext(text, absoluteRange)) return@forEach
                val rawCode = match.groupValues[1].lowercase()
                shortAudioLanguageCodes[rawCode]?.let(detected::add)
            }
        }
        return detected
    }

    private fun isSubtitleOnlyContext(text: String, range: IntRange): Boolean {
        val before = text.substring((range.first - 18).coerceAtLeast(0), range.first)
        val after = text.substring(range.last + 1, (range.last + 19).coerceAtMost(text.length))
        val subtitleAfter = Regex(
            """(?i)^\s*-?\s*(sub|subs|subtitle|subtitles)\b(?!\s*[:=\-])"""
        )
            .containsMatchIn(after)
        val subtitleBefore = Regex("""(?i)\b(sub|subs|subtitle|subtitles)\s*[-:|+]?\s*$""")
            .containsMatchIn(before)
        return subtitleAfter || subtitleBefore
    }
}
