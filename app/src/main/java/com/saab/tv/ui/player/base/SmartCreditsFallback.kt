package com.saab.tv.ui.player.base

import android.graphics.Bitmap
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.*
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
internal fun rememberSmartCreditsPrompt(
    enabled: Boolean, cacheKey: String, durationMs: Long, intervalSeconds: Int,
    provider: (suspend (Long) -> Bitmap?)?,
    requestMissingFrame: ((Long) -> Unit)? = null
): Long? {
    var prompt by remember(enabled, cacheKey, durationMs, intervalSeconds, provider != null) { mutableStateOf<Long?>(null) }
    val latestProvider by rememberUpdatedState(provider)
    val latestRequestMissingFrame by rememberUpdatedState(requestMissingFrame)
    val recognizer = remember(enabled && provider != null) {
        if (enabled && provider != null) {
            runCatching { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }.getOrNull()
        } else null
    }
    DisposableEffect(recognizer) {
        onDispose { recognizer?.close() }
    }
    LaunchedEffect(enabled, cacheKey, durationMs, intervalSeconds, provider != null, recognizer) {
        if (!enabled || provider == null) return@LaunchedEffect
        if (recognizer == null) {
            com.saab.tv.AppDiagnostics.failure(
                "Playback", "Smart Credits OCR Unavailable",
                IllegalStateException("Unable to initialize bundled text recognizer")
            )
            return@LaunchedEffect
        }
        val targets = SmartCreditsPolicy.targets(durationMs, intervalSeconds)
        if (targets.isEmpty()) return@LaunchedEffect
        val results = mutableMapOf<Long, Boolean>()
        var lastRequestedTarget: Long? = null
        var lastRequestElapsedMs = 0L
        while (isActive) {
            for (timestamp in targets) {
                if (timestamp in results) continue
                val bitmap = try { latestProvider?.invoke(timestamp) } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    com.saab.tv.AppDiagnostics.failure("Playback", "Smart Credits Cache Read Failed", error)
                    null
                } ?: continue
                try {
                    val text = try {
                        withContext(Dispatchers.IO) {
                            Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).text
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        com.saab.tv.AppDiagnostics.failure("Playback", "Smart Credits OCR Failed", error)
                        ""
                    }
                    results[timestamp] = SmartCreditsPolicy.looksLikeCreditsText(text)
                } finally { if (!bitmap.isRecycled) bitmap.recycle() }
            }
            prompt = SmartCreditsPolicy.promptAt(targets, results, intervalSeconds)
            if (prompt != null || results.size == targets.size) break
            // The isolated thumbnail service is already decoding this same stream.
            // Ask it to fill the earliest missing credit-window frame, then OCR it
            // from disk on the next pass instead of opening a competing decoder.
            SmartCreditsPolicy.nextMissingTarget(targets, results)?.let { missing ->
                val now = android.os.SystemClock.elapsedRealtime()
                if (missing != lastRequestedTarget || now - lastRequestElapsedMs >= 5_000L) {
                    latestRequestMissingFrame?.invoke(missing)
                    lastRequestedTarget = missing
                    lastRequestElapsedMs = now
                }
            }
            delay(3_000L)
        }
    }
    return prompt
}
