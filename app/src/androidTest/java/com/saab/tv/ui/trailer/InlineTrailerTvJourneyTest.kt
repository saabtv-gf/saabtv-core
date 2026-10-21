package com.saab.tv.ui.trailer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.testfixture.ComposeTestActivity
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Remote-control journey for inline-trailer actions on an Android TV Compose host. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class InlineTrailerTvJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestActivity>()

    private val relatedTitle = MetaItem("tmdb:related", "series", "Related Series")

    private fun render(
        onWatch: (MetaItem) -> Unit = {},
        onFullscreen: () -> Unit = {},
        onMute: () -> Unit = {},
        onDismiss: () -> Unit = {}
    ) {
        val player = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java)
        ) { _, method, _ -> error("Video rendering is disabled in this control journey: ${method.name}") } as ExoPlayer
        val session = InlineTrailerSession(
            owner = Any(),
            item = relatedTitle,
            player = player,
            onInteraction = {},
            onMute = onMute,
            onWatch = onWatch,
            onWatchlist = {},
            onFullscreen = onFullscreen,
            onDismiss = onDismiss,
            onNavigate = {}
        )
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(1280.dp, 720.dp)) {
                    InlineTrailerCard(session, renderVideo = false)
                }
            }
        }
    }

    @Test fun remoteSelectStartsTheExactRelatedTitle() {
        var selected: MetaItem? = null
        render(onWatch = { selected = it })

        compose.onNodeWithContentDescription("Start Watching")
            .performKeyInput { pressKey(Key.DirectionCenter) }

        compose.runOnIdle {
            assertEquals(relatedTitle.id, selected?.id)
            assertEquals(relatedTitle.type, selected?.type)
        }
    }

    @Test fun fullscreenAndAudioActionsStayOnTheTrailerUntilTheHostHandlesHandoff() {
        var fullscreenRequests = 0
        var muteRequests = 0
        var watchedTitles = 0
        render(
            onWatch = { watchedTitles++ },
            onFullscreen = { fullscreenRequests++ },
            onMute = { muteRequests++ }
        )

        compose.onNodeWithContentDescription("Fullscreen").performClick()
        compose.onNodeWithContentDescription("Unmute").performClick()

        compose.runOnIdle {
            assertEquals(1, fullscreenRequests)
            assertEquals(1, muteRequests)
            assertEquals("fullscreen handoff must not also start the title", 0, watchedTitles)
        }
    }

    @Test fun remoteBackDismissesTheTrailerWithoutStartingTheTitle() {
        var dismissals = 0
        var watchedTitles = 0
        render(onWatch = { watchedTitles++ }, onDismiss = { dismissals++ })

        compose.onNodeWithContentDescription("Start Watching")
            .performKeyInput { pressKey(Key.Back) }

        compose.runOnIdle {
            assertEquals(1, dismissals)
            assertEquals(0, watchedTitles)
        }
    }
}
