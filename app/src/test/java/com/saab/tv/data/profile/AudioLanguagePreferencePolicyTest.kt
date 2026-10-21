package com.saab.tv.data.profile

import com.saab.tv.data.model.ProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioLanguagePreferencePolicyTest {
    @Test
    fun profilePrioritiesAreTheCanonicalOrderedAudioPreference() {
        val profile = ProfileEntity(
            name = "Viewer",
            sourceLanguagePriority1 = "te",
            sourceLanguagePriority2 = "en",
            sourceLanguagePriority3 = "hi",
            preferredAudioLanguage = "en",
            preferredAudioLanguageSecondary = "fr"
        )

        assertEquals(listOf("te", "en", "hi"), AudioLanguagePreferencePolicy.forProfile(profile))
    }

    @Test
    fun promotionFromPlaybackUpdatesSourceRankingAndLegacyAudioFields() {
        val profile = ProfileEntity(
            name = "Viewer",
            sourceLanguagePriority1 = "te",
            sourceLanguagePriority2 = "en",
            sourceLanguagePriority3 = "hi"
        )

        val updated = AudioLanguagePreferencePolicy.promote(profile, "eng")

        assertEquals(listOf("en", "te", "hi"), AudioLanguagePreferencePolicy.forProfile(updated))
        assertEquals("en", updated.sourceLanguagePriority1)
        assertEquals("en", updated.preferredAudioLanguage)
        assertEquals("te", updated.preferredAudioLanguageSecondary)
    }

    @Test
    fun settingPriorityDeduplicatesAndMirrorsLegacyFields() {
        val profile = ProfileEntity(
            name = "Viewer",
            sourceLanguagePriority1 = "en",
            sourceLanguagePriority2 = "te",
            sourceLanguagePriority3 = "hi"
        )

        val updated = AudioLanguagePreferencePolicy.setPriority(profile, 1, "te")

        assertEquals(listOf("te", "hi"), AudioLanguagePreferencePolicy.forProfile(updated))
        assertEquals("te", updated.preferredAudioLanguage)
        assertEquals("", updated.preferredAudioLanguageSecondary)
    }

    @Test
    fun legacyAudioPreferencesAreFallbackWhenSourcePrioritiesAreUnset() {
        val profile = ProfileEntity(
            name = "Legacy",
            sourceLanguagePriority1 = "",
            sourceLanguagePriority2 = "",
            sourceLanguagePriority3 = "",
            preferredAudioLanguage = "fr",
            preferredAudioLanguageSecondary = "en"
        )

        assertEquals(listOf("fr", "en"), AudioLanguagePreferencePolicy.forProfile(profile))
    }

    @Test
    fun existingPlaybackPreferenceWinsOverUntouchedSourceDefaults() {
        val upgradedProfile = ProfileEntity(
            name = "Upgraded",
            sourceLanguagePriority1 = "en",
            sourceLanguagePriority2 = "te",
            sourceLanguagePriority3 = "hi",
            preferredAudioLanguage = "fr",
            preferredAudioLanguageSecondary = "en"
        )

        assertEquals(listOf("fr", "en", "te"), AudioLanguagePreferencePolicy.forProfile(upgradedProfile))
    }

    @Test
    fun customizedSourcePrioritiesRemainAuthoritativeOverLegacyAudioFields() {
        val profile = ProfileEntity(
            name = "Customized",
            sourceLanguagePriority1 = "te",
            sourceLanguagePriority2 = "hi",
            sourceLanguagePriority3 = "en",
            preferredAudioLanguage = "fr"
        )

        assertEquals(listOf("te", "hi", "en"), AudioLanguagePreferencePolicy.forProfile(profile))
    }
}
