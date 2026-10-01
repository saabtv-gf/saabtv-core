package com.saab.tv.data.profile

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

data class TrailerPreviewSettings(val enabled: Boolean = true, val delaySeconds: Int = 5,
    val muted: Boolean = true, val presentation: String = "inline")

object TrailerPreviewPreferences {
    fun read(context: Context, id: Int): TrailerPreviewSettings {
        val p = EpisodeSpoilerPreferences.preferences(context)
        return TrailerPreviewSettings(p.getBoolean("trailer_preview_$id", true),
            p.getInt("trailer_delay_$id", 5).takeIf { it in listOf(3, 5, 10, 15) } ?: 5,
            p.getBoolean("trailer_muted_$id", true),
            p.getString("trailer_presentation_$id", "inline")?.takeIf { it in listOf("inline", "fullscreen") } ?: "inline")
    }
    fun set(context: Context, id: Int, settings: TrailerPreviewSettings) {
        EpisodeSpoilerPreferences.preferences(context).edit()
            .putBoolean("trailer_preview_$id", settings.enabled)
            .putInt("trailer_delay_$id", settings.delaySeconds)
            .putBoolean("trailer_muted_$id", settings.muted)
            .putString("trailer_presentation_$id", settings.presentation).apply()
    }
    fun copy(context: Context, source: Int, target: Int) = set(context, target, read(context, source))
}

@Composable
fun rememberTrailerPreviewSettings(id: Int): TrailerPreviewSettings {
    val context = LocalContext.current
    var settings by remember(id) { mutableStateOf(TrailerPreviewPreferences.read(context, id)) }
    DisposableEffect(id, context) {
        val prefs = EpisodeSpoilerPreferences.preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            settings = TrailerPreviewPreferences.read(context, id)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return settings
}
