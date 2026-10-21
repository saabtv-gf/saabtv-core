package com.saab.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.saab.tv.R
import java.util.Locale

@Composable
internal fun localizedTitleType(rawType: String): String =
    when (rawType.trim().lowercase(Locale.ROOT)) {
        "movie", "film" -> stringResource(R.string.title_type_movie)
        "series", "tv", "show" -> stringResource(R.string.title_type_series)
        "episode" -> stringResource(R.string.title_type_episode)
        else -> rawType.trim().takeIf { it.isNotEmpty() }
            ?: stringResource(R.string.sort_filter_unknown)
    }

@Composable
internal fun localizedRuntimeLabel(runtimeMinutes: Int): String {
    val minutes = runtimeMinutes.coerceAtLeast(0)
    val hours = minutes / 60
    val remainder = minutes % 60
    return when {
        hours > 0 && remainder > 0 -> stringResource(
            R.string.title_runtime_hours_minutes,
            hours,
            remainder
        )
        hours > 0 -> stringResource(R.string.title_runtime_hours, hours)
        else -> stringResource(R.string.title_runtime_minutes, remainder)
    }
}
