package com.saab.tv.ui.player.base

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleTrackPreferenceTest {
    @Test
    fun unsupportedEmbeddedSubtitleDoesNotBlockUsableExternalSubtitle() {
        val embedded = PlayerTrackOption("embedded", "English", "en", supported = false,
            subtitleSourcePriority = SubtitleSourcePriority.EMBEDDED)
        val external = PlayerTrackOption("external", "English", "en", isExternal = true)
        assertEquals(external, SubtitleTrackPreference.findPreferred(listOf(embedded, external), "en"))
    }
    @Test
    fun embeddedSubtitleWinsOverOpenSubtitlesForSameLanguage() {
        val openSubtitles = PlayerTrackOption(
            id = "open",
            label = "OpenSubtitles English",
            language = "en",
            isExternal = true,
            subtitleSourcePriority = SubtitleSourcePriority.ADDON
        )
        val embedded = PlayerTrackOption(
            id = "embedded",
            label = "English",
            language = "eng",
            subtitleSourcePriority = SubtitleSourcePriority.EMBEDDED
        )

        assertEquals(
            embedded,
            SubtitleTrackPreference.findPreferred(listOf(openSubtitles, embedded), "English")
        )
    }

    @Test
    fun addonSubtitleIsUsedWhenEmbeddedLanguageIsUnavailable() {
        val embeddedTelugu = PlayerTrackOption(
            id = "embedded-te",
            label = "Telugu",
            language = "te",
            subtitleSourcePriority = SubtitleSourcePriority.EMBEDDED
        )
        val openSubtitlesEnglish = PlayerTrackOption(
            id = "open-en",
            label = "English",
            language = "en",
            isExternal = true,
            subtitleSourcePriority = SubtitleSourcePriority.ADDON
        )

        assertEquals(
            openSubtitlesEnglish,
            SubtitleTrackPreference.findPreferred(
                listOf(embeddedTelugu, openSubtitlesEnglish),
                "English"
            )
        )
    }
}
