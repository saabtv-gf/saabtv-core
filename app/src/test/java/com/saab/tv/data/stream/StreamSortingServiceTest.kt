package com.saab.tv.data.stream

import com.saab.tv.data.model.StreamQuality
import com.saab.tv.data.model.stremio.Stream
import com.saab.tv.data.model.stremio.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSortingServiceTest {
    private val service = StreamSortingService()

    @Test fun zeroSeederFilterUsesTorBoxWhenPresentAndProviderOtherwise() {
        val torBoxZero = Stream(title = "Movie 1080p", infoHash = "torbox-zero", seeders = 9,
            torBoxChecked = true, torBoxCached = false, torBoxSeeders = 0)
        val providerZero = Stream(title = "Movie 1080p", infoHash = "provider-zero", seeders = 0,
            torBoxChecked = true, torBoxCached = null, torBoxSeeders = null)
        val providerPositive = Stream(title = "Movie 1080p", infoHash = "provider-positive", seeders = 9,
            torBoxChecked = true, torBoxCached = null, torBoxSeeders = null)
        val cachedZero = Stream(title = "Movie 1080p", infoHash = "cached-zero", seeders = 0,
            torBoxChecked = true, torBoxCached = true, torBoxSeeders = 0)
        val filtered = service.sortAndFilter(listOf(torBoxZero, providerZero, providerPositive, cachedZero),
            StreamQuality.entries.toSet(), emptyList(), emptyMap(), hideZeroSeeders = true)
        assertFalse(torBoxZero in filtered)
        assertFalse(providerZero in filtered)
        assertTrue(providerPositive in filtered)
        assertTrue(cachedZero in filtered)
    }

    @Test
    fun seasonPackFilterHidesSingleEpisodeTorrentsAndKeepsDirectStreams() {
        val seasonPack = Stream(
            title = "Example Show S01 Complete 1080p",
            infoHash = "season-pack"
        )
        val seasonRange = Stream(
            title = "Example Show S01-S03 Collection",
            infoHash = "season-range"
        )
        val singleEpisode = Stream(
            title = "Example Show 1080p",
            infoHash = "single-episode",
            behaviorHints = StreamBehaviorHints(filename = "Example.Show.S01E03.mkv")
        )
        val directStream = Stream(
            title = "Direct S01E03",
            url = "https://example.com/episode.m3u8"
        )

        val filtered = service.sortAndFilter(
            streams = listOf(seasonPack, seasonRange, singleEpisode, directStream),
            enabledQualities = StreamQuality.entries.toSet(),
            excludePhrases = emptyList(),
            addonSortOrders = emptyMap(),
            seasonPackTorrentsOnly = true
        )

        assertTrue(seasonPack in filtered)
        assertTrue(seasonRange in filtered)
        assertTrue(directStream in filtered)
        assertFalse(singleEpisode in filtered)
        assertEquals(3, filtered.size)
    }

    @Test
    fun completeSeasonDetectionRejectsEpisodeNotation() {
        assertTrue(
            StreamSortingService.isCompleteSeasonTorrent(
                Stream(title = "Show Season 2 Pack", infoHash = "pack")
            )
        )
        assertTrue(
            StreamSortingService.isCompleteSeasonTorrent(
                Stream(title = "Show Complete Series", url = "magnet:?xt=urn:btih:complete")
            )
        )
        assertFalse(
            StreamSortingService.isCompleteSeasonTorrent(
                Stream(title = "Show Season 2 Episode 4", infoHash = "episode")
            )
        )
        assertFalse(
            StreamSortingService.isCompleteSeasonTorrent(
                Stream(title = "Show.S02.E04.1080p", infoHash = "episode-dotted")
            )
        )
    }

    @Test
    fun zeroSeederFilterKeepsStreamsWithUnknownSeederCounts() {
        val zeroSeeders = Stream(title = "Movie 1080p Seeds: 0", infoHash = "zero")
        val active = Stream(title = "Movie 1080p Seeds: 4", infoHash = "active")
        val unknown = Stream(title = "Movie 1080p", url = "https://example.com/movie.m3u8")

        val filtered = service.sortAndFilter(
            streams = listOf(zeroSeeders, active, unknown),
            enabledQualities = StreamQuality.entries.toSet(),
            excludePhrases = emptyList(),
            addonSortOrders = emptyMap(),
            hideZeroSeeders = true
        )

        assertFalse(zeroSeeders in filtered)
        assertTrue(active in filtered)
        assertTrue(unknown in filtered)
    }

    @Test
    fun smartTclSortMatchesDescendingPreciseScores() {
        val large4k = Stream(title = "Movie 2160p 18 GB Hindi Seeds: 200", infoHash = "large-4k")
        val small4k = Stream(title = "Movie 2160p 7 GB Telugu Seeds: 2", infoHash = "small-4k")
        val small1080p = Stream(title = "Movie 1080p 2 GB English Seeds: 500", infoHash = "small-1080")

        val sorted = service.sortAndFilter(
            streams = listOf(large4k, small1080p, small4k),
            enabledQualities = StreamQuality.entries.toSet(),
            excludePhrases = emptyList(),
            addonSortOrders = emptyMap(),
            sortBy = StreamSortingService.SMART_TCL_C755
        )

        assertEquals(listOf(large4k, small4k, small1080p), sorted)
        val scores = sorted.map(StreamScoreCalculator::score)
        assertTrue(scores.zipWithNext().all { (first, second) -> first >= second })
    }

    @Test
    fun smartTclSortUsesSeedersBeforeLanguageForEquivalentSizes() {
        val hindi = Stream(title = "Movie 2160p 8 GB Hindi Seeds: 500", infoHash = "hindi")
        val english = Stream(title = "Movie 2160p 8 GB English Seeds: 2", infoHash = "english")
        val telugu = Stream(title = "Movie 2160p 8 GB Telugu Seeds: 900", infoHash = "telugu")

        val sorted = service.sortAndFilter(
            streams = listOf(hindi, telugu, english),
            enabledQualities = StreamQuality.entries.toSet(),
            excludePhrases = emptyList(),
            addonSortOrders = emptyMap(),
            sortBy = StreamSortingService.SMART_TCL_C755
        )

        assertEquals(listOf(telugu, hindi, english), sorted)
    }

    @Test
    fun smartTclSortUsesSeedersWhenSizesShareFiveGbToleranceBand() {
        val smaller = Stream(title = "Movie 2160p 7 GB English Seeds: 2", infoHash = "smaller")
        val larger = Stream(title = "Movie 2160p 9 GB Telugu Seeds: 900", infoHash = "larger")

        val sorted = service.sortAndFilter(
            streams = listOf(larger, smaller),
            enabledQualities = StreamQuality.entries.toSet(),
            excludePhrases = emptyList(),
            addonSortOrders = emptyMap(),
            sortBy = StreamSortingService.SMART_TCL_C755
        )

        assertEquals(listOf(larger, smaller), sorted)
    }

    @Test
    fun smartTclSortUsesHigherSeedersWhenDisplayedScoresAreEqual() {
        // Availability reaches its score ceiling at 1,000 seeders. Raw seeder
        // count remains the deterministic tie-break after the exact score ties.
        val fewerSeeders = Stream(
            title = "Movie 2160p 8 GB Seeds: 1000",
            infoHash = "fewer-seeders"
        )
        val moreSeeders = Stream(
            title = "Movie 2160p 8 GB Seeds: 2000",
            infoHash = "more-seeders"
        )
        assertEquals(
            StreamScoreCalculator.scorePercent(fewerSeeders),
            StreamScoreCalculator.scorePercent(moreSeeders)
        )

        val sorted = service.sortAndFilter(
            streams = listOf(fewerSeeders, moreSeeders),
            enabledQualities = StreamQuality.entries.toSet(),
            excludePhrases = emptyList(),
            addonSortOrders = emptyMap(),
            sortBy = StreamSortingService.SMART_TCL_C755
        )

        assertEquals(listOf(moreSeeders, fewerSeeders), sorted)
    }

    @Test
    fun smartSortUsesUserConfiguredLanguageOrder() {
        val hindi = Stream(title = "Movie 2160p 8 GB Hindi Seeds: 50", infoHash = "hindi")
        val telugu = Stream(title = "Movie 2160p 8 GB Telugu Seeds: 50", infoHash = "telugu")
        val english = Stream(title = "Movie 2160p 8 GB English Seeds: 50", infoHash = "english")

        val sorted = service.sortAndFilter(
            streams = listOf(hindi, telugu, english),
            enabledQualities = StreamQuality.entries.toSet(),
            excludePhrases = emptyList(),
            addonSortOrders = emptyMap(),
            sortBy = StreamSortingService.SMART_TCL_C755,
            preferredAudioLanguages = listOf("te", "hi", "en")
        )

        assertEquals(listOf(telugu, hindi, english), sorted)
    }

    @Test
    fun smartTclSortPrefersPremiumDynamicRangeForOtherwiseEquivalentStreams() {
        val sdr = Stream(title = "Movie 2160p 8 GB English Seeds: 50", infoHash = "sdr")
        val hdr = Stream(title = "Movie 2160p HDR 8 GB English Seeds: 50", infoHash = "hdr")
        val hlg = Stream(title = "Movie 2160p HLG 8 GB English Seeds: 50", infoHash = "hlg")
        val hdr10 = Stream(title = "Movie 2160p HDR10 8 GB English Seeds: 50", infoHash = "hdr10")
        val hdr10Plus = Stream(
            title = "Movie 2160p HDR10Plus 8 GB English Seeds: 50",
            infoHash = "hdr10plus"
        )
        val dolbyVision = Stream(
            title = "Movie 2160p Dolby Vision 8 GB English Seeds: 50",
            infoHash = "dv"
        )

        val sorted = service.sortAndFilter(
            streams = listOf(sdr, hdr, hlg, hdr10, hdr10Plus, dolbyVision),
            enabledQualities = StreamQuality.entries.toSet(),
            excludePhrases = emptyList(),
            addonSortOrders = emptyMap(),
            sortBy = StreamSortingService.SMART_TCL_C755
        )

        assertEquals(listOf(dolbyVision, hdr10Plus, hdr10, hlg, hdr, sdr), sorted)
    }

    @Test
    fun smartTclSortPrefersTorrentioForOtherwiseEquivalentStreams() {
        val mediaFusion = Stream(
            name = "[MediaFusion] 4K",
            title = "Movie 2160p 8 GB English Seeds: 50",
            infoHash = "mediafusion"
        )
        val torrentio = Stream(
            name = "[Torrentio] 4K",
            title = "Movie 2160p 8 GB English Seeds: 50",
            infoHash = "torrentio"
        )

        val sorted = service.sortAndFilter(
            streams = listOf(mediaFusion, torrentio),
            enabledQualities = StreamQuality.entries.toSet(),
            excludePhrases = emptyList(),
            addonSortOrders = emptyMap(),
            sortBy = StreamSortingService.SMART_TCL_C755
        )

        assertEquals(listOf(torrentio, mediaFusion), sorted)
    }

    @Test
    fun fileSizeSortPlacesSmallerKnownFilesFirstAndUnknownLast() {
        val large = Stream(title = "Movie 1080p 9 GB", infoHash = "large")
        val small = Stream(title = "Movie 1080p 2 GB", infoHash = "small")
        val unknown = Stream(title = "Movie 1080p", infoHash = "unknown")

        val sorted = service.sortAndFilter(
            streams = listOf(large, unknown, small),
            enabledQualities = StreamQuality.entries.toSet(),
            excludePhrases = emptyList(),
            addonSortOrders = emptyMap(),
            sortBy = "size"
        )

        assertEquals(listOf(small, large, unknown), sorted)
    }

    @Test
    fun subtitleLanguageLabelsDoNotMasqueradeAsAudioLanguages() {
        val audioLanguages = StreamParser.extractAudioLanguages(
            "Hindi Audio | English Subtitles | Telugu Subs"
        )

        assertEquals(setOf("hi"), audioLanguages)
    }

    @Test
    fun detectsCompactIsoAudioLanguageListsWithoutIncludingSubtitleCodes() {
        val audioLanguages = StreamParser.extractAudioLanguages(
            "Movie.2160p | Audio: EN/TE + HI | Subtitles: FR"
        )

        assertEquals(setOf("en", "hi", "te"), audioLanguages)
    }

    @Test
    fun detectsReleaseTagsAndMediaFusionNativeLanguageNames() {
        assertEquals(
            setOf("en", "hi", "te", "ta", "ml"),
            StreamParser.extractAudioLanguages("Movie.1080p.[ENG+HIN+TEL+TAM+MAL]")
        )
        assertEquals(
            setOf("en", "hi", "te"),
            StreamParser.extractAudioLanguages("🌐 English + हिंदी + తెలుగు")
        )
    }

    @Test
    fun ignoresUnlabelledTwoLetterWordsInOrdinaryReleaseNames() {
        assertEquals(
            emptySet<String>(),
            StreamParser.extractAudioLanguages("It.2024.WEB-DL.DV.HDR10")
        )
    }

    @Test
    fun parsesMediaFusionSeederFormatsWithoutTreatingSeasonAsSeeders() {
        assertEquals(482, StreamParser.extractSeeds("📦 8.2 GB  👤️ 482  🌐 Telugu"))
        assertEquals(91, StreamParser.extractSeeds("Seeders: 91 | MediaFusion"))
        assertEquals(1_200, StreamParser.extractSeeds("👥 1.2K | MediaFusion"))
        assertEquals(2_500_000, StreamParser.extractSeeds("Peers: 2.5M"))
        assertEquals(72, StreamParser.extractSeeds("Seeding = 72"))
        assertEquals(88, StreamParser.extractSeeds("S/L: 88/12"))
        assertEquals(null, StreamParser.extractSeeds("Show.Name.S01E04.1080p"))
    }
}
