package com.saab.tv.ui.home

import android.app.Application
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DpadRepeatGateTest {
    @Test fun onlyHeldDirectionalRepeatsAreRateLimited() {
        var now = 100L
        val gate = DpadRepeatGate(horizontalRepeatIntervalMs = 120L, nowMs = { now })

        assertFalse(gate.shouldConsume(event(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_RIGHT)))
        now = 150L
        assertTrue(gate.shouldConsume(event(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_RIGHT, repeat = 1)))
        now = 220L
        assertFalse(gate.shouldConsume(event(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_RIGHT, repeat = 2)))
        now = 250L
        assertTrue(gate.shouldConsume(event(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_RIGHT, repeat = 3)))
    }

    @Test fun aNewPressAndNonDirectionalKeysAreNeverSwallowed() {
        var now = 100L
        val gate = DpadRepeatGate(horizontalRepeatIntervalMs = 120L, nowMs = { now })

        assertFalse(gate.shouldConsume(event(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_LEFT)))
        now = 101L
        assertFalse(gate.shouldConsume(event(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_LEFT)))
        assertFalse(gate.shouldConsume(event(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_ENTER, repeat = 1)))
        assertFalse(gate.shouldConsume(event(AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DPAD_LEFT, repeat = 1)))
    }

    @Test fun verticalAndHorizontalHeldKeysUseTheirOwnIntervals() {
        var now = 10L
        val gate = DpadRepeatGate(
            horizontalRepeatIntervalMs = 120L,
            verticalRepeatIntervalMs = 200L,
            nowMs = { now }
        )

        assertFalse(gate.shouldConsume(event(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_UP)))
        now = 150L
        assertTrue(gate.shouldConsume(event(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_UP, repeat = 1)))
        assertFalse(gate.shouldConsume(event(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_RIGHT)))
        now = 270L
        assertFalse(gate.shouldConsume(event(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_RIGHT, repeat = 1)))
    }

    private fun event(action: Int, keyCode: Int, repeat: Int = 0) =
        KeyEvent(AndroidKeyEvent(0L, 1L, action, keyCode, repeat))
}
