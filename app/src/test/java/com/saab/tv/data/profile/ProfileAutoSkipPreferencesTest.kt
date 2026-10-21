package com.saab.tv.data.profile

import com.saab.tv.data.model.ProfileEntity
import org.junit.Assert.*
import org.junit.Test

class ProfileAutoSkipPreferencesTest {
    @Test fun countdownAppliesToBothIntroAndOutro() {
        for (seconds in listOf(5, 10)) {
            val profile = ProfileEntity(name = "Test").withAutoSkipCountdown(seconds)
            assertTrue(profile.autoSkipIntro)
            assertEquals(seconds, profile.autoSkipCountdownSeconds)
            assertEquals(seconds, profile.introSkipCountdownSeconds)
            assertEquals(seconds, profile.outroSkipCountdownSeconds)
        }
    }

    @Test fun offDisablesBothCountdownsButKeepsManualSkipAndAutoplayPreferences() {
        val profile = ProfileEntity(name = "Test").withAutoSkipCountdown(0)
        assertFalse(profile.autoSkipIntro)
        assertEquals(0, profile.autoSkipCountdownSeconds)
        assertEquals(0, profile.introSkipCountdownSeconds)
        assertEquals(0, profile.outroSkipCountdownSeconds)
        assertTrue(profile.skipIntro)
        assertTrue(profile.autoplayNextEpisode)
    }

    @Test fun existingProfilesUseIntroCountdownAsSharedValue() {
        assertEquals(10, ProfileEntity(name = "Test", introSkipCountdownSeconds = 10,
            outroSkipCountdownSeconds = 5).autoSkipCountdownSeconds)
        assertEquals(0, ProfileEntity(name = "Test", autoSkipIntro = false,
            outroSkipCountdownSeconds = 10).autoSkipCountdownSeconds)
    }

    @Test fun invalidValuesTurnOffInsteadOfTriggeringAnUnexpectedSkip() {
        assertEquals(0, ProfileEntity(name = "Test").withAutoSkipCountdown(30).autoSkipCountdownSeconds)
    }

    @Test fun legacyMirrorsAreAlwaysDerivedFromTheCanonicalSeekAndIntroSettings() {
        val tenSecondProfile = ProfileEntity(
            name = "Ten seconds",
            seekTimeIntervalSeconds = 20,
            seekThumbnailIntervalSeconds = 30,
            autoSkipIntro = true,
            introSkipCountdownSeconds = 10,
            outroSkipCountdownSeconds = 5
        ).withCanonicalSharedPreferences()
        assertEquals(20, tenSecondProfile.seekThumbnailIntervalSeconds)
        assertEquals(10, tenSecondProfile.outroSkipCountdownSeconds)

        val fallbackCountdownProfile = ProfileEntity(
            name = "Fallback countdown",
            autoSkipIntro = true,
            introSkipCountdownSeconds = 0,
            outroSkipCountdownSeconds = 10
        ).withCanonicalSharedPreferences()
        assertEquals(5, fallbackCountdownProfile.outroSkipCountdownSeconds)

        val disabledProfile = fallbackCountdownProfile.copy(autoSkipIntro = false)
            .withCanonicalSharedPreferences()
        assertEquals(0, disabledProfile.outroSkipCountdownSeconds)
    }
}
