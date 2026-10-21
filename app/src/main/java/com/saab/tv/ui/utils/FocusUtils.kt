package com.saab.tv.ui.utils

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.runtime.withFrameNanos

/** Dispatch a focus request and report whether Compose accepted it. */
fun FocusRequester.requestFocusSafely(): Boolean = runCatching {
    requestFocus()
}.getOrDefault(false)

/** Retry focus only while Compose is attaching the destination, not on wall-clock sleeps. */
suspend fun FocusRequester.requestFocusWhenAttached(
    maxFrameAttempts: Int = 12,
    hasFocus: () -> Boolean = { true }
): Boolean {
    repeat(maxFrameAttempts.coerceAtLeast(1)) {
        withFrameNanos { }
        if (requestFocusSafely()) {
            withFrameNanos { }
            if (hasFocus()) return true
        }
    }
    return false
}

/** Restore the original target when it still exists, otherwise move to a known content entry. */
fun FocusRequester?.requestFocusOrFallback(fallback: FocusRequester): Boolean =
    this?.requestFocusSafely() == true || fallback.requestFocusSafely()

/**
 * Remembers the last focused item in a container.
 * When focus enters this container, it jumps to that item instead of the first one.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.rememberLastFocus(): Modifier = focusRestorer()
