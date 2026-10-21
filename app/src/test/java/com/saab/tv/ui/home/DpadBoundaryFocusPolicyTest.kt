package com.saab.tv.ui.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import com.saab.tv.ui.navigation.handoffNavigationFocus
import com.saab.tv.ui.navigation.isBackOrEscape
import com.saab.tv.ui.navigation.isInitialBackPress
import com.saab.tv.ui.navigation.isInitialDpadPress
import com.saab.tv.ui.navigation.isInitialKeyPress
import com.saab.tv.ui.navigation.NavigationFocusGuard
import com.saab.tv.ui.navigation.DpadFreshPressGate
import com.saab.tv.ui.navigation.dispatchFreshDpadAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DpadBoundaryFocusPolicyTest {
    @Test fun onlyFreshKeyDownCanTransferFocusAtContentBoundary() {
        assertTrue(KeyEvent(androidEvent(AndroidKeyEvent.ACTION_DOWN, repeatCount = 0)).isInitialDpadPress())
        assertFalse(KeyEvent(androidEvent(AndroidKeyEvent.ACTION_DOWN, repeatCount = 1)).isInitialDpadPress())
        assertFalse(KeyEvent(androidEvent(AndroidKeyEvent.ACTION_UP, repeatCount = 0)).isInitialDpadPress())
        val enter = KeyEvent(androidEvent(AndroidKeyEvent.ACTION_DOWN, repeatCount = 0, keyCode = AndroidKeyEvent.KEYCODE_ENTER))
        assertFalse(enter.isInitialDpadPress())
        assertTrue(enter.isInitialKeyPress())
        val center = KeyEvent(androidEvent(AndroidKeyEvent.ACTION_DOWN, repeatCount = 0, keyCode = AndroidKeyEvent.KEYCODE_DPAD_CENTER))
        assertFalse(center.isInitialDpadPress())
        assertTrue(center.isInitialKeyPress())
        assertFalse(KeyEvent(androidEvent(AndroidKeyEvent.ACTION_DOWN, repeatCount = 1, keyCode = AndroidKeyEvent.KEYCODE_DPAD_CENTER)).isInitialKeyPress())
    }

    @Test fun eachDirectionalKeyCanHandOffButCenterAndEnterCannot() {
        listOf(
            AndroidKeyEvent.KEYCODE_DPAD_UP,
            AndroidKeyEvent.KEYCODE_DPAD_DOWN,
            AndroidKeyEvent.KEYCODE_DPAD_LEFT,
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT
        ).forEach { keyCode ->
            assertTrue(KeyEvent(androidEvent(AndroidKeyEvent.ACTION_DOWN, 0, keyCode)).isInitialDpadPress())
        }
        listOf(AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER).forEach { keyCode ->
            assertFalse(KeyEvent(androidEvent(AndroidKeyEvent.ACTION_DOWN, 0, keyCode)).isInitialDpadPress())
        }
    }

    @Test fun backAndEscapeOnlyActOnTheirInitialKeyDown() {
        listOf(AndroidKeyEvent.KEYCODE_BACK, AndroidKeyEvent.KEYCODE_ESCAPE).forEach { keyCode ->
            val initial = KeyEvent(androidEvent(AndroidKeyEvent.ACTION_DOWN, 0, keyCode))
            val repeat = KeyEvent(androidEvent(AndroidKeyEvent.ACTION_DOWN, 1, keyCode))
            val release = KeyEvent(androidEvent(AndroidKeyEvent.ACTION_UP, 0, keyCode))
            assertTrue(initial.isBackOrEscape())
            assertTrue(repeat.isBackOrEscape())
            assertTrue(release.isBackOrEscape())
            assertTrue(initial.isInitialBackPress())
            assertFalse(repeat.isInitialBackPress())
            assertFalse(release.isInitialBackPress())
        }
        val enter = KeyEvent(androidEvent(AndroidKeyEvent.ACTION_DOWN, 0, AndroidKeyEvent.KEYCODE_ENTER))
        assertFalse(enter.isBackOrEscape())
        assertFalse(enter.isInitialBackPress())
    }

    @Test fun sharedHandoffAuthorizesOnlyTheInitialRemotePress() {
        val authorized = mutableListOf<KeyEvent?>()
        val handoff: (KeyEvent?) -> Boolean = { authorized += it; true }

        val initialPress = KeyEvent(androidEvent(AndroidKeyEvent.ACTION_DOWN, 0))
        val repeat = KeyEvent(androidEvent(AndroidKeyEvent.ACTION_DOWN, 1))
        val release = KeyEvent(androidEvent(AndroidKeyEvent.ACTION_UP, 0))
        val enter = KeyEvent(androidEvent(AndroidKeyEvent.ACTION_DOWN, 0, AndroidKeyEvent.KEYCODE_ENTER))

        assertTrue(handoffNavigationFocus(initialPress, handoff))
        assertFalse(handoffNavigationFocus(repeat, handoff))
        assertFalse(handoffNavigationFocus(release, handoff))
        assertFalse(handoffNavigationFocus(enter, handoff))

        assertEquals(1, authorized.size)
        assertTrue(authorized.single()?.isInitialDpadPress() == true)
    }

    @Test fun boundaryActionRunsOnceForHeldKeyAndAgainAfterARealRelease() {
        val gate = DpadFreshPressGate()
        var actions = 0
        fun dispatch(action: Int, downTime: Long, eventTime: Long, repeatCount: Int = 0) {
            dispatchFreshDpadAction(
                event = KeyEvent(AndroidKeyEvent(downTime, eventTime, action,
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT, repeatCount)),
                expectedKey = Key.DirectionRight,
                gate = gate
            ) { actions++ }
        }

        dispatch(AndroidKeyEvent.ACTION_DOWN, 100L, 100L)
        dispatch(AndroidKeyEvent.ACTION_DOWN, 100L, 220L, repeatCount = 1)
        // Some input stacks incorrectly reset repeatCount while retaining the
        // original downTime; this still belongs to the same held press.
        dispatch(AndroidKeyEvent.ACTION_DOWN, 100L, 260L)
        assertEquals(1, actions)

        dispatch(AndroidKeyEvent.ACTION_UP, 100L, 300L)
        dispatch(AndroidKeyEvent.ACTION_DOWN, 301L, 301L)
        assertEquals(2, actions)
    }

    @Test fun newDownTimeIsFreshEvenIfThePreviousReleaseWasLostDuringFocusHandoff() {
        val gate = DpadFreshPressGate()
        var actions = 0
        fun press(downTime: Long) = dispatchFreshDpadAction(
            event = KeyEvent(AndroidKeyEvent(
                downTime, downTime, AndroidKeyEvent.ACTION_DOWN,
                AndroidKeyEvent.KEYCODE_DPAD_DOWN, 0
            )),
            expectedKey = Key.DirectionDown,
            gate = gate
        ) { actions++ }

        press(10L)
        press(11L)
        assertEquals("a new hardware downTime is a new deliberate press", 2, actions)
    }

    @Test fun rejectedShellHandoffDoesNotReportSuccessOrBypassToFallback() {
        val rejected = handoffNavigationFocus(
            KeyEvent(androidEvent(AndroidKeyEvent.ACTION_DOWN, repeatCount = 0)),
            authorize = { false }
        )

        assertFalse(rejected)
    }

    @Test fun shellAcceptsOnlyItsConfiguredNavigationEntryDirection() {
        val guard = NavigationFocusGuard(
            scope = CoroutineScope(Dispatchers.Unconfined),
            blocked = { false },
            returnToContent = {},
            incomingDirection = Key.DirectionUp
        )

        assertFalse(guard.authorizeDirectEntry(KeyEvent(androidEvent(
            AndroidKeyEvent.ACTION_DOWN,
            repeatCount = 0,
            keyCode = AndroidKeyEvent.KEYCODE_DPAD_LEFT
        ))))
        assertTrue(guard.authorizeDirectEntry(KeyEvent(androidEvent(
            AndroidKeyEvent.ACTION_DOWN,
            repeatCount = 0,
            keyCode = AndroidKeyEvent.KEYCODE_DPAD_UP
        ))))
    }

    private fun androidEvent(
        action: Int,
        repeatCount: Int,
        keyCode: Int = AndroidKeyEvent.KEYCODE_DPAD_LEFT
    ) = AndroidKeyEvent(
        0L,
        1L,
        action,
        keyCode,
        repeatCount
    )
}
