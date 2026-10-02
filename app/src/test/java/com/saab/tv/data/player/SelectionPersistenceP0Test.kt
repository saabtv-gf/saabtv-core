package com.saab.tv.data.player

import android.app.Application
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.stremio.*
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
