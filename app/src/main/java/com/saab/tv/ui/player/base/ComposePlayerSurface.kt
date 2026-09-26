package com.saab.tv.ui.player.base

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun ComposePlayerSurface(
    renderSurface: PlayerRenderSurface,
    modifier: Modifier = Modifier
) {
    renderSurface.Content(modifier = modifier)
}
