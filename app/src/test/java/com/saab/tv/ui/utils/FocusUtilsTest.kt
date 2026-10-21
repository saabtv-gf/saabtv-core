package com.saab.tv.ui.utils

import android.app.Application
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class FocusUtilsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun detachedSavedRequesterFallsBackToTheScreenEntry() {
        val detached = FocusRequester()
        val entry = FocusRequester()
        compose.setContent {
            Box(Modifier.size(100.dp).focusRequester(entry).focusable().testTag("content-entry"))
        }

        compose.runOnIdle { assertTrue(detached.requestFocusOrFallback(entry)) }
        compose.onNodeWithTag("content-entry").assertIsFocused()
    }

    @Test
    fun attachedSavedRequesterWinsWithoutFocusingTheFallback() {
        val saved = FocusRequester()
        val entry = FocusRequester()
        compose.setContent {
            Row {
                Box(Modifier.size(100.dp).focusRequester(saved).focusable().testTag("saved-card"))
                Box(Modifier.size(100.dp).focusRequester(entry).focusable().testTag("content-entry"))
            }
        }

        compose.runOnIdle { assertTrue(saved.requestFocusOrFallback(entry)) }
        compose.onNodeWithTag("saved-card").assertIsFocused()
        compose.onNodeWithTag("content-entry").assertIsNotFocused()
    }

    @Test
    fun attachedRequesterGetsFocusWithoutWallClockPolling() {
        val requester = FocusRequester()
        val result = AtomicReference<Boolean?>()
        compose.setContent {
            LaunchedEffect(Unit) { result.set(requester.requestFocusWhenAttached()) }
            Box(Modifier.size(100.dp).focusRequester(requester).focusable().testTag("attached-target"))
        }

        compose.waitUntil(1_000) { result.get() != null }
        assertTrue(result.get() == true)
        compose.onNodeWithTag("attached-target").assertIsFocused()
    }

    @Test
    fun safelyReportsWhenAnAttachedFocusRequestIsDispatched() {
        val blocked = FocusRequester()
        compose.setContent {
            Box(
                Modifier.size(100.dp)
                    .focusRequester(blocked)
                    .focusProperties { canFocus = false }
                    .focusable()
                    .testTag("blocked-target")
            )
        }

        compose.runOnIdle { assertTrue(blocked.requestFocusSafely()) }
        compose.onNodeWithTag("blocked-target").assertIsNotFocused()
    }

    @Test
    fun detachedRequesterStopsAfterBoundedFrameAttempts() {
        val detached = FocusRequester()
        val result = AtomicReference<Boolean?>()
        compose.setContent {
            LaunchedEffect(Unit) { result.set(detached.requestFocusWhenAttached(maxFrameAttempts = 3)) }
            Box(Modifier.size(100.dp).focusable().testTag("unrelated-target"))
        }

        compose.waitUntil(1_000) { result.get() != null }
        assertTrue(result.get() == false)
    }

    @Test
    fun focusGroupRestoresItsLastFocusedChild() {
        val first = FocusRequester()
        val last = FocusRequester()
        val outside = FocusRequester()
        compose.setContent {
            Column {
                Row(Modifier.rememberLastFocus().focusGroup()) {
                    Box(Modifier.size(100.dp).focusRequester(first).focusable().testTag("first-child"))
                    Box(Modifier.size(100.dp).focusRequester(last).focusable().testTag("last-child"))
                }
                Box(Modifier.size(100.dp).focusRequester(outside).focusable().testTag("outside"))
            }
        }

        compose.runOnIdle { last.requestFocus() }
        compose.onNodeWithTag("last-child").assertIsFocused()
        compose.runOnIdle { outside.requestFocus() }
        compose.onNodeWithTag("outside").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("last-child").assertIsFocused()
    }
}
