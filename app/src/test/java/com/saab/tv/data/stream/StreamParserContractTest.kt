package com.saab.tv.data.stream

import com.saab.tv.data.model.StreamQuality
import com.saab.tv.data.model.stremio.Stream
import com.saab.tv.data.model.stremio.StreamBehaviorHints
import org.junit.Assert.*
import org.junit.Test

class StreamParserContractTest {
    @Test fun sizeUnitsAndDecimalsUseBinaryBytes() {
        listOf("1 KB" to 1024L, "1.5mb" to 1572864L, "2 GB" to 2147483648L,
            "0.5 TB" to 549755813888L).forEach { (text, expected) ->
            assertEquals(text, expected, StreamParser.extractSizeBytes(text))
        }
        listOf(null, "", " ", "no size", "0 GB", "20 bytes").forEach {
            assertNull(StreamParser.extractSizeBytes(it))
        }
    }

    @Test fun allSeederFormatsPreserveZeroAndCompactCounts() {
        listOf("👤 123", "👥 seeds: 123", "seeders=123", "seeding:123", "peers 123", "S:123", "S/L:123/0")
            .forEach { assertEquals(it, 123, StreamParser.extractSeeds(it)) }
        listOf("Seeds: 1,234" to 1234, "Seeds: 1.234" to 1234, "Seeds: 1.5k" to 1500,
            "Seeds: 1,5K" to 1500, "Seeds: 2M" to 2000000, "Seeds: 0" to 0,
            "Seeds: 999999999999" to Int.MAX_VALUE).forEach { (text, expected) ->
            assertEquals(text, expected, StreamParser.extractSeeds(text))
        }
        listOf(null, "", "unknown", "seeders: none", "Seeds: 1..2k").forEach {
            assertNull(StreamParser.extractSeeds(it))
        }
    }

    @Test fun structuredSeederPrecedenceAndNegativeProviderFallback() {
        val base = Stream(title = "720p Seeds: 7 2 GB", seeders = 8,
            behaviorHints = StreamBehaviorHints(seeders = 9, videoSize = 123))
        assertEquals(10, StreamParser.parse(base.copy(torBoxSeeders = 10)).seeds)
        assertEquals(8, StreamParser.parse(base).seeds)
        assertEquals(9, StreamParser.parse(base.copy(seeders = -1)).seeds)
        assertEquals(7, StreamParser.parse(base.copy(seeders = -1,
            behaviorHints = StreamBehaviorHints(seeders = -1))).seeds)
        assertEquals(0, StreamParser.parse(base.copy(seeders = 0)).seeds)
        assertEquals(123L, StreamParser.parse(base).sizeBytes)
        assertEquals(2147483648L, StreamParser.parse(base.copy(behaviorHints = null)).sizeBytes)
        assertNull(StreamParser.parse(Stream()).seeds)
    }

    @Test fun qualityPrecedenceUsesFilenameThenTitleDescriptionAndName() {
        val stream = Stream(name = "480p", description = "720p", title = "1080p",
            behaviorHints = StreamBehaviorHints(filename = "movie.2160p.mkv"))
        assertEquals(StreamQuality.UHD_4K, StreamParser.parse(stream).quality)
        assertEquals(StreamQuality.FHD_1080P, StreamParser.parse(stream.copy(behaviorHints = null)).quality)
        assertEquals(StreamQuality.HD_720P, StreamParser.parse(stream.copy(behaviorHints = null, title = null)).quality)
        assertEquals(StreamQuality.SD_480P, StreamParser.parse(Stream(name = "480p")).quality)
        assertEquals(StreamQuality.UNKNOWN, StreamParser.parse(Stream()).quality)
        assertEquals("name title description filename", StreamParser.combinedText(Stream(
            name = "name", title = "title", description = "description",
            behaviorHints = StreamBehaviorHints(filename = "filename"))))
    }

    @Test fun formatTagsIncludeHdrFamilyWithoutConflatingHdr10Plus() {
        listOf("DV" to "dv", "Dovi" to "dv", "Dolby Vision" to "dv", "HDR10+" to "hdr10plus",
            "HDR10 Plus" to "hdr10plus", "HDR10" to "hdr10", "HLG" to "hlg", "DTS-X" to "dts",
            "Atmos" to "dolby", "HEVC" to "hevc", "AV1" to "av1", "AVC" to "h264", "SBS" to "3d")
            .forEach { (text, tag) -> assertTrue(text, tag in StreamParser.extractFormats(text)) }
        assertEquals(setOf("hdr10plus", "hdr"), StreamParser.extractFormats("HDR10+"))
        assertEquals(setOf("hdr10", "hdr"), StreamParser.extractFormats("HDR10"))
        assertEquals(setOf("hlg", "hdr"), StreamParser.extractFormats("HLG"))
        listOf(null, "", "ordinary title").forEach { assertTrue(StreamParser.extractFormats(it).isEmpty()) }
    }

    @Test fun fullLanguageNamesAndContextualIsoCodesAreDetected() {
        val names = linkedMapOf("English" to "en", "Hindi" to "hi", "Telugu" to "te", "Tamil" to "ta",
            "Malayalam" to "ml", "Kannada" to "kn", "Bengali" to "bn", "Marathi" to "mr", "Gujarati" to "gu",
            "Punjabi" to "pa", "Urdu" to "ur", "Odia" to "or", "Assamese" to "as", "Spanish" to "es",
            "French" to "fr", "German" to "de", "Italian" to "it", "Portuguese" to "pt", "Russian" to "ru",
            "Japanese" to "ja", "Korean" to "ko", "Chinese" to "zh", "Arabic" to "ar", "Turkish" to "tr",
            "Dutch" to "nl", "Polish" to "pl", "Indonesian" to "id", "Thai" to "th", "Vietnamese" to "vi")
        names.forEach { (name, code) ->
            assertEquals(name, setOf(code), StreamParser.extractAudioLanguages("Audio: $name"))
            assertEquals(code, setOf(code), StreamParser.extractAudioLanguages("Audio: $code"))
        }
        assertEquals(setOf("en", "te", "hi"), StreamParser.extractAudioLanguages("[en+te+hi]"))
        assertEquals(setOf("ja", "ko", "zh"), StreamParser.extractAudioLanguages("Audio: jp kr cn"))
    }

    @Test fun subtitleOnlyLanguagesAndOrdinaryTitleWordsAreNotAudio() {
        listOf(null, "", "It Comes At Night", "English subtitles", "Subs: Hindi", "Telugu-subs")
            .forEach { assertTrue(it.orEmpty(), StreamParser.extractAudioLanguages(it).isEmpty()) }
        assertEquals(setOf("en"), StreamParser.extractAudioLanguages("Audio: English\nSubs: Telugu"))
    }

    @Test fun qualityKeysRoundTripAndResolutionWinsOverReleaseLabels() {
        StreamQuality.entries.forEach { quality ->
            assertEquals(quality, StreamQuality.fromKey(StreamQuality.toKey(quality)))
        }
        assertNull(StreamQuality.fromKey("invalid"))
        listOf("2160i" to StreamQuality.UHD_4K, "1080p CAM" to StreamQuality.FHD_1080P,
            "720p UHD" to StreamQuality.HD_720P, "480p HD" to StreamQuality.SD_480P,
            "Ultra HD" to StreamQuality.UHD_4K, "FHD" to StreamQuality.FHD_1080P,
            "CAMRip" to StreamQuality.CAM, "DVDRip" to StreamQuality.SD_480P,
            "HD" to StreamQuality.HD_720P, "unknown" to StreamQuality.UNKNOWN).forEach { (text, expected) ->
            assertEquals(text, expected, StreamQuality.fromString(text))
        }
    }

    @Test fun providerDetectionUsesEveryMetadataFieldAndMaintainsPreference() {
        listOf(Stream(name = "Torrentio"), Stream(title = "torrentio"), Stream(description = "TORRENTIO"),
            Stream(addonTransportUrl = "https://torrentio.example"), Stream(url = "https://torrentio.example/video"))
            .forEach { assertEquals(StreamSourceProvider.TORRENTIO, StreamSourceProviderResolver.detect(it)) }
        assertEquals(StreamSourceProvider.MEDIA_FUSION, StreamSourceProviderResolver.detect(Stream(name = "Media Fusion")))
        assertEquals(StreamSourceProvider.MEDIA_FUSION, StreamSourceProviderResolver.detect(Stream(name = "MediaFusion")))
        assertNull(StreamSourceProviderResolver.detect(Stream()))
        assertEquals(Int.MAX_VALUE, StreamSourceProviderResolver.selectionRank(Stream()))
        assertTrue(StreamSourceProviderResolver.selectionRank(Stream(name = "Torrentio")) <
            StreamSourceProviderResolver.selectionRank(Stream(name = "MediaFusion")))
        assertTrue(StreamSourceProviderResolver.requiresSeederMetadata(Stream(infoHash = "hash")))
        assertTrue(StreamSourceProviderResolver.requiresSeederMetadata(Stream(name = "Torrentio", url = "https://video")))
        assertFalse(StreamSourceProviderResolver.requiresSeederMetadata(Stream(url = "https://video")))
    }
}
