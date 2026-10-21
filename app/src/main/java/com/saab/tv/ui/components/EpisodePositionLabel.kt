package com.saab.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.saab.tv.R

/** Compact on-screen episode notation with a fully localized screen-reader announcement. */
@Composable
internal fun EpisodePositionLabel(
    seasonNumber: Int,
    episodeNumber: Int,
    modifier: Modifier = Modifier
) {
    val accessibleLabel = stringResource(
        R.string.episode_accessibility_label,
        seasonNumber,
        episodeNumber
    )
    Text(
        text = "S$seasonNumber · E$episodeNumber",
        modifier = modifier.semantics { contentDescription = accessibleLabel },
        style = MaterialTheme.typography.bodySmall,
        color = Color.White,
        maxLines = 1
    )
}
