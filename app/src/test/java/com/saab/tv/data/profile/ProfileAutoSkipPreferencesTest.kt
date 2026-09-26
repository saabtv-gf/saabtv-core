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
}
