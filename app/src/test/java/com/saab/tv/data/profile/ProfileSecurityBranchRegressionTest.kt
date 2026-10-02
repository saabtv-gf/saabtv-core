package com.saab.tv.data.profile

import com.saab.tv.data.model.ProfileEntity
import org.junit.Assert.*
import org.junit.Test

class ProfileSecurityBranchRegressionTest {
    @Test fun malformedStoredPinsFailClosedWithoutCrashing() {
        for (hash in listOf("v2\$bad", "v2\$no-number\$salt\$hash", "v2\$120000\$!\$!", "v2\$120000\$\$")) {
            assertFalse(ProfilePin.matches(ProfileEntity(name = "Test", pinHash = hash), "1234"))
        }
        val noPin = ProfileEntity(name = "Test")
        assertTrue(ProfilePin.matches(noPin, ""))
        assertFalse(ProfilePin.needsUpgrade(noPin))
        assertFalse(ProfilePin.isValid("123"))
        assertFalse(ProfilePin.isValid("12a4"))
        assertFalse(ProfilePin.isValid("12345"))
        assertTrue(ProfilePin.isValid("0000"))
    }
    @Test(expected = IllegalArgumentException::class)
    fun pinHashRejectsInvalidInput() { ProfilePin.hash(1, "abcd") }

    @Test(expected = IllegalArgumentException::class)
    fun onboardingRejectsMissingLanguagePreference() {
        ProfileEntity(name = "Test").withOnboardingPreferences(listOf("en", "te"))
    }
    @Test(expected = IllegalArgumentException::class)
    fun onboardingRejectsBlankLanguagePreference() {
        ProfileEntity(name = "Test").withOnboardingPreferences(listOf("en", "te", " "))
    }
    @Test fun anEmptyLanguagePreferenceListIsInvalid() {
        try {
            ProfileEntity(name = "Test").withOnboardingPreferences(emptyList())
            fail("Missing preferences must not silently create a profile")
        } catch (_: IllegalArgumentException) { }
    }
}
