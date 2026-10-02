package com.saab.tv.ui.trailer

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import com.saab.tv.data.model.stremio.MetaItem
import java.lang.reflect.Proxy
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class InlineTrailerInteractionP0Test {
    @get:Rule val compose=createComposeRule()
    private var watched=0; private var full=0; private var dismissed=0; private var listed=0; private var muted=0
    private lateinit var session:InlineTrailerSession
    private fun show(watchlisted:Boolean=false) {
        val player=Proxy.newProxyInstance(ExoPlayer::class.java.classLoader,arrayOf(ExoPlayer::class.java)) { _,method,_ ->
            error("No decoder should be touched in control test: ${method.name}") } as ExoPlayer
        session=InlineTrailerSession(Any(),MetaItem("tt1","movie","Movie"),player,{}, {muted++},{watched++},{listed++},{full++},{dismissed++},{})
        session.watchlisted=watchlisted
        compose.setContent { MaterialTheme { Box(Modifier.size(640.dp,360.dp)) { InlineTrailerCard(session,renderVideo=false) } } }
    }
    @Test fun startWatchingRemoteEnterFiresOnceAndDoesNotDismiss() {
        show();compose.onNodeWithContentDescription("Start Watching").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle {assertEquals(1,watched);assertEquals(0,dismissed)}
    }
    @Test fun fullscreenActionCallsHandoffAndDoesNotDismissInline() {
        show();compose.onNodeWithContentDescription("Fullscreen").performClick()
        compose.runOnIdle {assertEquals(1,full);assertEquals(0,dismissed)}
    }
    @Test fun backDismissesOnceOnKeyReleaseRatherThanStartingTitle() {
        show();compose.onNodeWithContentDescription("Start Watching").performKeyInput { pressKey(Key.Back) }
        compose.runOnIdle {assertEquals(1,dismissed);assertEquals(0,watched)}
    }
    @Test fun watchlistedStateExposesRemoveOnlyAndMuteRemainsClickable() {
        show(true);compose.onNodeWithContentDescription("Add To Watchlist").assertDoesNotExist()
        compose.onNodeWithContentDescription("Remove From Watchlist").performClick()
        compose.onNodeWithContentDescription("Unmute").performClick()
        compose.runOnIdle {assertEquals(1,listed);assertEquals(1,muted)}
    }
    @Test fun controlsRemainVisibleAfterIdleFrames() {
        show();compose.mainClock.advanceTimeBy(10_000)
        compose.onNodeWithContentDescription("Fullscreen").assertExists()
        compose.onNodeWithContentDescription("Start Watching").assertExists()
    }
    @Test fun oldFullscreenOwnerCannotClearNewFullscreenSession() {
        val old=Any();val current=Any()
        InlineTrailerAnchor.fullscreen(old,true);InlineTrailerAnchor.fullscreen(current,true)
        InlineTrailerAnchor.clearSession(old);assertTrue(InlineTrailerAnchor.fullscreenActive)
        InlineTrailerAnchor.clearSession(current);assertFalse(InlineTrailerAnchor.fullscreenActive)
    }
    @Test fun staleAnchorCannotClearNewTitleAndInvalidBoundsAreIgnored() {
        val a=MetaItem("tt1","movie","A");val b=MetaItem("tt2","movie","B");val rect=Rect(0f,0f,100f,150f)
        InlineTrailerAnchor.update(a,rect);InlineTrailerAnchor.update(b,rect)
        InlineTrailerAnchor.clearAnchor(a,rect);assertEquals("movie:tt2",InlineTrailerAnchor.key)
        InlineTrailerAnchor.update(a,Rect.Zero);assertEquals("movie:tt2",InlineTrailerAnchor.key)
        InlineTrailerAnchor.clearAnchor(b,rect)
    }
}
