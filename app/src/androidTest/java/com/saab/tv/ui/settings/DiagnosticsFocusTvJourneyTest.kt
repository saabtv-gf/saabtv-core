package com.saab.tv.ui.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saab.tv.AppDiagnostics
import com.saab.tv.testfixture.ComposeTestActivity
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class DiagnosticsFocusTvJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestActivity>()

    @Test fun dpadCanReturnToDetachedHeaderAndReachAllReportButtons() {
        val base = compose.activity
        val directory = File(base.cacheDir, "diagnostics-focus-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        }
        try {
            val eventLog = File(context.filesDir, "app_diagnostics/events.log")
            eventLog.parentFile?.mkdirs()
            eventLog.writeText("${System.currentTimeMillis()}\tFATAL\tApplication\tUncaught Exception\tfixture crash\n")
            assertTrue(AppDiagnostics.report(context).events.any { it.event == "Uncaught Exception" })
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context) {
                    MaterialTheme {
                        PlaybackDiagnosticsSettings(onGoBack = {})
                    }
                }
            }
            repeat(3) {
                compose.onNodeWithText("Refresh")
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithText("Refresh").assertIsFocused(); true }
                        .getOrDefault(false)
                }
                compose.onNodeWithText("Refresh").performKeyInput { pressKey(Key.DirectionDown) }
                // The diagnostics events are below the tall, lazy header and are
                // not composed until the button's Down handler scrolls to them.
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithTag("diagnostic-event-0").fetchSemanticsNodes().isNotEmpty()
                }
                val firstLog = compose.onNodeWithTag("diagnostic-event-0")
                compose.waitUntil(5_000) {
                    runCatching { firstLog.assertIsFocused(); true }.getOrDefault(false)
                }
                compose.onNodeWithTag("app-diagnostics-list").performScrollToIndex(1)
                firstLog.performKeyInput { pressKey(Key.DirectionUp) }
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithText("Refresh").assertIsFocused(); true }
                        .getOrDefault(false)
                }
                compose.onNodeWithText("Refresh").assertIsDisplayed()
                compose.onNodeWithText("Refresh").performKeyInput { pressKey(Key.DirectionRight) }
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithText("Export Report").assertIsFocused(); true }
                        .getOrDefault(false)
                }
                compose.onNodeWithText("Export Report").performKeyInput { pressKey(Key.DirectionRight) }
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithText("Clear Logs").assertIsFocused(); true }
                        .getOrDefault(false)
                }
            }
        } finally { directory.deleteRecursively() }
    }
}
