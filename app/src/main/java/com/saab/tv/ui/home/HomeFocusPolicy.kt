package com.saab.tv.ui.home

internal fun requestFocusIfReady(requestFocus: () -> Unit): Boolean =
    try {
        requestFocus()
        true
    } catch (_: IllegalStateException) {
        false
    }
