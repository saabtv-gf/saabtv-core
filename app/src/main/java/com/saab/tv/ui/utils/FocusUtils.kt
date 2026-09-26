package com.saab.tv.ui.utils

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties

/**
 * Remembers the last focused item in a container.
 * When focus enters this container, it jumps to that item instead of the first one.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.rememberLastFocus(): Modifier = composed {
    val lastFocusedItem = remember { mutableStateOf<FocusRequester?>(null) }

    this.focusProperties {
        enter = {
            if (lastFocusedItem.value != null) {
                lastFocusedItem.value!!
            } else {
                FocusRequester.Default
            }
        }
    }
}