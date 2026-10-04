package com.saab.tv.ui.player.base

import android.graphics.Bitmap
import androidx.compose.runtime.*
import com.saab.tv.AppDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
internal fun rememberSmartCreditsPrompt(
    enabled: Boolean,
    cacheKey: String,
    durationMs: Long,
    intervalSeconds: Int,
    provider: (suspend (Long) -> Bitmap?)?,
    cachedFrames: Int?,
    totalFrames: Int?,
    requestMissingFrame: ((Long) -> Unit)? = null,
    diagnosticsSessionId: String = ""
): Long? {
    // Cache progress and cleanup must never erase the detected playback timestamp.
    var prompt by remember(cacheKey, durationMs, intervalSeconds) { mutableStateOf<Long?>(null) }
    val latestProvider by rememberUpdatedState(provider)
    val latestCachedFrames by rememberUpdatedState(cachedFrames)
    val latestTotalFrames by rememberUpdatedState(totalFrames)
    val latestRequest by rememberUpdatedState(requestMissingFrame)
    LaunchedEffect(enabled, cacheKey, durationMs, intervalSeconds, diagnosticsSessionId) {
        if (!enabled || prompt != null) return@LaunchedEffect
        var lastProgress: Pair<Int?, Int?>? = null
        while (isActive && !SmartCreditsPolicy.fullCacheReady(latestCachedFrames, latestTotalFrames)) {
            val progress = latestCachedFrames to latestTotalFrames
            if (progress != lastProgress) {
                AppDiagnostics.detailed("Smart Next Episode", "Waiting For Full Cache", "ready=${progress.first}/${progress.second} detector=image_heuristic")
                lastProgress = progress
            }
            delay(1_000)
        }
        AppDiagnostics.event("Smart Next Episode", "Full Cache Ready", "ready=$latestCachedFrames/$latestTotalFrames")
        val targets = SmartCreditsPolicy.targets(durationMs, intervalSeconds)
        val results = mutableMapOf<Long, Boolean>()
        while (isActive) {
            for (timestamp in targets) {
                if (timestamp in results) continue
                val bitmap = try {
                    latestProvider?.invoke(timestamp)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    AppDiagnostics.failure("Smart Next Episode", "Cached Frame Read Failed", error)
                    null
                }
                if (bitmap == null) {
                    latestRequest?.invoke(timestamp)
                    // Preserve chronological detection: do not select a later credit before this frame is read.
                    break
                }
                try {
                    val assessment = withContext(Dispatchers.Default) {
                        val width = minOf(160, bitmap.width)
                        val height = minOf(90, bitmap.height)
                        SmartCreditsPolicy.assessImage(width, height) { x, y ->
                            bitmap.getPixel(x * bitmap.width / width, y * bitmap.height / height)
                        }
                    }
                    results[timestamp] = assessment.credits
                    AppDiagnostics.detailed("Smart Next Episode", "Cached Frame Classified",
                        "atMs=$timestamp detector=image_heuristic credits=${assessment.credits} darkFraction=${assessment.darkFraction} brightFraction=${assessment.brightFraction} textBands=${assessment.textBands}")
                    if (assessment.credits) {
                        prompt = SmartCreditsPolicy.promptAt(targets, results, intervalSeconds)
                        AppDiagnostics.event("Smart Next Episode", "Fallback Prompt Found", "creditMs=$timestamp promptMs=$prompt retained=true")
                        return@LaunchedEffect
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            if (results.size == targets.size) {
                AppDiagnostics.event("Smart Next Episode", "Fallback Prompt Not Found", "processed=${results.size}/${targets.size}")
                return@LaunchedEffect
            }
            delay(3_000)
        }
    }
    return prompt
}
