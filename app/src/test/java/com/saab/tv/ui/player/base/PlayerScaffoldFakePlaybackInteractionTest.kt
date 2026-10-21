package com.saab.tv.ui.player.base

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme as Material3Theme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.tv.material3.MaterialTheme
import com.saab.tv.R
import com.saab.tv.data.localization.AppLanguage
import com.saab.tv.data.model.stremio.MetaVideo
import com.saab.tv.data.model.stremio.Stream
import com.saab.tv.data.tmdb.TmdbCastInfo
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
import org.robolectric.RuntimeEnvironment
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
    private var castLoadCalls = 0

    private fun show(
        nextEpisodeInfo: NextEpisodeInfo? = null,
        onAutoplayNextEpisode: ((String?, Long, Long?) -> Unit)? = null,
        skipSegmentInfo: SkipSegmentInfo? = null,
        skipIntroEnabled: Boolean = true,
        skipRecapEnabled: Boolean = true,
        autoSkipIntro: Boolean = true,
        introSkipCountdownSeconds: Int = 5,
        seekThumbnailProvider: (suspend (Long) -> Bitmap?)? = null,
        seekTimeIntervalSeconds: Int = 30,
        episodes: List<MetaVideo> = emptyList(),
        castMembers: List<TmdbCastInfo> = emptyList(),
        currentPlaybackId: String? = null,
        languageTag: String? = null
    ) {
        val localizedContext = languageTag?.let {
            AppLanguage.localizedContext(RuntimeEnvironment.getApplication(), it)
        }
        compose.setContent {
            val playerContent: @Composable () -> Unit = {
                Material3Theme {
                    MaterialTheme {
                    BasePlayerScaffold(
                        playbackController = playback,
                        renderSurface = FakeRenderSurface,
                        seekThumbnailProvider = seekThumbnailProvider,
                        seekThumbnailCacheKey = "fake-source",
                        seekTimeIntervalSeconds = seekTimeIntervalSeconds,
                        onSeekPreviewPosition = { seekPreviewCallbacks += it },
                        title = "Fake Film",
                        mediaType = "movie",
                        castMembers = castMembers,
                        onLoadCast = { castLoadCalls++ },
                        onBack = { backCalls++ },
                        skipSegmentInfo = skipSegmentInfo,
                        skipIntroEnabled = skipIntroEnabled,
                        skipRecapEnabled = skipRecapEnabled,
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
            if (localizedContext == null) playerContent()
            else CompositionLocalProvider(LocalContext provides localizedContext, content = playerContent)
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

    @Test fun castPanelLoadsCastSupportsDpadMovementAndBackButtonReturnsToControls() {
        show(castMembers = listOf(
            TmdbCastInfo("Lead Actor", "Hero", null, 1),
            TmdbCastInfo("Second Actor", "Friend", null, 2)
        ))

        compose.onNodeWithTag("player-cast-control", useUnmergedTree = true)
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(2_000) { castLoadCalls > 0 }
        compose.waitUntil(2_000) {
            compose.onAllNodesWithContentDescription("Back to playback")
                .fetchSemanticsNodes().isNotEmpty()
        }
        val castPanelBounds = compose.onNodeWithTag("playback-cast-panel")
            .fetchSemanticsNode().boundsInRoot
        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Cast panel should be docked on the right", castPanelBounds.left >= rootBounds.width / 2f)
        assertTrue("Cast panel should use a compact width", castPanelBounds.width <= 380f)
        compose.onNodeWithText("Lead Actor").assertExists()
        compose.onNodeWithText("Second Actor").assertExists()
        val avatarBounds = compose.onNodeWithTag("playback-cast-avatar-0", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertEquals("Avatar should remain a compact square", avatarBounds.width, avatarBounds.height, 0.5f)
        assertTrue("Focus outline should be constrained to the avatar", avatarBounds.width < castPanelBounds.width / 2f)
        assertTrue(
            "Cast members should be stacked vertically",
            compose.onNodeWithText("Second Actor").fetchSemanticsNode().boundsInRoot.top >
                compose.onNodeWithText("Lead Actor").fetchSemanticsNode().boundsInRoot.top
        )
        assertTrue(castLoadCalls > 0)
        compose.onNodeWithContentDescription("Back to playback").assertIsFocused()

        compose.onNodeWithContentDescription("Back to playback")
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Lead Actor").assertIsFocused()
        compose.onNodeWithText("Lead Actor")
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Second Actor").assertIsFocused()

        compose.onNodeWithContentDescription("Back to playback")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performClick()
        compose.waitUntil(2_000) {
            compose.onAllNodesWithContentDescription("Back to playback").fetchSemanticsNodes().isEmpty() &&
                runCatching { compose.onNodeWithContentDescription("Pause").assertIsFocused() }.isSuccess
        }
        compose.onNodeWithContentDescription("Pause").assertIsFocused()
    }

    @Test fun localizedPlaybackControlDescriptionStillExecutesThePlayerAction() {
        val localized = AppLanguage.localizedContext(RuntimeEnvironment.getApplication(), "kn")
        show(languageTag = "kn")

        compose.onNodeWithContentDescription(localized.getString(R.string.player_pause))
            .assertExists()
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }

        compose.waitUntil(1_000) { playback.pauseCalls == 1 }
        assertEquals(1, playback.pauseCalls)
        assertFalse(playback.uiState.value.playWhenReady)
    }

    @Test fun seekThumbnailSceneAnnouncementUsesTheSelectedLocale() {
        val localized = AppLanguage.localizedContext(RuntimeEnvironment.getApplication(), "ml")
        playback.uiState.value = playback.uiState.value.copy(positionMs = 50_000L)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        show(languageTag = "ml", seekThumbnailProvider = { bitmap })
        compose.mainClock.autoAdvance = false

        compose.onNodeWithContentDescription(localized.getString(R.string.player_pause))
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithContentDescription(localized.getString(R.string.player_pause))
            .performKeyInput { pressKey(Key.DirectionRight) }
        compose.mainClock.advanceTimeBy(100L)
        compose.waitForIdle()

        compose.onNodeWithContentDescription(
            localized.getString(R.string.player_scene_preview_at, "1:30")
        ).assertExists()
        bitmap.recycle()
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

    @Test fun playbackErrorCanAdvanceToTheNextRankedSource() {
        val first = PlayerSourceOption(
            id = "first", url = "https://fixture.invalid/first.mkv", label = "First source"
        )
        val second = PlayerSourceOption(
            id = "second", url = "https://fixture.invalid/second.mkv", label = "Next source"
        )
        playback.sourceOptions.value = listOf(first, second)
        playback.uiState.value = playback.uiState.value.copy(
            isReady = false,
            isPlaying = false,
            playWhenReady = false,
            currentSourceId = first.id,
            errorMessage = "This source timed out. Try another source."
        )
        show()

        compose.onNodeWithText("Next Source").assertExists()
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }

        compose.runOnIdle {
            assertEquals(second.id, playback.uiState.value.currentSourceId)
            assertEquals(listOf(second), chosenSources)
        }
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

        val skipIntro = compose.onNodeWithText("Skip Intro")
        val boundsBeforeFocus = skipIntro.fetchSemanticsNode().boundsInRoot
        skipIntro.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle { assertEquals(boundsBeforeFocus, skipIntro.fetchSemanticsNode().boundsInRoot) }
        skipIntro.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(42_000L, playback.uiState.value.positionMs) }
    }

    @Test fun skipRecapSettingHidesOnlyTheRecapAction() {
        playback.uiState.value = playback.uiState.value.copy(positionMs = 10_000L)
        show(
            skipSegmentInfo = SkipSegmentInfo(
                introStartMs = 5_000L, introEndMs = 8_000L,
                recapStartMs = 9_000L, recapEndMs = 30_000L
            ),
            skipIntroEnabled = true,
            skipRecapEnabled = false,
            autoSkipIntro = false
        )

        compose.onNodeWithText("Skip Recap").assertDoesNotExist()
        compose.onNodeWithText("Skip Intro").assertDoesNotExist()
    }

    @Test fun skipRecapCanBeDisabledWithoutDisablingIntroSkipping() {
        playback.uiState.value = playback.uiState.value.copy(positionMs = 6_000L)
        show(
            skipSegmentInfo = SkipSegmentInfo(
                introStartMs = 5_000L, introEndMs = 8_000L,
                recapStartMs = 5_000L, recapEndMs = 30_000L
            ),
            skipIntroEnabled = true,
            skipRecapEnabled = false,
            autoSkipIntro = false
        )

        compose.onNodeWithText("Skip Intro").assertExists()
        compose.onNodeWithText("Skip Recap").assertDoesNotExist()
    }

    @Test fun skipIntroCanBeDisabledWithoutDisablingRecapSkipping() {
        playback.uiState.value = playback.uiState.value.copy(positionMs = 10_000L)
        show(
            skipSegmentInfo = SkipSegmentInfo(
                introStartMs = 5_000L, introEndMs = 8_000L,
                recapStartMs = 9_000L, recapEndMs = 30_000L
            ),
            skipIntroEnabled = false,
            skipRecapEnabled = true,
            autoSkipIntro = false
        )

        compose.onNodeWithText("Skip Intro").assertDoesNotExist()
        compose.onNodeWithText("Skip Recap").assertExists()
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

        compose.onNodeWithText("NEXT EPISODE IN", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Play Next Episode")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, nextEpisodeCalls) }
    }

    @Test fun audioPanelGroupsTracksByLanguageAndSelectsTheLanguageTrack() {
        val preferredLanguages = androidx.compose.runtime.mutableStateOf(listOf("en", "hi"))
        playback.audioTracks.value = listOf(
            PlayerTrackOption("en-main", "English Stereo", "en", selected = true, audioFormat = "AAC 2.0"),
            PlayerTrackOption("hi-main", "Hindi Stereo", "hi", audioFormat = "EAC3 5.1"),
            PlayerTrackOption("ta-main", "Tamil Stereo", "ta", audioFormat = "AAC 2.0")
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
                        preferredAudioLanguages = preferredLanguages.value,
                        onClose = {},
                        onSelectTrack = { playback.selectAudioTrack(it) }
                    )
                }
            } }
        }
        compose.onNodeWithText("Audio Tracks").assertExists()
        compose.onNodeWithText("Language preference: English › Hindi").assertExists()
        compose.onNodeWithText("Preferred languages").assertExists()
        compose.onNodeWithText("Other languages").assertExists()
        assertTrue(
            "Preferred languages should appear before other languages",
            compose.onNodeWithText("Hindi").fetchSemanticsNode().boundsInRoot.top <
                compose.onNodeWithText("Other languages").fetchSemanticsNode().boundsInRoot.top
        )
        assertTrue(
            "Other language entries should appear after their section heading",
            compose.onNodeWithText("Other languages").fetchSemanticsNode().boundsInRoot.top <
                compose.onNodeWithText("Tamil").fetchSemanticsNode().boundsInRoot.top
        )
        compose.onNodeWithText("Languages").assertExists()
        compose.onNodeWithText("Tracks").assertExists()
        compose.onNodeWithText("Hindi").performClick()

        compose.waitUntil(2_000) { playback.uiState.value.selectedAudioTrackId == "hi-main" }
        assertEquals("hi-main", playback.uiState.value.selectedAudioTrackId)
        assertEquals("A playback track choice must not change saved preferences", listOf("en", "hi"), preferredLanguages.value)
        compose.onNodeWithText("Language preference: English › Hindi").assertExists()
    }

    @Test fun audioPanelSwitchesToAlternateSourceWhenLanguageIsNotOnCurrentStream() {
        val currentSourceId = androidx.compose.runtime.mutableStateOf("english-source")
        val preferredLanguages = listOf("en", "hi")
        val sources = listOf(
            PlayerSourceOption(
                id = "english-source",
                url = "https://fixture.invalid/english.mkv",
                label = "English 1080p",
                audioLanguages = listOf("en")
            ),
            PlayerSourceOption(
                id = "hindi-source",
                url = "https://fixture.invalid/hindi.mkv",
                label = "Hindi 720p",
                audioLanguages = listOf("hi")
            )
        )
        playback.audioTracks.value = listOf(
            PlayerTrackOption("en-main", "English Stereo", "en", selected = true)
        )
        playback.uiState.value = playback.uiState.value.copy(
            currentSourceId = currentSourceId.value,
            selectedAudioTrackId = "en-main"
        )
        playback.sourceAudioTracks = mapOf(
            "hindi-source" to listOf(PlayerTrackOption("hi-main", "Hindi Stereo", "hi", selected = true))
        )

        compose.setContent {
            Material3Theme { MaterialTheme {
                val uiState = playback.uiState.collectAsState().value
                Box(Modifier.fillMaxSize()) {
                    AudioSelectionSidePanel(
                        visible = true,
                        title = "Audio Tracks",
                        audioTracks = playback.audioTracks.collectAsState().value,
                        sources = sources,
                        currentSourceId = currentSourceId.value,
                        selectedAudioId = uiState.selectedAudioTrackId,
                        preferredAudioLanguages = preferredLanguages,
                        onClose = {},
                        onSelectTrack = { playback.selectAudioTrack(it) },
                        onSelectSourceForLanguage = { sourceId, language ->
                            currentSourceId.value = sourceId
                            playback.selectSourceForAudioLanguage(sourceId, language)
                        }
                    )
                }
            } }
        }

        compose.onAllNodesWithText("Hindi", substring = false).get(0).performClick()

        compose.waitUntil(2_000) {
            playback.uiState.value.currentSourceId == "hindi-source"
        }
        assertEquals("hindi-source", playback.uiState.value.currentSourceId)
        assertEquals("hi", playback.requestedSourceLanguage)
        assertEquals("hi-main", playback.uiState.value.selectedAudioTrackId)
        assertEquals("Selecting a language-specific source must not promote its language", listOf("en", "hi"), preferredLanguages)
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

    @Test fun subtitleOffChoiceUsesAppLanguageAndKeepsCanonicalTrackId() {
        val localized = AppLanguage.localizedContext(RuntimeEnvironment.getApplication(), "ml")
        playback.subtitleTracks.value = listOf(
            PlayerTrackOption("#none", "Off", null),
            PlayerTrackOption("en-sub", "English SDH", "en", selected = true)
        )
        playback.uiState.value = playback.uiState.value.copy(selectedSubtitleTrackId = "en-sub")

        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized) {
                Material3Theme { MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        SubtitleSelectionSidePanel(
                            visible = true,
                            title = "Subtitles",
                            subtitleTracks = playback.subtitleTracks.collectAsState().value,
                            selectedSubtitleId = playback.uiState.collectAsState().value.selectedSubtitleTrackId,
                            onClose = {},
                            onSelectTrack = { playback.selectSubtitleTrack(it) }
                        )
                    }
                } }
            }
        }

        val localizedOff = localized.getString(R.string.common_off)
        compose.onNode(hasText(localizedOff, substring = true) and hasClickAction()).performClick()
        compose.onNodeWithText(
            localized.getString(R.string.player_current_track, localizedOff),
            substring = false
        ).assertExists()
        compose.waitUntil(2_000) { playback.uiState.value.selectedSubtitleTrackId == "#none" }
        assertEquals("#none", playback.selectedSubtitleId)
    }

    @Test fun sourceLanguagePanelSelectsBestMatchingSourceAndExposesProviderList() {
        var selectedAudioLanguage = ""
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
                        onAudioLanguageSelected = { selectedAudioLanguage = it },
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
        assertEquals("hi", selectedAudioLanguage)
    }

    @Test fun playerSourceLanguageChoiceDoesNotUpdateTheSharedAudioPreference() {
        var chosenSource = ""
        val englishUrl = "https://fixture.invalid/english.mkv"
        val hindiUrl = "https://fixture.invalid/hindi.mkv"
        val sources = listOf(
            PlayerSourceOption(
                id = englishUrl,
                url = englishUrl,
                label = "English 1080p",
                name = "[Torrentio]",
                title = "Fake Film English 1080p"
            ),
            PlayerSourceOption(
                id = hindiUrl,
                url = hindiUrl,
                label = "Hindi 720p",
                name = "[MediaFusion]",
                title = "Fake Film Hindi 720p"
            )
        )
        compose.setContent {
            Material3Theme { MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    PlayerSourceSidebar(
                        visible = true,
                        title = "Fake Film",
                        sources = sources,
                        currentSourceId = englishUrl,
                        onClose = {},
                        onLanguageSelectSource = { chosenSource = it },
                        onSelectSource = { chosenSource = it }
                    )
                }
            } }
        }

        compose.onAllNodesWithText("Hindi", substring = true)
            .filter(hasClickAction())
            .onFirst()
            .performClick()
        compose.waitUntil(2_000) { chosenSource == hindiUrl }
        assertEquals(hindiUrl, chosenSource)
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
    var requestedSourceLanguage: String? = null
    var sourceAudioTracks: Map<String, List<PlayerTrackOption>> = emptyMap()

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
    override fun selectSourceForAudioLanguage(sourceId: String, language: String) {
        requestedSourceLanguage = language
        selectSource(sourceId)
        val tracks = sourceAudioTracks[sourceId].orEmpty()
        if (tracks.isNotEmpty()) {
            audioTracks.value = tracks
            val selected = tracks.firstOrNull {
                AudioTrackPreferencePolicy.normalizeTrackLanguage(it.language, it.label) == language
            }
            selected?.let { selectAudioTrack(it.id) }
        }
    }
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
