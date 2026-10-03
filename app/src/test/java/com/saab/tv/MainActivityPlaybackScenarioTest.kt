package com.saab.tv

import android.app.Application
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.stremio.Stream
import com.saab.tv.data.model.stremio.StreamSubtitle
import com.saab.tv.domain.AddonSubtitle
import com.saab.tv.testing.FeatureFixture
import com.saab.tv.ui.player.PlayerSessionResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MainActivityPlaybackScenarioTest {
    private lateinit var fixture: FeatureFixture

    @Before fun setUp() {
        runBlocking {
            fixture = FeatureFixture(RuntimeEnvironment.getApplication())
            fixture.dao.insertProfile(ProfileEntity(id = 1, name = "Playback"))
        }
    }

    @After fun tearDown() = fixture.close()

    @Test fun watchedDecisionCoversInvalidDurationMinimumWatchThresholdTailAndClamp() {
        assertFalse(isPlaybackSnapshotCompleted(1_000_000, null))
        assertFalse(isPlaybackSnapshotCompleted(1_000_000, 0))
        assertFalse(isPlaybackSnapshotCompleted(299_999, 300_000))
        assertFalse(isPlaybackSnapshotCompleted(900_000, 1_000_000))
        assertTrue(isPlaybackSnapshotCompleted(950_000, 1_000_000))
        assertTrue(isPlaybackSnapshotCompleted(970_000, 1_000_000)) // final 30s fallback
        assertTrue(isPlaybackSnapshotCompleted(Long.MAX_VALUE, 1_000_000)) // position clamps to duration
        assertTrue(isPlaybackSnapshotCompleted(500_000, 1_000_000, watchedThresholdPercent = 1)) // lower bound is 50%
        assertFalse(isPlaybackSnapshotCompleted(9_800_000, 10_000_000, watchedThresholdPercent = 100)) // upper bound is 99%
    }

    @Test fun sourceResolutionPrefersDirectUrlAndBuildsMagnetWithAddonAndDefaultTrackers() {
        assertEquals("https://media.test/file", resolvePlayableSourceUrl(Stream(url = " https://media.test/file ")))
        assertNull(resolvePlayableSourceUrl(Stream()))

        val magnet = resolvePlayableSourceUrl(Stream(
            infoHash = "ABC123",
            sources = listOf("tracker:udp://custom.test:80/announce", "tracker:udp://custom.test:80/announce")
        ))!!
        assertTrue(magnet.startsWith("magnet:?xt=urn:btih:ABC123&dn=Video"))
        assertTrue(magnet.contains("custom.test"))
        assertTrue(magnet.contains("tracker.opentrackr.org"))
        assertEquals(1, Regex("custom.test").findAll(magnet).count())
    }

    @Test fun subtitleHelpersRejectUnsafeOrUnresolvableUrlsAndNormalizeNamesAndLanguage() {
        assertNull(resolveSubtitleUrl("  ", "https://addon.test"))
        assertEquals("https://cdn.test/sub.srt", resolveSubtitleUrl("https://cdn.test/sub.srt", null))
        assertNull(resolveSubtitleUrl("file:///private/sub.srt", null))
        assertNull(resolveSubtitleUrl("/sub.srt", null))
        assertEquals("https://addon.test/base/sub.srt", resolveSubtitleUrl("/sub.srt", "https://addon.test/base/"))
        assertNull(resolveSubtitleUrl("///", "https://addon.test"))

        assertEquals("OpenSubs", sanitizeSubtitleSourceName(" [OpenSubs] ", "fallback"))
        assertEquals("fallback", sanitizeSubtitleSourceName(" [] ", "fallback"))
        assertEquals("Movie English", subtitleNameFromUrl("https://subs.test/Movie%20English.srt?token=1"))
        assertNull(subtitleNameFromUrl("https://subs.test/"))
        assertEquals("ml-in", normalizeSubtitleLanguageTag(" ML_IN "))
        assertNull(normalizeSubtitleLanguageTag("  "))
    }

    @Test fun embeddedAndAddonSubtitlePayloadsFilterInvalidEntriesAndDeduplicate() {
        val stream = Stream(
            addonTransportUrl = "https://addon.test/subtitles",
            subtitles = listOf(
                StreamSubtitle(id = " embedded ", lang = "EN_us", url = "embedded.srt", name = " [Stream] "),
                StreamSubtitle(lang = "en-US", url = "https://cdn.test/English.srt?token=1"),
                StreamSubtitle(lang = "en", url = "file:///blocked.srt"),
                StreamSubtitle(lang = "en", url = " ")
            )
        )
        val embedded = buildEmbeddedSubtitlePayload(stream)
        assertEquals(2, embedded.size)
        assertEquals("embedded", embedded[0].id)
        assertEquals("https://addon.test/subtitles/embedded.srt", embedded[0].url)
        assertEquals("Stream", embedded[0].name)
        assertEquals("en-us", embedded[0].language)

        val addon = buildAddonSubtitlePayload(listOf(
            AddonSubtitle("", "https://cdn.test/English.srt?token=1", "EN-US", "[OpenSubs]"),
            AddonSubtitle("invalid", "relative.srt", "en", "OpenSubs"),
            AddonSubtitle("fallback", "https://subs.test/fallback.vtt", "te", " ")
        ))
        assertEquals(2, addon.size)
        assertEquals("OpenSubs", addon[0].name)
        assertEquals("en-us", addon[0].language)
        assertEquals("Addon subtitle", addon[1].name)

        val combined = buildSubtitlePayload(stream, listOf(
            AddonSubtitle("duplicate", "https://cdn.test/English.srt?token=1", "en-us", "OpenSubs")
        ))
        assertEquals(2, combined.size) // same normalized URL/language appears once
    }

    @Test fun sessionEndSavesOnlyUserSelectionsAndResolvesResumeHint() {
        val streamA = Stream(url = "https://media.test/a", infoHash = "a")
        val streamB = Stream(url = "https://media.test/b", infoHash = "b")
        val pending = PendingSourceSelection("tt1:1:2", streamA, listOf(streamA, streamB))
        var consumed = 0
        var resumeHint: String? = "initial"
        handlePlayerSessionEnd(
            sessionResult = PlayerSessionResult(10_000, 100_000, false, streamB.url, "audio:ml", "sub:ml", true, 750),
            selectedPlaybackId = " tt1:1:2 ",
            playbackTrackSelectionStore = fixture.tracks,
            sourceSelectionStore = fixture.sources,
            pendingSourceSelection = pending,
            onConsumePendingSelection = { consumed++ },
            onResumeHintResolved = { resumeHint = it }
        )
        assertEquals("tt1:1:2", resumeHint)
        assertEquals(1, consumed)
        assertEquals("audio:ml", fixture.tracks.getSelection("tt1:1:2")?.audioTrackId)
        assertEquals("sub:ml", fixture.tracks.getSelection("tt1:1:2")?.subtitleTrackId)
        assertEquals(750L, fixture.tracks.getSelection("tt1:1:2")?.subtitleDelayMs)
        assertEquals(streamB, fixture.sources.findPreferredStream("tt1:1:2", listOf(streamA, streamB)))
    }

    @Test fun sessionEndHandlesBlankShortCompletedUnmatchedAndDisabledRememberCases() {
        var consumed = 0
        val hints = mutableListOf<String?>()
        val noTracks = PlayerSessionResult(1_000, 100_000, false, null, null, "auto:en")
        handlePlayerSessionEnd(noTracks, "  ", fixture.tracks, fixture.sources, null,
            { consumed++ }, { hints += it })
        handlePlayerSessionEnd(noTracks.copy(positionMs = 4_999), "tt2", fixture.tracks, fixture.sources, null,
            { consumed++ }, { hints += it })
        handlePlayerSessionEnd(noTracks.copy(isCompleted = true, positionMs = 50_000), "tt3", fixture.tracks, fixture.sources, null,
            { consumed++ }, { hints += it })

        val launched = Stream(url = "https://media.test/launched", infoHash = "launched")
        val other = Stream(url = "https://media.test/other", infoHash = "other")
        val unmatchedPending = PendingSourceSelection("tt4", launched, listOf(other))
        handlePlayerSessionEnd(noTracks.copy(positionMs = 5_000, selectedSourceUrl = "https://media.test/missing"),
            "tt4", fixture.tracks, fixture.sources, unmatchedPending, { consumed++ }, { hints += it }, rememberSourceSelection = false)

        assertEquals(4, consumed)
        assertEquals(listOf(null, null, null, "tt4"), hints)
        assertNull(fixture.tracks.getSelection("tt2")) // auto-selected subtitle is not persisted
        assertFalse(fixture.sources.hasRememberedSelection("tt4")) // explicit opt-out is honored
    }
}
