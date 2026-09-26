package com.saab.tv.data.stream

import com.saab.tv.data.model.stremio.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamScoreCalculatorTest {
    @Test
    fun premiumHealthyEfficientSourceScoresHigher() {
        val premium = Stream(
            name = "[Torrentio] 4K",
            title = "Movie 2160p DV HEVC 8 GB Seeds: 500",
            infoHash = "premium"
        )
        val weak = Stream(
            name = "[MediaFusion] 720p",
            title = "Movie 720p 8 GB Seeds: 0",
            infoHash = "weak"
        )

        assertTrue(
            StreamScoreCalculator.scorePercent(premium) >
                StreamScoreCalculator.scorePercent(weak)
        )
    }

    @Test
    fun scoreDoesNotPenalizeMissingAudioLanguageKeywords() {
        val withoutLanguage = Stream(
            name = "[Torrentio] 1080p",
            title = "Movie 1080p HEVC 4 GB Seeds: 50",
            infoHash = "without-language"
        )
        val withLanguage = withoutLanguage.copy(
            title = "Movie 1080p HEVC 4 GB English Seeds: 50",
            infoHash = "with-language"
        )

        assertTrue(
            StreamScoreCalculator.scorePercent(withoutLanguage) ==
                StreamScoreCalculator.scorePercent(withLanguage)
        )
    }

    @Test
    fun configuredLanguageOrderChangesScore() {
        val english = Stream(title = "Movie 1080p English 4 GB Seeds: 50", infoHash = "en")
        val telugu = english.copy(title = "Movie 1080p Telugu 4 GB Seeds: 50", infoHash = "te")

        assertTrue(
            StreamScoreCalculator.score(telugu, listOf("te", "en", "hi")) >
                StreamScoreCalculator.score(english, listOf("te", "en", "hi"))
        )
    }

    @Test
    fun scoreAlwaysRemainsAPercentage() {
        val score = StreamScoreCalculator.scorePercent(Stream(title = "Unknown source"))
        assertTrue(score in 0..100)
    }

    @Test
    fun preciseScoreDifferentiatesSeederCountsThatRoundToTheSameWholePercent() {
        val fewerSeeders = Stream(
            title = "Movie 2160p 8 GB Seeds: 500",
            infoHash = "fewer"
        )
        val moreSeeders = fewerSeeders.copy(
            title = "Movie 2160p 8 GB Seeds: 510",
            infoHash = "more"
        )

        assertTrue(StreamScoreCalculator.score(moreSeeders) > StreamScoreCalculator.score(fewerSeeders))
        assertNotEquals(
            StreamScoreCalculator.scoreLabel(fewerSeeders),
            StreamScoreCalculator.scoreLabel(moreSeeders)
        )
    }

    @Test
    fun debridUrlsStillUseAdvertisedSeederCounts() {
        val fewerSeeders = Stream(
            title = "Movie 2160p 8 GB Seeds: 50",
            url = "https://debrid.example/low"
        )
        val moreSeeders = fewerSeeders.copy(
            title = "Movie 2160p 8 GB Seeds: 500",
            url = "https://debrid.example/high"
        )

        assertTrue(StreamScoreCalculator.score(moreSeeders) > StreamScoreCalculator.score(fewerSeeders))
    }

    @Test
    fun torrentAddonDebridUrlWithoutSeedersNeverBeatsKnownSeederMetadataInTier() {
        val unknownDebridStream = Stream(
            name = "Torrentio 4K",
            title = "Movie 2160p DV HEVC Dolby 9 GB",
            url = "https://cdn.torbox.app/unknown.mkv"
        )
        val knownDebridStream = Stream(
            name = "MediaFusion 4K",
            title = "Movie 2160p 40 GB Seeds: 1",
            url = "https://cdn.torbox.app/known.mkv"
        )

        assertTrue(
            StreamScoreCalculator.score(knownDebridStream) >
                StreamScoreCalculator.score(unknownDebridStream)
        )
    }

    @Test
    fun structuredSeederMetadataParticipatesInScoring() {
        val fewer = Stream(title = "Movie 2160p 8 GB", seeders = 5, infoHash = "fewer")
        val more = fewer.copy(seeders = 500, infoHash = "more")

        assertTrue(StreamScoreCalculator.score(more) > StreamScoreCalculator.score(fewer))
    }

    @Test
    fun canonicalPickerOrderIsExactScoreDescending() {
        val lower = Stream(title = "Movie 1080p 6 GB Seeds: 20", infoHash = "lower")
        val higher = Stream(title = "Movie 2160p DV HEVC 8 GB Seeds: 400", infoHash = "higher")

        assertEquals(
            listOf("higher", "lower"),
            StreamScoreCalculator.sortDescending(listOf(lower, higher)).map(Stream::infoHash)
        )
    }

    @Test
    fun rawSeederCountBreaksAnExactScoreTie() {
        val fewer = Stream(
            name = "[Torrentio] 4K",
            title = "Movie 2160p DV HEVC Dolby 1 GB Seeds: 1000",
            infoHash = "fewer"
        )
        val more = fewer.copy(
            title = "Movie 2160p DV HEVC Dolby 1 GB Seeds: 2000",
            infoHash = "more"
        )

        assertEquals(StreamScoreCalculator.score(fewer), StreamScoreCalculator.score(more), 0.0)
        assertEquals(
            listOf("more", "fewer"),
            StreamScoreCalculator.sortDescending(listOf(fewer, more)).map(Stream::infoHash)
        )
    }

    @Test
    fun knownSeederMetadataAlwaysWinsInsideTheSameResolutionTier() {
        val unknownButPremium = Stream(
            name = "[Torrentio] 4K",
            title = "Movie 2160p DV HEVC Dolby 9 GB",
            infoHash = "unknown-seeders"
        )
        val knownButOtherwiseWeak = Stream(
            name = "[MediaFusion] 4K",
            title = "Movie 2160p 40 GB Seeds: 0",
            infoHash = "known-seeders"
        )

        assertTrue(
            StreamScoreCalculator.score(knownButOtherwiseWeak) >
                StreamScoreCalculator.score(unknownButPremium)
        )
        assertEquals(
            listOf("known-seeders", "unknown-seeders"),
            StreamScoreCalculator.sortDescending(listOf(unknownButPremium, knownButOtherwiseWeak))
                .map(Stream::infoHash)
        )
    }

    @Test
    fun resolutionBandStillWinsWhenHigherResolutionHasUnknownSeeders() {
        val fourKUnknown = Stream(title = "Movie 2160p 8 GB", infoHash = "4k-unknown")
        val fullHdKnown = Stream(
            title = "Movie 1080p DV HEVC 8 GB Seeds: 50000",
            infoHash = "1080p-known"
        )

        assertTrue(
            StreamScoreCalculator.score(fourKUnknown) >
                StreamScoreCalculator.score(fullHdKnown)
        )
    }

    @Test
    fun higherResolutionAlwaysOutscoresLowerResolutionRegardlessOfSeeders() {
        val fourKWithoutSeeders = Stream(
            title = "Movie 2160p 60 GB Seeds: 0",
            infoHash = "4k"
        )
        val fullHdWithManySeeders = Stream(
            title = "Movie 1080p HEVC 4 GB Seeds: 50000",
            infoHash = "1080p"
        )
        val hdWithManySeeders = Stream(
            title = "Movie 720p HEVC 2 GB Seeds: 50000",
            infoHash = "720p"
        )

        assertTrue(
            StreamScoreCalculator.score(fourKWithoutSeeders) >
                StreamScoreCalculator.score(fullHdWithManySeeders)
        )
        assertTrue(
            StreamScoreCalculator.score(fullHdWithManySeeders) >
                StreamScoreCalculator.score(hdWithManySeeders)
        )
    }

    @Test
    fun belowTenGbLargerFileReceivesHigherScore() {
        val fiveGb = sameSpecificationAtSize("5 GB")
        val nineGb = sameSpecificationAtSize("9 GB")

        assertTrue(StreamScoreCalculator.score(nineGb) > StreamScoreCalculator.score(fiveGb))
    }

    @Test
    fun aboveTenGbSmallerFileWinsInsideFiveGbBucketWindow() {
        val seventeenGb = sameSpecificationAtSize("17 GB")
        val twentyThreeGb = sameSpecificationAtSize("23 GB")

        assertTrue(
            StreamScoreCalculator.score(seventeenGb) >
                StreamScoreCalculator.score(twentyThreeGb)
        )
    }

    @Test
    fun aboveTenGbSmallerSizeBucketReceivesHigherScore() {
        val fourteenGb = sameSpecificationAtSize("14 GB")
        val sixteenGb = sameSpecificationAtSize("16 GB")

        assertTrue(
            StreamScoreCalculator.score(fourteenGb) >
                StreamScoreCalculator.score(sixteenGb)
        )
    }

    private fun sameSpecificationAtSize(size: String) = Stream(
        title = "Movie 2160p HEVC $size English Seeds: 50",
        infoHash = size
    )
}
