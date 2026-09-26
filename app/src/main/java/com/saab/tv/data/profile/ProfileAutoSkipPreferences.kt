package com.saab.tv.data.profile

import com.saab.tv.data.model.ProfileEntity

// Keep the legacy Room columns for update compatibility. The intro value is
// authoritative for the single shared control, including on existing profiles.
internal val ProfileEntity.autoSkipCountdownSeconds: Int
    get() = if (!autoSkipIntro) 0 else if (introSkipCountdownSeconds == 10) 10 else 5

internal fun ProfileEntity.withAutoSkipCountdown(seconds: Int): ProfileEntity {
    val value = if (seconds == 5 || seconds == 10) seconds else 0
    return copy(
        autoSkipIntro = value > 0,
        introSkipCountdownSeconds = value,
        outroSkipCountdownSeconds = value
    )
}
