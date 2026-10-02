package com.saab.tv.ui.player.base

import android.graphics.Bitmap
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
internal fun rememberSmartCreditsPrompt(
    enabled: Boolean, cacheKey: String, durationMs: Long, intervalSeconds: Int,
    provider: (suspend (Long) -> Bitmap?)?
): Long? {
    var prompt by remember(enabled, cacheKey, durationMs, intervalSeconds, provider != null) { mutableStateOf<Long?>(null) }
    val latestProvider by rememberUpdatedState(provider)
    LaunchedEffect(enabled, cacheKey, durationMs, intervalSeconds, provider != null) {
        if (!enabled || provider == null) return@LaunchedEffect
        val targets = SmartCreditsPolicy.targets(durationMs, intervalSeconds)
        if (targets.isEmpty()) return@LaunchedEffect
        val results = mutableMapOf<Long, Boolean>()
        while (isActive) {
            for (timestamp in targets) {
                if (timestamp in results) continue
                // Cache-only provider: never open another stream or request extraction.
                val bitmap = try { latestProvider?.invoke(timestamp) } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    com.saab.tv.AppDiagnostics.failure("Playback", "Smart Credits Cache Read Failed", error)
                    return@LaunchedEffect
                } ?: continue
                try {
                    results[timestamp] = withContext(Dispatchers.Default) {
                        val width = minOf(160, bitmap.width)
                        val height = minOf(90, bitmap.height)
                        SmartCreditsPolicy.looksLikeCredits(width, height) { x, y ->
                            bitmap.getPixel(x * bitmap.width / width, y * bitmap.height / height)
                        }
                    }
                } finally { if (!bitmap.isRecycled) bitmap.recycle() }
            }
            prompt = SmartCreditsPolicy.promptAt(targets, results, intervalSeconds)
            if (prompt != null || results.size == targets.size) break
            delay(10_000L)
        }
    }
    return prompt
}
