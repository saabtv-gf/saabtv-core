package com.saab.tv.ui.details

import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import coil.size.Size
import coil.transform.Transformation
import com.saab.tv.data.profile.EpisodeSpoilerPreferences

@Composable
internal fun rememberEpisodeSpoilers(profileId: Int? = null): Boolean {
    val context = LocalContext.current
    val prefs = remember(context) { EpisodeSpoilerPreferences.preferences(context) }
    fun read(): Boolean = EpisodeSpoilerPreferences.enabled(context, profileId ?: prefs.getInt("last_active_profile_id", -1))
    var enabled by remember(profileId, prefs) { mutableStateOf(read()) }
    DisposableEffect(profileId, prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> enabled = read() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled
}

/** CPU soft blur works on Fire OS / Android 8+, unlike RenderEffect-only modifiers. */
internal class EpisodeSpoilerBlur : Transformation {
    override val cacheKey = "saab-episode-spoiler-soft-blur-v1"
    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val scale = minOf(320f / input.width, 180f / input.height, 1f)
        val width = (input.width * scale).toInt().coerceAtLeast(1)
        val height = (input.height * scale).toInt().coerceAtLeast(1)
        val tinyScale = minOf(12f / width, 12f / height, 1f)
        val tiny = Bitmap.createScaledBitmap(input, (width * tinyScale).toInt().coerceAtLeast(1), (height * tinyScale).toInt().coerceAtLeast(1), true)
        val output = Bitmap.createScaledBitmap(tiny, width, height, true)
        if (tiny !== input && tiny !== output) tiny.recycle()
        return output
    }
}
