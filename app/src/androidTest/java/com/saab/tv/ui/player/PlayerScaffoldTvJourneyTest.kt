package com.saab.tv.ui.player

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme as Material3Theme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.tv.material3.MaterialTheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saab.tv.data.model.stremio.MetaVideo
import com.saab.tv.testfixture.ComposeTestActivity
import com.saab.tv.ui.player.base.BasePlayerScaffold
import com.saab.tv.ui.player.base.PlayerBackendType
import com.saab.tv.ui.player.base.PlayerLoadRequest
import com.saab.tv.ui.player.base.PlayerPlaybackController
import com.saab.tv.ui.player.base.PlayerRenderSurface
import com.saab.tv.ui.player.base.PlayerSourceOption
import com.saab.tv.ui.player.base.PlayerTrackOption
import com.saab.tv.ui.player.base.PlayerUiState
import com.saab.tv.ui.player.base.SubtitleSelectionSidePanel
import com.saab.tv.ui.details.GlassSidebar
import com.saab.tv.ui.details.SidebarState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real-device Compose/DPAD journeys around the player's visible remote controls and panels. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class PlayerScaffoldTvJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestActivity>()

    private val playback = TvFakePlaybackController()
    private val selectedEpisodes = mutableListOf<MetaVideo>()

    private fun render(episodes: List<MetaVideo> = emptyList(), currentPlaybackId: String? = null) {
        compose.setContent {
            Material3Theme {
                MaterialTheme {
                    BasePlayerScaffold(
                        playbackController = playback,
                        renderSurface = TvFakeRenderSurface,
                        title = "Device Test",
                        mediaType = "series",
                        onBack = {},
                        episodes = episodes,
                        currentPlaybackId = currentPlaybackId,
                        onEpisodeSelected = { episode, _, _, _ -> selectedEpisodes += episode }
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun dpadActivatesPlaybackControls() {
        render()
        compose.onNodeWithContentDescription("Pause")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(2_000) { playback.pauseCalls == 1 }
        compose.onNodeWithContentDescription("Play")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(2_000) { playback.playCalls == 1 }
        assertEquals(1, playback.playCalls)
        assertEquals(1, playback.pauseCalls)
    }

    @Test fun dpadSeekingCommitsInBothDirectionsAndClampsToTheMediaRange() {
        render()

        compose.onNodeWithContentDescription("Pause")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionUp) }

        fun seek(direction: Key) {
            compose.onNodeWithContentDescription("Pause")
                .performKeyInput {
                    pressKey(direction)
                    pressKey(Key.DirectionCenter)
                }
        }

        seek(Key.DirectionRight)
        compose.waitUntil(2_000) { playback.uiState.value.positionMs == 30_000L }
        seek(Key.DirectionLeft)
        compose.waitUntil(2_000) { playback.uiState.value.positionMs == 0L }
        seek(Key.DirectionLeft)
        compose.waitForIdle()
        assertEquals("Seeking backward at the start must clamp to zero", 0L, playback.uiState.value.positionMs)

        compose.runOnIdle {
            playback.uiState.value = playback.uiState.value.copy(positionMs = 590_000L)
        }
        seek(Key.DirectionRight)
        compose.waitUntil(2_000) { playback.uiState.value.positionMs == 600_000L }
        assertEquals("Seeking past duration must clamp to the media end", 600_000L, playback.uiState.value.positionMs)
    }

    @Test fun accessibleSeekSliderAnnouncesPositionAndSeeksToRequestedFraction() {
        render()

        val seekBar = compose.onNodeWithContentDescription("Playback progress")
        val initialSemantics = seekBar.fetchSemanticsNode().config
        assertEquals(
            ProgressBarRangeInfo(0f, 0f..1f),
            initialSemantics[SemanticsProperties.ProgressBarRangeInfo]
        )
        assertEquals("0:00 of 10:00", initialSemantics[SemanticsProperties.StateDescription])

        seekBar
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                assertEquals(true, setProgress(0.5f))
            }
        compose.waitUntil(2_000) { playback.uiState.value.positionMs == 300_000L }
        assertEquals(
            ProgressBarRangeInfo(0.5f, 0f..1f),
            compose.onNodeWithContentDescription("Playback progress")
                .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        )
        assertEquals(300_000L, playback.uiState.value.positionMs)
    }

    @Test fun playbackProgressFillMovesLinearlyBetweenBackendSnapshots() {
        playback.uiState.value = playback.uiState.value.copy(positionMs = 60_000L)
        render()
        compose.mainClock.autoAdvance = false
        val fill = compose.onNodeWithTag("player-progress-fill")
        val initialWidth = fill.fetchSemanticsNode().boundsInRoot.width

        compose.runOnIdle {
            playback.uiState.value = playback.uiState.value.copy(positionMs = 180_000L)
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
        val halfwayWidth = fill.fetchSemanticsNode().boundsInRoot.width

        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
        val finalWidth = fill.fetchSemanticsNode().boundsInRoot.width

        assertTrue(halfwayWidth > initialWidth)
        assertTrue(finalWidth > halfwayWidth)
        assertEquals((initialWidth + finalWidth) / 2f, halfwayWidth, 3f)
    }

    @Test fun subtitlePanelChangesToAnAvailableLanguageTrack() {
        val tracks = listOf(
            PlayerTrackOption("en", "English SDH", "en", selected = true),
            PlayerTrackOption("fr", "French", "fr")
        )
        compose.setContent {
            Material3Theme { MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    SubtitleSelectionSidePanel(
                        visible = true,
                        title = "Subtitles",
                        subtitleTracks = tracks,
                        selectedSubtitleId = "en",
                        onClose = {},
                        onSelectTrack = { selectedSubtitleTrackId = it }
                    )
                }
            } }
        }
        compose.onAllNodesWithText("Subtitles", substring = false).assertCountEquals(2)
        compose.onNodeWithText("French").performClick()
        compose.waitUntil(2_000) { selectedSubtitleTrackId == "fr" }
        assertEquals("fr", selectedSubtitleTrackId)
    }

    @Test fun episodePanelOpensAndSelectsTheRequestedEpisode() {
        val episodes = listOf(
            MetaVideo(id = "series:s1e1", title = "Pilot", season = 1, episode = 1),
            MetaVideo(id = "series:s1e2", title = "The Follow Up", season = 1, episode = 2)
        )
        compose.setContent {
            Material3Theme { MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    GlassSidebar(
                        state = SidebarState.Episodes(episodes),
                        currentEpisodeId = "series:s1e1",
                        onEpisodeSelected = { selectedEpisodes += it },
                        onSourceSelected = {},
                        onBack = {},
                        onDismiss = {}
                    )
                }
            } }
        }
        compose.onNodeWithText("More Episodes").assertExists()
        compose.onNodeWithTag("episodes-list").performScrollToIndex(1)
        compose.onNodeWithTag("episode-thumbnail-${episodes[1].id}").performClick()
        compose.waitUntil(2_000) { selectedEpisodes.isNotEmpty() }
        assertEquals(listOf(episodes[1]), selectedEpisodes)
    }

    private var selectedSubtitleTrackId: String? = null
}

private class TvFakePlaybackController : PlayerPlaybackController {
    override val backendType = PlayerBackendType.EXOPLAYER
    override val uiState = MutableStateFlow(PlayerUiState(
        isReady = true,
        isPlaying = true,
        playWhenReady = true,
        durationMs = 600_000L
    ))
    override val sourceOptions = MutableStateFlow(emptyList<PlayerSourceOption>())
    override val audioTracks = MutableStateFlow(emptyList<PlayerTrackOption>())
    override val subtitleTracks = MutableStateFlow(emptyList<PlayerTrackOption>())
    var playCalls = 0
    var pauseCalls = 0
    var selectedSubtitleTrackId: String? = null

    override fun load(request: PlayerLoadRequest) = Unit
    override fun play() { playCalls++; uiState.value = uiState.value.copy(isPlaying = true, playWhenReady = true) }
    override fun pause() { pauseCalls++; uiState.value = uiState.value.copy(isPlaying = false, playWhenReady = false) }
    override fun togglePlayPause() { if (uiState.value.playWhenReady) pause() else play() }
    override fun seekTo(positionMs: Long) { uiState.value = uiState.value.copy(positionMs = positionMs) }
    override fun seekBy(deltaMs: Long) { seekTo((uiState.value.positionMs + deltaMs).coerceAtLeast(0L)) }
    override fun setPlaybackSpeed(speed: Float) { uiState.value = uiState.value.copy(playbackSpeed = speed) }
    override fun selectSource(sourceId: String) { uiState.value = uiState.value.copy(currentSourceId = sourceId) }
    override fun selectAudioTrack(trackId: String?) { uiState.value = uiState.value.copy(selectedAudioTrackId = trackId) }
    override fun selectSubtitleTrack(trackId: String?) {
        selectedSubtitleTrackId = trackId
        uiState.value = uiState.value.copy(selectedSubtitleTrackId = trackId, subtitleSelectionWasManual = true)
    }
    override fun setSubtitleVerticalOffset(percent: Int) { uiState.value = uiState.value.copy(subtitleVerticalOffsetPercent = percent) }
    override fun setSubtitleSize(percent: Int) { uiState.value = uiState.value.copy(subtitleSizePercent = percent) }
    override fun setSubtitleDelay(delayMs: Long) { uiState.value = uiState.value.copy(subtitleDelayMs = delayMs) }
    override fun setSubtitleTextColor(color: Int) { uiState.value = uiState.value.copy(subtitleTextColor = color) }
    override fun setSubtitleBackgroundColor(color: Int) { uiState.value = uiState.value.copy(subtitleBackgroundColor = color) }
    override fun release() = Unit
}

private object TvFakeRenderSurface : PlayerRenderSurface {
    override val backendType = PlayerBackendType.EXOPLAYER
    @androidx.compose.runtime.Composable override fun Content(modifier: Modifier) {
        Text("Fake Video Surface", modifier.fillMaxSize())
    }
}
