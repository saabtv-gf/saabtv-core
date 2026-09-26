package com.saab.tv.ui.player.base

import android.app.Activity
import android.content.Context

object PlayerBackendFactory {
    fun create(
        context: Context,
        backendType: PlayerBackendType,
        playbackSettings: PlaybackSettings = PlaybackSettings()
    ): PlayerRuntime {
        return when (backendType) {
            PlayerBackendType.EXOPLAYER -> {
                val activity = context as? Activity
                val backend = ExoPlayerBackend(context.applicationContext, playbackSettings, activity)
                PlayerRuntime(
                    playbackController = backend,
                    renderSurface = backend
                )
            }
        }
    }
}
