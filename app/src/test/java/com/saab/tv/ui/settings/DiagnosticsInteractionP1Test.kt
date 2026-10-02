package com.saab.tv.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.saab.tv.AppDiagnostics
import com.saab.tv.data.player.PlaybackDiagnostics
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class DiagnosticsInteractionP1Test {
    @get:Rule val compose=createComposeRule()
    @Before fun reset() {
        val context=RuntimeEnvironment.getApplication()
        AppDiagnostics.clear(context); AppDiagnostics.setBasicEnabled(context,false); PlaybackDiagnostics.setEnabled(context,false)
    }
    @After fun cleanup() { AppDiagnostics.clear(RuntimeEnvironment.getApplication()) }
    private fun show() { compose.setContent { MaterialTheme { PlaybackDiagnosticsSettings {} } } }
    @Test fun refreshAndExportAndClearAreSeparateClickableControls() {
        show(); compose.onNodeWithText("Refresh").performClick(); compose.onNodeWithText("Updated").assertExists()
        compose.onNodeWithText("Export Report").assertHasClickAction()
        compose.onNodeWithText("Clear Logs").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("App and playback logs cleared.").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun basicToggleRecordsOnlyAfterExplicitActivation() {
        show(); assertFalse(AppDiagnostics.isBasicEnabled(RuntimeEnvironment.getApplication()))
        compose.onNodeWithText("Basic Logging").performClick()
        compose.runOnIdle { assertTrue(AppDiagnostics.isBasicEnabled(RuntimeEnvironment.getApplication())) }
        compose.onNodeWithText("Basic Logging").performClick()
        compose.runOnIdle { assertFalse(AppDiagnostics.isBasicEnabled(RuntimeEnvironment.getApplication())) }
    }
    @Test fun remoteCanReachRefreshExportAndClearFromLoggingControls() {
        show()
        compose.onNodeWithText("Basic Logging").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        compose.onNodeWithText("Basic Logging").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Detailed Logging").assertIsFocused()
        compose.onNodeWithText("Detailed Logging").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Refresh").assertIsFocused()
        compose.onNodeWithText("Refresh").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Export Report").assertIsFocused()
        compose.onNodeWithText("Export Report").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Clear Logs").assertIsFocused()
    }
}
