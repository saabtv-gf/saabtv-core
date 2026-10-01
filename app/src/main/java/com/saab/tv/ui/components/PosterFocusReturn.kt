package com.saab.tv.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.focus.FocusRequester

/** Restore the exact poster after closing an overlay, without jumping to row one. */
val LocalPosterFocusReturn = compositionLocalOf<(FocusRequester) -> Unit> { {} }
