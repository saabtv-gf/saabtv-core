package com.saab.tv.ui.player.base

import android.app.Application
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real adapter control paths. Decoder/network playback still requires device tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ExoPlayerAdapterRegressionTest {
    private lateinit var backend: ExoPlayerBackend
    @Before fun setup() { backend = ExoPlayerBackend(RuntimeEnvironment.getApplication()) }
    @After fun cleanup() = backend.release()
    @Test fun speedClampsToSupportedRangeAndPreservesInRangeValue() {
        backend.setPlaybackSpeed(0.01f); assertEquals(0.25f, backend.uiState.value.playbackSpeed)
        backend.setPlaybackSpeed(100f); assertEquals(2f, backend.uiState.value.playbackSpeed)
        backend.setPlaybackSpeed(1.5f); assertEquals(1.5f, backend.uiState.value.playbackSpeed)
    }
    @Test fun subtitleSizeAndPositionClampIndependently() {
        backend.setSubtitleSize(1); assertEquals(50, backend.uiState.value.subtitleSizePercent)
        backend.setSubtitleSize(999); assertEquals(200, backend.uiState.value.subtitleSizePercent)
        backend.setSubtitleVerticalOffset(-999); assertEquals(-20, backend.uiState.value.subtitleVerticalOffsetPercent)
        backend.setSubtitleVerticalOffset(999); assertEquals(20, backend.uiState.value.subtitleVerticalOffsetPercent)
        backend.setSubtitleSize(125); assertEquals(125, backend.uiState.value.subtitleSizePercent)
    }
    @Test fun manualSubtitleDelayClampsAndRemainsPersistable() {
        backend.setSubtitleDelay(Long.MIN_VALUE); assertEquals(-10_000L, backend.persistableSubtitleDelayMs())
        backend.setSubtitleDelay(Long.MAX_VALUE); assertEquals(10_000L, backend.persistableSubtitleDelayMs())
        backend.setSubtitleDelay(-1500); assertEquals(-1500L, backend.uiState.value.subtitleDelayMs)
    }
    @Test fun subtitleColorsPreserveArgbIncludingTransparency() {
        backend.setSubtitleTextColor(0xFF00AAFF.toInt()); backend.setSubtitleBackgroundColor(0x80112233.toInt())
        assertEquals(0xFF00AAFF.toInt(), backend.uiState.value.subtitleTextColor)
        assertEquals(0x80112233.toInt(), backend.uiState.value.subtitleBackgroundColor)
    }
    @Test fun releasedAdapterRejectsLateControlsAndReleaseIsIdempotent() {
        backend.release(); val before = backend.uiState.value
        backend.play(); backend.pause(); backend.togglePlayPause(); backend.seekTo(-1); backend.seekBy(30_000)
        backend.setPlaybackSpeed(2f); backend.setSubtitleSize(180); backend.setSubtitleVerticalOffset(10)
        backend.setSubtitleDelay(5000); backend.setSubtitleTextColor(0); backend.setSubtitleBackgroundColor(-1)
        backend.selectSource("missing"); backend.selectAudioTrack(null)
        backend.release()
        assertEquals(before, backend.uiState.value)
        assertTrue(backend.audioTracks.value.isEmpty()); assertTrue(backend.subtitleTracks.value.isEmpty())
    }
    @Test fun subtitleSelectionAfterReleaseMustNotMutateDisposedPlayerState() {
        backend.release()
        val before = backend.uiState.value
        backend.selectSubtitleTrack(null)
        backend.selectSubtitleTrack("embedded:en")
        backend.selectSubtitleTrack("external:en")
        assertEquals(before, backend.uiState.value)
    }

    @Test fun selectingSubtitleOffCancelsPendingAutoSyncWork() {
        val pending = Job()
        setPrivateField("subtitleAutoSyncJob", pending)
        setPrivateField("autoSyncSubtitleId", "external:en")

        ExoPlayerBackend::class.java.getDeclaredMethod(
            "scheduleSubtitleAutoSync", String::class.java
        ).apply {
            isAccessible = true
            invoke(backend, "#none")
        }

        assertFalse("Subtitle Off must cancel ALASS work", pending.isActive)
        assertNull(privateField<Job?>("subtitleAutoSyncJob"))
        assertNull(privateField<String?>("autoSyncSubtitleId"))
    }

    @Test fun manualSubtitleDelayCancelsPendingAutoSyncWork() {
        val pending = Job()
        setPrivateField("subtitleAutoSyncJob", pending)

        backend.setSubtitleDelay(250L)

        assertFalse("A manual delay supersedes auto-sync", pending.isActive)
        assertEquals(250L, backend.persistableSubtitleDelayMs())
    }
    @Test fun transportControlsBeforeLoadDoNotCreateSourcesOrCrash() {
        backend.play(); backend.pause(); backend.seekBy(-30_000); backend.seekTo(10_000)
        assertTrue(backend.sourceOptions.value.isEmpty()); assertEquals(0L, backend.uiState.value.positionMs)
    }

    @Test fun lateMagnetResolutionFromPreviousLoadCannotReplaceCurrentSession() {
        setPrivateField("_sourceOptions", MutableStateFlow(listOf(
            PlayerSourceOption("magnet", "magnet:?xt=urn:btih:0123456789012345678901234567890123456789", "Torrent"),
            PlayerSourceOption("direct", "https://media.example/video.mp4", "Direct")
        )))
        setPrivateField("currentSourceId", "direct")

        var resolveMagnet: ((String, String) -> Unit)? = null
        backend.onMagnetSourceSelected = { _, _, _, onReady, _ -> resolveMagnet = onReady }
        backend.selectSource("magnet")
        val oldResolution = checkNotNull(resolveMagnet)

        // A new title can reuse the same source id, so the id check alone is not
        // enough to distinguish the stale callback from the active session.
        backend.load(PlayerLoadRequest(
            mediaUrl = "https://media.example/new-title.mp4",
            title = "New title",
            initialSourceId = "magnet"
        ))
        val generationAfterNewLoad = privateField<Long>("loadToken")

        oldResolution("magnet://resolved", "http://127.0.0.1:8080/new-title")

        assertEquals(generationAfterNewLoad, privateField<Long>("loadToken"))
    }

    private fun setPrivateField(name: String, value: Any?) {
        ExoPlayerBackend::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(backend, value)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(name: String): T =
        ExoPlayerBackend::class.java.getDeclaredField(name).let { field ->
            field.isAccessible = true
            field.get(backend) as T
        }
}
