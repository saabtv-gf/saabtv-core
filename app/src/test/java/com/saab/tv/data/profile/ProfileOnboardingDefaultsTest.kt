package com.saab.tv.data.profile

import com.saab.tv.data.model.ProfileEntity
import org.junit.Assert.*
import org.junit.Test

class ProfileOnboardingDefaultsTest {
    @Test fun newProfilesUseRequestedDefaults() {
        val profile = ProfileEntity(name = "New")
        assertEquals(95, profile.watchedThreshold)
        assertEquals(30, profile.seekTimeIntervalSeconds)
        assertEquals(30, profile.seekThumbnailIntervalSeconds)
        assertTrue(profile.seekThumbnailsEnabled && profile.rememberSourceSelection && profile.autoSelectSource)
        assertTrue(profile.autoSkipIntro && profile.skipIntro && profile.autoplayNextEpisode)
        assertTrue(profile.skipRecap)
        assertEquals("introdb", profile.autoplayThresholdMode)
        assertEquals(5, profile.introSkipCountdownSeconds)
        assertEquals(5, profile.outroSkipCountdownSeconds)
        assertTrue(profile.sourceSeasonPacksOnly && profile.sourceHideZeroSeeders)
        assertEquals("en", profile.preferredAudioLanguage)
        assertEquals("en", profile.preferredSubtitleLanguage)
    }

    @Test fun deviceChoiceAndLanguagePriorityAreIndependent() {
        val base = ProfileEntity(name = "New", themeId = "custom")
        val languages = base.withOnboardingPreferences(listOf("ml", "kn", "en"))
        assertEquals(listOf("ml", "kn", "en"), listOf(languages.sourceLanguagePriority1, languages.sourceLanguagePriority2, languages.sourceLanguagePriority3))
        assertEquals(base.tunnelingEnabled, languages.tunnelingEnabled)
        val fourK = base.withOnboardingPreferences(listOf("te", "hi", "en")).forDeviceDisplay(true)
        assertTrue(fourK.tunnelingEnabled)
        assertTrue(fourK.sourceEnabledQualities.split(",").contains("4k"))
        assertEquals("3d", fourK.sourceExcludedFormats)
        assertEquals(listOf("te", "hi", "en"), listOf(fourK.sourceLanguagePriority1, fourK.sourceLanguagePriority2, fourK.sourceLanguagePriority3))
        assertEquals("custom", fourK.themeId)
        val hd = base.withOnboardingPreferences(listOf("en", "fr", "de")).forDeviceDisplay(false)
        assertFalse(hd.tunnelingEnabled)
        assertEquals("1080p,720p,unknown", hd.sourceEnabledQualities)
        assertEquals(setOf("dv", "hdr", "dts", "dolby", "hevc", "av1", "3d"), hd.sourceExcludedFormats.split(",").toSet())
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateLanguagesAreRejected() {
        ProfileEntity(name = "New").withOnboardingPreferences(listOf("en", "en", "te"))
    }

    @Test fun cloudDisplayValuesCannotOverrideTheLocalDeviceDefaults() {
        val fromHdDevice = ProfileEntity(name = "Shared", tunnelingEnabled = false, sourceEnabledQualities = "720p", sourceExcludedFormats = "hdr")
        val on4kDevice = fromHdDevice.forDeviceDisplay(true)
        assertTrue(on4kDevice.tunnelingEnabled)
        assertTrue(on4kDevice.sourceEnabledQualities.contains("4k"))
        assertEquals("3d", on4kDevice.sourceExcludedFormats)
        assertEquals("720p", fromHdDevice.sourceEnabledQualities)
    }

    @Test fun onlyUnwatchedEpisodesAreProtectedWhenEnabled() {
        assertTrue(shouldHideEpisodeSpoilers(true, false))
        assertFalse(shouldHideEpisodeSpoilers(true, true))
        assertFalse(shouldHideEpisodeSpoilers(false, false))
    }
}
