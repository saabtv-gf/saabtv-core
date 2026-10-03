package com.saab.tv.ui.player.base

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme as Material3Theme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class PlayerScaffoldFakePlaybackInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val playback = FakePlaybackController()
    private var backCalls = 0
    private var nextEpisodeCalls = 0
    private var seekPreviewCallbacks = mutableListOf<Long>()

    private fun show(
        nextEpisodeInfo: NextEpisodeInfo? = null,
        onAutoplayNextEpisode: ((String?, Long, Long?) -> Unit)? = null,
        skipSegmentInfo: SkipSegmentInfo? = null,
        autoSkipIntro: Boolean = true,
        seekThumbnailProvider: (suspend (Long) -> Bitmap?)? = null,
        seekThumbnailIntervalSeconds: Int = 30
    ) {
        compose.setContent {
            Material3Theme {
                MaterialTheme {
                    BasePlayerScaffold(
                        playbackController = playback,
                        renderSurface = FakeRenderSurface,
                        seekThumbnailProvider = seekThumbnailProvider,
                        seekThumbnailCacheKey = "fake-source",
                        seekThumbnailIntervalSeconds = seekThumbnailIntervalSeconds,
                        onSeekPreviewPosition = { seekPreviewCallbacks += it },
                        title = "Fake Film",
                        mediaType = "movie",
                        onBack = { backCalls++ },
                        skipSegmentInfo = skipSegmentInfo,
                        autoSkipIntro = autoSkipIntro,
                        onSourceChosen = {},
                        nextEpisodeInfo = nextEpisodeInfo,
                        onAutoplayNextEpisode = onAutoplayNextEpisode
                    )
                }
            }
        }
    }

    @Test fun playbackControlsDriveFakeControllerAndReflectItsState() {
        show()

        compose.onNodeWithContentDescription("Pause").assertExists()
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(1_000) { playback.pauseCalls == 1 }
        compose.runOnIdle {
            assertEquals(1, playback.pauseCalls)
            assertFalse(playback.uiState.value.playWhenReady)
        }
        compose.onNodeWithContentDescription("Play").assertExists()
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle {
            assertEquals(1, playback.playCalls)
            assertTrue(playback.uiState.value.playWhenReady)
        }
        assertEquals(0, backCalls)
    }

    @Test fun subtitleControlTracksAvailabilityFromFakePlayback() {
        playback.subtitleTracks.value = listOf(
            PlayerTrackOption(id = "en", label = "English SDH", language = "en")
        )
        show()

        compose.onNodeWithContentDescription("Subtitles").assertExists()
        compose.runOnIdle { playback.subtitleTracks.value = emptyList() }
        compose.onNodeWithContentDescription("Subtitles").assertDoesNotExist()
    }

    @Test fun playbackErrorShowsMessageAndBackAction() {
        playback.uiState.value = playback.uiState.value.copy(
            isReady = false, isPlaying = false, playWhenReady = false,
            errorMessage = "Network unavailable"
        )
        show()

        compose.onNodeWithText("Network unavailable").assertExists()
        compose.onNodeWithText("Back").assertExists()
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, backCalls) }
    }

    @Test fun seekBarPreviewsThenCommitsConfiguredInterval() {
        show()
        compose.onNodeWithContentDescription("Pause")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithContentDescription("Pause").performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionCenter)
        }

        compose.runOnIdle {
            assertEquals(30_000L, playback.uiState.value.positionMs)
            assertEquals(1, playback.pauseCalls)
            assertEquals(1, playback.playCalls)
        }
    }

    @Test fun thumbnailSeekLoadsCenteredCarouselBeforeConfirmingSeek() {
        playback.uiState.value = playback.uiState.value.copy(positionMs = 50_000L)
        val requestedPositions = mutableListOf<Long>()
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        show(
            seekThumbnailProvider = { position ->
                requestedPositions += position
                bitmap
            }
        )
        compose.mainClock.autoAdvance = false

        compose.onNodeWithContentDescription("Pause")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithContentDescription("Pause").performKeyInput { pressKey(Key.DirectionRight) }
        compose.mainClock.advanceTimeBy(100L)
        compose.waitForIdle()

        assertEquals(listOf(90_000L, 60_000L, 120_000L, 30_000L, 150_000L), requestedPositions)
        compose.onAllNodes(hasContentDescription("Scene preview at", substring = true)).assertCountEquals(5)
        compose.onNodeWithContentDescription("Scene preview at 1:30").assertExists()
        compose.runOnIdle {
            assertEquals(50_000L, playback.uiState.value.positionMs)
            assertTrue(seekPreviewCallbacks.isEmpty())
        }
        bitmap.recycle()
    }

    @Test fun skipIntroJumpsToTheDetectedSegmentEnd() {
        playback.uiState.value = playback.uiState.value.copy(positionMs = 15_000L)
        show(
            skipSegmentInfo = SkipSegmentInfo(introStartMs = 10_000L, introEndMs = 42_000L),
            autoSkipIntro = false
        )

        compose.onNodeWithText("Skip Intro")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(42_000L, playback.uiState.value.positionMs) }
    }

    @Test fun nextEpisodeControlUsesCurrentPositionAndDuration() {
        playback.uiState.value = playback.uiState.value.copy(positionMs = 121_000L, isEnded = true, isPlaying = false, playWhenReady = false)
        show(
            nextEpisodeInfo = NextEpisodeInfo("Next", null, 1, 2),
            onAutoplayNextEpisode = { _, position, duration ->
                assertEquals(121_000L, position)
                assertEquals(600_000L, duration)
                nextEpisodeCalls++
            }
        )

        compose.onNodeWithText("Play Next Episode")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, nextEpisodeCalls) }
    }

}

private class FakePlaybackController : PlayerPlaybackController {
    override val backendType = PlayerBackendType.EXOPLAYER
    override val uiState = MutableStateFlow(
        PlayerUiState(isReady = true, isPlaying = true, playWhenReady = true, durationMs = 600_000L)
    )
    override val sourceOptions = MutableStateFlow(emptyList<PlayerSourceOption>())
    override val audioTracks = MutableStateFlow(emptyList<PlayerTrackOption>())
    override val subtitleTracks = MutableStateFlow(emptyList<PlayerTrackOption>())

    var playCalls = 0
    var pauseCalls = 0
    var selectedSubtitleId: String? = null

    override fun load(request: PlayerLoadRequest) = Unit
    override fun play() {
        playCalls++
        uiState.value = uiState.value.copy(isPlaying = true, playWhenReady = true)
    }
    override fun pause() {
        pauseCalls++
        uiState.value = uiState.value.copy(isPlaying = false, playWhenReady = false)
    }
    override fun togglePlayPause() {
        if (uiState.value.playWhenReady) pause() else play()
    }
    override fun seekTo(positionMs: Long) { uiState.value = uiState.value.copy(positionMs = positionMs) }
    override fun seekBy(deltaMs: Long) { seekTo((uiState.value.positionMs + deltaMs).coerceAtLeast(0L)) }
    override fun setPlaybackSpeed(speed: Float) { uiState.value = uiState.value.copy(playbackSpeed = speed) }
    override fun selectSource(sourceId: String) { uiState.value = uiState.value.copy(currentSourceId = sourceId) }
    override fun selectAudioTrack(trackId: String?) { uiState.value = uiState.value.copy(selectedAudioTrackId = trackId) }
    override fun selectSubtitleTrack(trackId: String?) {
        selectedSubtitleId = trackId
        uiState.value = uiState.value.copy(selectedSubtitleTrackId = trackId, subtitleSelectionWasManual = true)
    }
    override fun setSubtitleVerticalOffset(percent: Int) { uiState.value = uiState.value.copy(subtitleVerticalOffsetPercent = percent) }
    override fun setSubtitleSize(percent: Int) { uiState.value = uiState.value.copy(subtitleSizePercent = percent) }
    override fun setSubtitleDelay(delayMs: Long) { uiState.value = uiState.value.copy(subtitleDelayMs = delayMs) }
    override fun setSubtitleTextColor(color: Int) { uiState.value = uiState.value.copy(subtitleTextColor = color) }
    override fun setSubtitleBackgroundColor(color: Int) { uiState.value = uiState.value.copy(subtitleBackgroundColor = color) }
    override fun release() = Unit
}

private object FakeRenderSurface : PlayerRenderSurface {
    override val backendType = PlayerBackendType.EXOPLAYER
    @Composable override fun Content(modifier: Modifier) { Text("Fake Video Surface", modifier.fillMaxSize()) }
}
