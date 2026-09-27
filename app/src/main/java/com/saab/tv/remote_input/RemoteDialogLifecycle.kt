package com.saab.tv.remote_input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Cancel pairing when its TV screen is no longer visible. */
@Composable
internal fun RemoteDialogLifecycle(onStop: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val stop by rememberUpdatedState(onStop)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
