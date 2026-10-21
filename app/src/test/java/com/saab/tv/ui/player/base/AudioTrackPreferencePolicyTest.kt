package com.saab.tv.ui.player.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTrackPreferencePolicyTest {
    private val tracks = listOf(
        PlayerTrackOption(id = "fr-track", label = "French", language = "fra"),
        PlayerTrackOption(id = "en-track", label = "English", language = "eng"),
        PlayerTrackOption(id = "hi-track", label = "Hindi", language = "hi-IN")
    )

    @Test
    fun choosesFirstAvailableLanguageByUserPriorityNotProviderTrackOrder() {
        assertEquals(
            "hi-track",
            AudioTrackPreferencePolicy.preferredTrackId(tracks, listOf("te", "hi", "en"))
        )
    }

    @Test
    fun changedGlobalPreferenceRejectsRememberedForeignTrack() {
        assertEquals(
            "en-track",
            AudioTrackPreferencePolicy.initialTrackId(tracks, listOf("en", "hi"), "fr-track")
        )
    }

    @Test
    fun primaryLanguageWinsOverRememberedTrackInLowerPriorityLanguage() {
        assertEquals(
            "en-track",
            AudioTrackPreferencePolicy.initialTrackId(tracks, listOf("en", "hi"), "hi-track")
        )
    }

    @Test
    fun rememberedVariantIsKeptWhenItMatchesTheHighestPriorityLanguage() {
        val variants = listOf(
            PlayerTrackOption(id = "en-stereo", label = "English Stereo", language = "en"),
            PlayerTrackOption(id = "en-surround", label = "English Surround", language = "eng")
        )
        assertEquals("en-surround", AudioTrackPreferencePolicy.initialTrackId(variants, listOf("en", "hi"), "en-surround"))
    }

    @Test
    fun languageLabelCanSupplyMissingManifestLanguageMetadata() {
        val untaggedTracks = listOf(
            PlayerTrackOption(id = "track-1", label = "Hindi EAC3 5.1", language = null),
            PlayerTrackOption(id = "track-2", label = "English AAC Stereo", language = null)
        )
        assertEquals("track-2", AudioTrackPreferencePolicy.preferredTrackId(untaggedTracks, listOf("en", "hi")))
    }

    @Test
    fun oneShotLanguageSelectionNeverFallsBackToAnotherAvailableLanguage() {
        assertEquals("hi-track", AudioTrackPreferencePolicy.trackIdForLanguage(tracks, "hin"))
        assertEquals(null, AudioTrackPreferencePolicy.trackIdForLanguage(tracks, "te"))
    }

    @Test
    fun explicitManifestTrackIdsRemainUniqueAcrossTrackGroups() {
        val first = AudioTrackPreferencePolicy.stableTrackId("a", 0, 0, "audio")
        val second = AudioTrackPreferencePolicy.stableTrackId("a", 1, 0, "audio")
        assertTrue(first != second)
    }

    @Test
    fun regionalPreferenceCanUseTheBaseLanguageTrackWhenNoRegionalVariantExists() {
        assertEquals(
            "es-track",
            AudioTrackPreferencePolicy.preferredTrackId(
                listOf(PlayerTrackOption(id = "es-track", label = "Spanish", language = "es")),
                listOf("es-419")
            )
        )
    }

    @Test
    fun emptyPreferencesPreserveExistingDefaultTrackBehavior() {
        assertEquals("fr-track", AudioTrackPreferencePolicy.initialTrackId(tracks, emptyList(), "fr-track"))
        assertEquals(emptyList<String>(), AudioTrackPreferencePolicy.normalizePreferences(listOf("", "und")))
    }

    @Test
    fun audioLanguageGroupsCanRankPreferredLanguageAheadOfAlphabeticalOrder() {
        val groups = buildAudioLanguageGroups(tracks, listOf("hi", "en", "fr"))

        assertEquals(listOf("hi", "en", "fr"), groups.map { it.key })
    }
}
