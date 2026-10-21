package com.saab.tv.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
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
    private fun show() { compose.setContent { MaterialTheme { PlaybackDiagnosticsSettings(onGoBack = {}) } } }

    @Test fun visibleDiagnosticRowsDoNotRequestParentScroll() {
        assertFalse(shouldScrollDiagnosticItemIntoViewport(120, 80, 0, 720))
        assertFalse(shouldScrollDiagnosticItemIntoViewport(0, 720, 0, 720))
        assertTrue(shouldScrollDiagnosticItemIntoViewport(-1, 40, 0, 720))
        assertTrue(shouldScrollDiagnosticItemIntoViewport(700, 40, 0, 720))
        assertTrue(shouldScrollDiagnosticItemIntoViewport(null, null, 0, 720))
        assertTrue(shouldScrollDiagnosticItemIntoViewport(100, 0, 0, 720))
    }

    @Test fun refreshAndExportAndClearAreSeparateClickableControls() {
        show(); compose.onNodeWithText("Refresh").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Updated").fetchSemanticsNodes().isNotEmpty() }
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

    @Test fun activatingReadOnlyCrashRowDoesNotDismissDiagnosticsOrThrow() {
        val context = RuntimeEnvironment.getApplication()
        AppDiagnostics.failure(context, "Application", "Uncaught Exception", IllegalStateException("diagnostic crash row fixture"))
        assertTrue(AppDiagnostics.report(context).events.any {
            it.event == "Uncaught Exception" && it.details.contains("java.lang.IllegalStateException")
        })
        show()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Uncaught Exception").fetchSemanticsNodes().isNotEmpty()
        }

        val crashIndex = AppDiagnostics.report(context).events.indexOfFirst {
            it.event == "Uncaught Exception"
        }
        assertTrue("Crash diagnostic should be present", crashIndex >= 0)
        val crashRow = compose.onNodeWithTag("diagnostic-event-$crashIndex")
        crashRow.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        crashRow.assertIsFocused()
        crashRow.assertHasClickAction()
        assertEquals(
            context.getString(com.saab.tv.R.string.diagnostics_details_collapsed),
            crashRow.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        )
        crashRow.performKeyInput { pressKey(Key.Enter) }
        assertEquals(
            context.getString(com.saab.tv.R.string.diagnostics_details_expanded),
            crashRow.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        )
        crashRow.performKeyInput { pressKey(Key.DirectionCenter) }

        compose.onNodeWithText("Uncaught Exception").assertExists()
        crashRow.assertIsFocused()
        assertEquals(
            context.getString(com.saab.tv.R.string.diagnostics_details_collapsed),
            crashRow.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        )
    }

    @Test fun diagnosticsHeaderAndOlderEventsRemainReachableByScrolling() {
        val context = RuntimeEnvironment.getApplication()
        AppDiagnostics.setBasicEnabled(context, true)
        repeat(60) { index ->
            AppDiagnostics.event(context, "Scroll Test", "scroll-event-${index.toString().padStart(2, '0')}")
            Thread.sleep(5)
        }
        compose.waitUntil(10_000) {
            AppDiagnostics.report(context).events.any { it.event == "scroll-event-00" }
        }
        show()
        compose.onNodeWithText("Refresh").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("scroll-event-59").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("app-diagnostics-list")
            .performScrollToNode(hasText("scroll-event-00"))
        compose.onNodeWithText("scroll-event-00").assertIsDisplayed()
    }

    @Test fun remoteUpReattachesHeaderBeforeReturningFromFirstLogRow() {
        val context = RuntimeEnvironment.getApplication()
        repeat(5) {
            AppDiagnostics.failure(context, "Application", "Uncaught Exception", IllegalStateException("fixture"))
        }
        show()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Transfer Summary").fetchSemanticsNodes().isNotEmpty()
        }
        repeat(3) {
            compose.onNodeWithText("Refresh").performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.RequestFocus
            )
            compose.onNodeWithText("Refresh").performKeyInput { pressKey(Key.DirectionDown) }
            val firstRow = compose.onNodeWithTag("diagnostic-event-0")
            firstRow.assertIsFocused()
            compose.onNodeWithTag("app-diagnostics-list").performScrollToIndex(1)
            // Lazy-list retention differs by platform; the return path must work
            // whether the header is retained off-screen or fully detached.
            firstRow.performKeyInput { pressKey(Key.DirectionUp) }
            compose.onNodeWithText("Refresh").assertIsFocused()
            compose.onNodeWithText("Refresh").assertIsDisplayed()
        }
    }
}
