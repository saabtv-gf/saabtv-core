package com.saab.tv.ui.home

import android.os.SystemClock
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * Rate-limits repeated DPAD key-down events so long-press feels like paced
 * single presses instead of turbo acceleration.
 */
class DpadRepeatGate(
    private val horizontalRepeatIntervalMs: Long = 120L,
    private val verticalRepeatIntervalMs: Long = horizontalRepeatIntervalMs
) {
    private val lastAcceptedByKey = mutableMapOf<Key, Long>()

    fun shouldConsume(event: KeyEvent): Boolean {
        val key = event.key
        val repeatIntervalMs = when (key) {
            Key.DirectionLeft, Key.DirectionRight -> horizontalRepeatIntervalMs
            Key.DirectionUp, Key.DirectionDown -> verticalRepeatIntervalMs
            else -> return false
        }

        if (event.type != KeyEventType.KeyDown) return false

        // A new physical press must never be swallowed because it followed a
        // previous press quickly. Only pace key-repeat events from a held key.
        if (event.nativeKeyEvent.repeatCount == 0) {
            lastAcceptedByKey[key] = SystemClock.uptimeMillis()
            return false
        }

        val eventTimeMs = SystemClock.uptimeMillis()
        val lastAcceptedEventTimeMs = lastAcceptedByKey[key]
        if (lastAcceptedEventTimeMs == null) {
            lastAcceptedByKey[key] = eventTimeMs
            return false
        }

        val elapsed = eventTimeMs - lastAcceptedEventTimeMs
        return if (elapsed >= repeatIntervalMs) {
            lastAcceptedByKey[key] = eventTimeMs
            false
        } else {
            true
        }
    }
}
