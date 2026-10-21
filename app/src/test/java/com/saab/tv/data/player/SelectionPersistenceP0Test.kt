package com.saab.tv.data.player

import android.app.Application
import com.saab.tv.data.account.AccountStorage
import com.saab.tv.data.account.AccountSnapshotStore
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.stremio.*
import com.saab.tv.domain.schema.ContentIdentity
import com.saab.tv.domain.schema.ContentKind
import com.saab.tv.testing.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SelectionPersistenceP0Test {
    private lateinit var f: FeatureFixture
    @Before fun setup() = runBlocking {
        f = FeatureFixture(RuntimeEnvironment.getApplication())
        f.dao.insertProfile(ProfileEntity(id = 1, name = "One")); f.dao.insertProfile(ProfileEntity(id = 2, name = "Two"))
        f.app.configuration.saveRuntimeState(1)
    }
    @After fun cleanup() = f.close()
    private fun stream(id: String) = Stream(url = "https://fixture.invalid/$id", title = "1080p English", infoHash = id.repeat(40).take(40))
    @Test fun changedMovieSourceIsPreferredOnResumeAndClearRemovesPreference() {
        val a = stream("a"); val b = stream("b")
        f.sources.rememberSelection("tt1", a); f.sources.rememberSelection("tt1", b)
        assertEquals(b, f.sources.findPreferredStream("tt1", listOf(a,b)))
        f.sources.clearSelection("tt1"); assertFalse(f.sources.hasRememberedSelection("tt1"))
    }
    @Test fun rememberedSourceIsScopedToProfile() = runBlocking {
        val a = stream("a"); f.sources.rememberSelection("tt1", a)
        f.app.configuration.saveRuntimeState(2)
        assertFalse(f.sources.hasRememberedSelection("tt1"))
        f.app.configuration.saveRuntimeState(1); assertTrue(f.sources.hasRememberedSelection("tt1"))
    }
    @Test fun seasonPackSourceContinuesAcrossEpisodeFileIndexes() {
        val a = stream("a").copy(fileIdx = 1, behaviorHints = StreamBehaviorHints(filename = "Show.S01E01.mkv", bingeGroup = "pack"))
        val next = a.copy(fileIdx = 2, behaviorHints = StreamBehaviorHints(filename = "Show.S01E02.mkv", bingeGroup = "pack"))
        f.sources.rememberSelection("tt1:1:1", a)
        assertEquals(next, f.sources.findPreferredStream("tt1:1:2", listOf(stream("b"),next)))
    }
    @Test fun rememberedSourceIsFoundThroughVerifiedMovieIdentityAlias() = runBlocking {
        val tmdb = requireNotNull(ContentIdentity.tmdb(ContentKind.MOVIE, "42"))
        val imdb = requireNotNull(ContentIdentity.imdb(ContentKind.MOVIE, "tt0000042"))
        f.canonicalIdentityStore.linkVerifiedIdentities(tmdb, imdb)
        val remembered = stream("a")
        f.sources.rememberSelection("tmdb:42", remembered, "movie")

        assertTrue(f.sources.hasRememberedSelection("tt0000042", "movie"))
        assertEquals(remembered, f.sources.findPreferredStream("tt0000042", "movie", listOf(stream("b"), remembered)))
    }

    @Test fun rememberedSeriesSourceAliasUsesTheSeriesRootForEpisodePlayback() = runBlocking {
        val tmdb = requireNotNull(ContentIdentity.tmdb(ContentKind.SERIES, "84"))
        val imdb = requireNotNull(ContentIdentity.imdb(ContentKind.SERIES, "tt0000084"))
        f.canonicalIdentityStore.linkVerifiedIdentities(tmdb, imdb)
        val remembered = stream("a").copy(fileIdx = 7)
        f.sources.rememberSelection("tmdb:84:2:5", remembered, "series")

        assertTrue(f.sources.hasRememberedSelection("tt0000084:2:5", "series"))
        assertEquals(remembered, f.sources.findPreferredStream("tt0000084:2:5", "series", listOf(stream("b"), remembered)))
    }
    @Test fun chosenSeasonPackAndExplicitSubtitleSurviveAccountRestore() = runBlocking {
        val first = stream("a").copy(fileIdx = 1,
            behaviorHints = StreamBehaviorHints(filename = "Show.S01E01.mkv", bingeGroup = "pack"))
        val next = first.copy(fileIdx = 2,
            behaviorHints = StreamBehaviorHints(filename = "Show.S01E02.mkv", bingeGroup = "pack"))
        val lowerRanked = stream("b").copy(fileIdx = 2)
        AccountStorage.setUserId(f.context, "portable-playback-test")
        val sources = SourceSelectionStore(f.context, f.app.configuration)
        val tracks = PlaybackTrackSelectionStore(f.context, f.app.configuration)
        val snapshotStore = AccountSnapshotStore(f.context, f.app.db)
        sources.rememberSelection("tt1:1:1", first)
        tracks.updateSelection("tt1:1:1", "audio-ml", "embedded:ml", updateAudio = true, updateSubtitle = true)
        val snapshot = snapshotStore.capture()

        sources.clearSelection("tt1:1:1")
        tracks.clearSelection("tt1:1:1")
        snapshotStore.restore(snapshot)

        assertEquals(next, sources.findPreferredStream("tt1:1:2", listOf(lowerRanked, next)))
        assertEquals(PlaybackTrackSelectionStore.Selection("audio-ml", "embedded:ml", null),
            tracks.getSelection("tt1:1:1"))
    }
    @Test fun clearingSeriesPrefixDoesNotClearAnotherTitle() {
        f.sources.rememberSelection("tt1:1:1", stream("a")); f.sources.rememberSelection("tt2", stream("b"))
        f.sources.clearSelectionsForPrefix("tt1")
        assertFalse(f.sources.hasRememberedSelection("tt1:1:1")); assertTrue(f.sources.hasRememberedSelection("tt2"))
    }
    @Test fun explicitSubtitleOffPersistsAndAudioUpdateDoesNotOverwriteIt() {
        f.tracks.updateSelection("tt1", "audio-en", "off", updateAudio = true, updateSubtitle = true)
        f.tracks.updateSelection("tt1", "audio-hi", null, updateAudio = true, updateSubtitle = false)
        val selection = f.tracks.getSelection("tt1")!!
        assertEquals("audio-hi", selection.audioTrackId); assertEquals("off", selection.subtitleTrackId)
    }
    @Test fun trackSelectionsFollowPortableProfileWhenLocalRoomIdChanges() = runBlocking {
        f.tracks.updateSelection("tt-portable", "audio-ml", "embedded:ml", 250L,
            updateAudio = true, updateSubtitle = true, updateSubtitleDelay = true)
        val portableId = requireNotNull(f.app.configuration.getLastActivePortableProfileId())
        AccountStorage.preferences(f.context, "profile_configuration_prefs").edit()
            .putString("portable_profile_id_2", portableId)
            .putInt("last_active_profile_id", 2)
            .commit()

        assertEquals(
            PlaybackTrackSelectionStore.Selection("audio-ml", "embedded:ml", 250L),
            f.tracks.getSelection("tt-portable")
        )
    }
    @Test fun legacyLocalProfileTrackKeysMigrateToPortableIdentityOnRead() {
        val portableId = requireNotNull(f.app.configuration.getLastActivePortableProfileId())
        val prefs = AccountStorage.preferences(f.context, "playback_track_selection_prefs")
        prefs.edit().putString("audio_p1_tt-legacy", "audio-en")
            .putString("subtitle_p1_tt-legacy", "embedded:en")
            .putBoolean("subtitleExplicit_p1_tt-legacy", true)
            .putLong("subtitleDelay_p1_tt-legacy", -125L).commit()

        assertEquals(
            PlaybackTrackSelectionStore.Selection("audio-en", "embedded:en", -125L),
            f.tracks.getSelection("tt-legacy")
        )
        assertEquals("audio-en", prefs.getString("audio_u${portableId}_tt-legacy", null))
        assertFalse(prefs.contains("audio_p1_tt-legacy"))
    }
    @Test fun clearingPortableTrackChoiceAlsoRemovesLegacyValuesThatCouldResurrectIt() {
        f.tracks.updateSelection("tt-clear", "audio-en", "embedded:en", 500L,
            updateAudio = true, updateSubtitle = true, updateSubtitleDelay = true)
        val prefs = AccountStorage.preferences(f.context, "playback_track_selection_prefs")
        prefs.edit().putString("audio_p1_tt-clear", "stale-audio")
            .putString("subtitle_p1_tt-clear", "stale-subtitle")
            .putBoolean("subtitleExplicit_p1_tt-clear", true)
            .putLong("subtitleDelay_p1_tt-clear", 900L).commit()

        f.tracks.updateSelection("tt-clear", " ", " ", 0L,
            updateAudio = true, updateSubtitle = true, updateSubtitleDelay = true)

        assertNull(f.tracks.getSelection("tt-clear"))
        assertFalse(prefs.contains("audio_p1_tt-clear"))
        assertFalse(prefs.contains("subtitle_p1_tt-clear"))
        assertFalse(prefs.contains("subtitleDelay_p1_tt-clear"))
    }
    @Test fun subtitleDelayRoundTripsAndZeroClearsOverride() {
        listOf(-1500L, 0L, 1500L).forEach { delay ->
            f.tracks.updateSelection("tt1", null, null, delay, false, false, true)
            assertEquals(delay.takeIf { it != 0L }, f.tracks.getSelection("tt1")?.subtitleDelayMs)
        }
    }
    @Test fun noUpdateFlagsDoNotCreateSelection() {
        f.tracks.updateSelection("tt1", "a", "s", updateAudio = false, updateSubtitle = false)
        assertNull(f.tracks.getSelection("tt1"))
    }
    @Test fun blankPlaybackIdIsIgnoredAndBlankTracksClearSelection() {
        f.tracks.updateSelection("", "a", "s", updateAudio = true, updateSubtitle = true)
        assertNull(f.tracks.getSelection(""))
        f.tracks.updateSelection("tt1", "a", "s", updateAudio = true, updateSubtitle = true)
        f.tracks.updateSelection("tt1", "  ", " ", updateAudio = true, updateSubtitle = true)
        assertNull(f.tracks.getSelection("tt1"))
    }
}
