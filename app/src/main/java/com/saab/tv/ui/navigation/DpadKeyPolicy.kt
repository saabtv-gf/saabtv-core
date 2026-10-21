package com.saab.tv.ui.navigation

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusRequester
import com.saab.tv.ui.utils.requestFocusSafely

/** Only the initial key-down may trigger an action; held-key repeats are not new intent. */
internal fun KeyEvent.isInitialKeyPress(): Boolean =
    type == KeyEventType.KeyDown && nativeKeyEvent.repeatCount == 0

/** A Back/Escape action fires on the first press, never on a held-key repeat or release. */
internal fun KeyEvent.isInitialBackPress(): Boolean =
    isBackOrEscape() && isInitialKeyPress()

/** Recognize both Back sources even on repeats/releases so handlers can consume the whole gesture. */
internal fun KeyEvent.isBackOrEscape(): Boolean = key == Key.Back || key == Key.Escape

/** Only a fresh directional press may transfer focus between navigation and content. */
internal fun KeyEvent.isInitialDpadPress(): Boolean =
    isInitialKeyPress() && when (key) {
        Key.DirectionUp,
        Key.DirectionDown,
        Key.DirectionLeft,
        Key.DirectionRight -> true
        else -> false
    }

/**
 * De-duplicates a held directional press at a focus boundary. Some TV input
 * stacks deliver repeat downs with repeatCount=0, so the key-up edge is also
 * tracked instead of relying on repeatCount alone.
 */
internal class DpadFreshPressGate {
    private val keyDownTimes = mutableMapOf<Key, Long>()

    fun shouldHandle(event: KeyEvent): Boolean {
        val key = event.key
        if (key !in DpadKeys) return false
        return when (event.type) {
            KeyEventType.KeyDown -> {
                val downTime = event.nativeKeyEvent.downTime
                if (event.nativeKeyEvent.repeatCount > 0) {
                    keyDownTimes.putIfAbsent(key, downTime)
                    false
                } else if (keyDownTimes[key] == downTime) {
                    false
                } else {
                    keyDownTimes[key] = downTime
                    true
                }
            }
            KeyEventType.KeyUp -> {
                keyDownTimes.remove(key)
                false
            }
            else -> false
        }
    }

    fun release(event: KeyEvent) {
        if (event.type == KeyEventType.KeyUp) keyDownTimes.remove(event.key)
    }

    private companion object {
        val DpadKeys = setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)
    }
}

/** Consume a boundary key gesture and invoke its action once until the release edge. */
internal fun dispatchFreshDpadAction(
    event: KeyEvent,
    expectedKey: Key,
    gate: DpadFreshPressGate,
    action: () -> Unit
): Boolean {
    if (event.key != expectedKey) return false
    if (event.type == KeyEventType.KeyUp) {
        gate.release(event)
        return true
    }
    if (event.type != KeyEventType.KeyDown) return false
    if (gate.shouldHandle(event)) action()
    return true
}

/** Use the shell's registered handoff when present; standalone shelves keep their focus fallback. */
internal fun handoffNavigationFocus(
    event: KeyEvent,
    authorize: ((KeyEvent?) -> Boolean)?,
    fallbackRequester: FocusRequester? = null
): Boolean {
    if (!event.isInitialDpadPress()) return false
    return authorize?.invoke(event) ?: fallbackRequester?.requestFocusSafely() ?: false
}
