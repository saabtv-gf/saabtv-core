package com.saab.tv.ui.settings

/** Android DPAD_CENTER, ENTER and NUMPAD_ENTER on read-only diagnostic rows. */
internal fun isDiagnosticActivationKey(keyCode: Int): Boolean = keyCode == 23 || keyCode == 66 || keyCode == 160
