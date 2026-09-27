package com.saab.tv.data.update

import java.util.Locale

/** Keep user-visible percent signs out of dynamically constructed format strings. */
internal fun updateProgressLabel(progress: Float, downloadedMb: Float, totalMb: Float): String =
    if (progress < 0) String.format(Locale.getDefault(), "Downloading… %.1f MB", downloadedMb)
    else String.format(Locale.getDefault(), "Downloading… %d%% · %.1f / %.1f MB",
        (progress.coerceIn(0f, 1f) * 100).toInt(), downloadedMb, totalMb)
