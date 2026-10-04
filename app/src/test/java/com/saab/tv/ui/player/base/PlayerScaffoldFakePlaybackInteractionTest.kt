package com.saab.tv.ui.player.base

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme as Material3Theme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.tv.material3.MaterialTheme
import com.saab.tv.data.model.stremio.MetaVideo
import com.saab.tv.data.model.stremio.Stream
import com.saab.tv.ui.details.GlassSidebar
import com.saab.tv.ui.details.SidebarState
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
    private var chosenSources = mutableListOf<PlayerSourceOption>()
    private var chosenStreams = mutableListOf<Stream>()
    private var selectedEpisodes = mutableListOf<MetaVideo>()

    private fun show(
        nextEpisodeInfo: NextEpisodeInfo? = null,
        onAutoplayNextEpisode: ((String?, Long, Long?) -> Unit)? = null,
        skipSegmentInfo: SkipSegmentInfo? = null,
        autoSkipIntro: Boolean = true,
        introSkipCountdownSeconds: Int = 5,
        seekThumbnailProvider: (suspend (Long) -> Bitmap?)? = null,
        seekThumbnailIntervalSeconds: Int = 30,
        episodes: List<MetaVideo> = emptyList(),
        currentPlaybackId: String? = null
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
                        introSkipCountdownSeconds = introSkipCountdownSeconds,
                        onSourceChosen = { chosenSources += it },
                        nextEpisodeInfo = nextEpisodeInfo,
                        onAutoplayNextEpisode = onAutoplayNextEpisode,
                        episodes = episodes,
                        currentPlaybackId = currentPlaybackId,
                        onEpisodeSelected = { episode, _, _, _ -> selectedEpisodes += episode }
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
        assertEquals(1, playback.pauseCalls)
        assertFalse(playback.uiState.value.playWhenReady)
        compose.onNodeWithContentDescription("Play").assertExists()
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(1_000) { playback.playCalls == 1 }
        assertTrue(playback.uiState.value.playWhenReady)
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

    @Test fun introDbRecapAutoSkipsWithTheSharedCountdownAndWaitsWhilePaused() {
        playback.uiState.value = playback.uiState.value.copy(positionMs = 10_000L, isPlaying = false)
        compose.mainClock.autoAdvance = false
        show(
            skipSegmentInfo = SkipSegmentInfo(recapStartMs = 5_000L, recapEndMs = 30_000L),
            introSkipCountdownSeconds = 5
        )

        compose.mainClock.advanceTimeBy(6_000L)
        compose.runOnIdle { assertEquals(10_000L, playback.uiState.value.positionMs) }

        compose.runOnIdle { playback.uiState.value = playback.uiState.value.copy(isPlaying = true) }
        compose.mainClock.advanceTimeBy(5_500L)
        compose.runOnIdle { assertEquals(30_000L, playback.uiState.value.positionMs) }
    }

    @Test fun recapCanBeSkippedManuallyWhenAutoSkipIsOff() {
        playback.uiState.value = playback.uiState.value.copy(positionMs = 10_000L)
        show(
            skipSegmentInfo = SkipSegmentInfo(recapStartMs = 5_000L, recapEndMs = 30_000L),
            autoSkipIntro = false
        )

        compose.onNodeWithText("Skip Recap")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(30_000L, playback.uiState.value.positionMs) }
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

    @Test fun audioPanelGroupsTracksByLanguageAndSelectsTheLanguageTrack() {
        playback.audioTracks.value = listOf(
            PlayerTrackOption("en-main", "English Stereo", "en", selected = true, audioFormat = "AAC 2.0"),
            PlayerTrackOption("hi-main", "Hindi Stereo", "hi", audioFormat = "EAC3 5.1")
        )
        playback.uiState.value = playback.uiState.value.copy(selectedAudioTrackId = "en-main")
        compose.setContent {
            Material3Theme { MaterialTheme {
                val audioTracks = playback.audioTracks.collectAsState().value
                val uiState = playback.uiState.collectAsState().value
                Box(Modifier.fillMaxSize()) {
                    AudioSelectionSidePanel(
                        visible = true,
                        title = "Audio Tracks",
                        audioTracks = audioTracks,
                        selectedAudioId = uiState.selectedAudioTrackId,
                        onClose = {},
                        onSelectTrack = { playback.selectAudioTrack(it) }
                    )
                }
            } }
        }
        compose.onNodeWithText("Audio Tracks").assertExists()
        compose.onNodeWithText("Languages").assertExists()
        compose.onNodeWithText("Tracks").assertExists()
        compose.onNodeWithText("Hindi").performClick()

        compose.waitUntil(2_000) { playback.uiState.value.selectedAudioTrackId == "hi-main" }
        assertEquals("hi-main", playback.uiState.value.selectedAudioTrackId)
    }

    @Test fun subtitlePanelSelectsLanguageAndAllowsExplicitOffTrack() {
        playback.subtitleTracks.value = listOf(
            PlayerTrackOption("#none", "Off", null, selected = true),
            PlayerTrackOption("en-sub", "English SDH", "en", subtitleSourcePriority = SubtitleSourcePriority.EMBEDDED),
            PlayerTrackOption("fr-sub", "French", "fr")
        )
        playback.uiState.value = playback.uiState.value.copy(selectedSubtitleTrackId = "en-sub")
        compose.setContent {
            Material3Theme { MaterialTheme {
                val subtitleTracks = playback.subtitleTracks.collectAsState().value
                val uiState = playback.uiState.collectAsState().value
                Box(Modifier.fillMaxSize()) {
                    SubtitleSelectionSidePanel(
                        visible = true,
                        title = "Subtitles",
                        subtitleTracks = subtitleTracks,
                        selectedSubtitleId = uiState.selectedSubtitleTrackId,
                        onClose = {},
                        onSelectTrack = { playback.selectSubtitleTrack(it) }
                    )
                }
            } }
        }
        compose.onAllNodesWithText("Subtitles", substring = false).assertCountEquals(2)
        compose.onAllNodesWithText("French", substring = false).get(0).performClick()
        compose.waitUntil(2_000) { playback.uiState.value.selectedSubtitleTrackId == "fr-sub" }

        compose.onNodeWithText("Off").performClick()
        compose.waitUntil(2_000) { playback.uiState.value.selectedSubtitleTrackId == "#none" }
        assertEquals("#none", playback.selectedSubtitleId)
    }

    @Test fun sourceLanguagePanelSelectsBestMatchingSourceAndExposesProviderList() {
        val english = PlayerSourceOption(
            id = "english-source", url = "https://fixture.invalid/en.mkv", label = "English 1080p",
            name = "Torrentio", title = "Movie English 1080p"
        )
        val hindi = PlayerSourceOption(
            id = "hindi-source", url = "https://fixture.invalid/hi.mkv", label = "Hindi 720p",
            name = "MediaFusion", title = "Movie Hindi 720p"
        )
        playback.sourceOptions.value = listOf(english, hindi)
        compose.setContent {
            Material3Theme { MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    GlassSidebar(
                        state = SidebarState.Sources(
                            streamTitle = "Fake Film",
                            streams = listOf(
                                Stream(name = "[Torrentio]", title = "English 1080p", url = english.url),
                                Stream(name = "[MediaFusion]", title = "Hindi 720p", url = hindi.url)
                            ),
                            showBestLanguageOptions = true
                        ),
                        onEpisodeSelected = {},
                        onSourceSelected = { chosenStreams += it },
                        onLanguageSourceSelected = { chosenStreams += it },
                        onBack = {},
                        onDismiss = {}
                    )
                }
            } }
        }
        compose.onNodeWithText("Select Source").assertExists()
        compose.onNodeWithText("Source Language").assertExists()
        compose.onAllNodesWithText("Hindi", substring = false).get(0).performClick()

        compose.waitUntil(2_000) { chosenStreams.any { it.url == hindi.url } }
        assertEquals(hindi.url, chosenStreams.first().url)
    }

    @Test fun episodePanelSelectsRequestedEpisodeAndClosesAfterSelection() {
        playback.sourceOptions.value = listOf(
            PlayerSourceOption("current", "https://fixture.invalid/current.mkv", "Current", title = "English")
        )
        playback.uiState.value = playback.uiState.value.copy(currentSourceId = "current", positionMs = 125_000L)
        val episodes = listOf(
            MetaVideo(id = "show:s1e1", title = "Pilot", season = 1, episode = 1),
            MetaVideo(id = "show:s1e2", title = "Second Episode", season = 1, episode = 2)
        )
        compose.setContent {
            Material3Theme { MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    GlassSidebar(
                        state = SidebarState.Episodes(episodes),
                        currentEpisodeId = "show:s1e1",
                        onEpisodeSelected = { selectedEpisodes += it },
                        onSourceSelected = {},
                        onBack = {},
                        onDismiss = {}
                    )
                }
            } }
        }
        compose.onNodeWithText("More Episodes").assertExists()
        compose.onNode(hasClickAction() and hasText("S1 : E2", substring = true)).performClick()

        compose.runOnIdle {
            assertEquals(listOf(episodes[1]), selectedEpisodes)
        }
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
