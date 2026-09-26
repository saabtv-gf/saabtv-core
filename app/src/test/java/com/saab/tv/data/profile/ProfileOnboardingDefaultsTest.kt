package com.saab.tv.data.profile

import com.saab.tv.data.model.ProfileEntity
import org.junit.Assert.*
import org.junit.Test

class ProfileOnboardingDefaultsTest {
    @Test fun newProfilesUseRequestedDefaults() {
        val profile = ProfileEntity(name = "New")
        assertEquals(30, profile.seekTimeIntervalSeconds)
        assertEquals(30, profile.seekThumbnailIntervalSeconds)
        assertTrue(profile.seekThumbnailsEnabled && profile.rememberSourceSelection && profile.autoSelectSource)
        assertTrue(profile.autoSkipIntro && profile.skipIntro && profile.autoplayNextEpisode)
        assertEquals("introdb", profile.autoplayThresholdMode)
        assertEquals(5, profile.introSkipCountdownSeconds)
        assertEquals(5, profile.outroSkipCountdownSeconds)
        assertTrue(profile.sourceSeasonPacksOnly && profile.sourceHideZeroSeeders)
        assertEquals("en", profile.preferredAudioLanguage)
        assertEquals("en", profile.preferredSubtitleLanguage)
    }

    @Test fun tvChoiceAndLanguagePriorityAreApplied() {
        val base = ProfileEntity(name = "New", themeId = "custom")
        val fourK = base.withOnboardingPreferences(true, listOf("te", "hi", "en"))
        assertTrue(fourK.tunnelingEnabled)
        assertEquals("3d", fourK.sourceExcludedFormats)
        assertEquals(listOf("te", "hi", "en"), listOf(fourK.sourceLanguagePriority1, fourK.sourceLanguagePriority2, fourK.sourceLanguagePriority3))
        assertEquals("custom", fourK.themeId)
        val hd = base.withOnboardingPreferences(false, listOf("en", "fr", "de"))
        assertFalse(hd.tunnelingEnabled)
        assertEquals(setOf("dv", "hdr", "dts", "dolby", "hevc", "av1", "3d"), hd.sourceExcludedFormats.split(",").toSet())
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateLanguagesAreRejected() {
        ProfileEntity(name = "New").withOnboardingPreferences(true, listOf("en", "en", "te"))
    }
}
