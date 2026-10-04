package com.saab.tv.ui.player.base

import androidx.compose.runtime.*
import kotlinx.coroutines.*

@Composable
internal fun rememberLiveCreditsPrompt(
    enabled: Boolean,
    cacheKey: String,
    controller: PlayerPlaybackController,
    surface: PlayerRenderSurface,
    directFrameProvider: (suspend (Long) -> android.graphics.Bitmap?)?,
    cachedFrameProvider: (suspend (Long) -> android.graphics.Bitmap?)?,
    requestCachedFrame: ((Long) -> Unit)?
): Long? {
    var prompt by remember(enabled, cacheKey, controller) { mutableStateOf<Long?>(null) }
    LaunchedEffect(enabled, cacheKey, controller, surface) {
        if (!enabled) return@LaunchedEffect
        var previousPositive: Long? = null
            while (isActive && prompt == null) {
                val state = controller.uiState.value
                if (!SmartCreditsPolicy.liveScanEligible(state.positionMs, state.durationMs,
                        state.isPlaying, state.isBuffering || state.isSeeking)) {
                    previousPositive = null
                    delay(1_000)
                    continue
                }
                val captureStartedAt = android.os.SystemClock.elapsedRealtime()
                val liveBitmap = surface.captureVideoFrame()
                val decoderBitmap = if (liveBitmap == null) runCatching {
                    directFrameProvider?.invoke(state.positionMs)
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    com.saab.tv.AppDiagnostics.failure("Smart Next Episode", "Isolated Decoder Capture Failed", error)
                    null
                } else null
                val cachedBitmap = if (liveBitmap == null && decoderBitmap == null) try {
                    val framePosition = SmartCreditsPolicy.gridPosition(state.positionMs, 5)
                    cachedFrameProvider?.invoke(framePosition)?.also {
                        com.saab.tv.AppDiagnostics.detailed("Smart Next Episode", "Cached Frame Fallback", "positionMs=${state.positionMs} gridMs=$framePosition")
                    } ?: run {
                        requestCachedFrame?.invoke(SmartCreditsPolicy.gridPosition(state.positionMs, 30))
                        null
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    com.saab.tv.AppDiagnostics.failure("Smart Next Episode", "Cached Frame Fallback Failed", error)
                    null
                } else null
                val bitmap = liveBitmap ?: decoderBitmap ?: cachedBitmap
                val captureSource = when {
                    liveBitmap != null -> "live_surface"
                    decoderBitmap != null -> "isolated_decoder_direct"
                    else -> "cached_thumbnail_fallback"
                }
                if (bitmap == null) {
                    previousPositive = null
                    com.saab.tv.AppDiagnostics.detailed("Smart Next Episode", "Live Capture Unavailable", "positionMs=${state.positionMs} liveCaptureFailed=true cachedFallbackAvailable=${cachedFrameProvider != null}")
                } else try {
                    val assessment = withContext(Dispatchers.Default) {
                        val width = minOf(160, bitmap.width)
                        val height = minOf(90, bitmap.height)
                        SmartCreditsPolicy.assessImage(width, height) { x, y ->
                            bitmap.getPixel(x * bitmap.width / width, y * bitmap.height / height)
                        }
                    }
                    val positive = assessment.credits
                    val current = controller.uiState.value
                    val elapsedMs = android.os.SystemClock.elapsedRealtime() - captureStartedAt
                    val stable = SmartCreditsPolicy.frameStillCurrent(state.positionMs, current.positionMs,
                        elapsedMs, state.playbackSpeed, current.isSeeking || current.isBuffering)
                    com.saab.tv.AppDiagnostics.detailed("Smart Next Episode", "Live Frame Classified",
                        "detector=image_heuristic source=$captureSource positionMs=${state.positionMs} frame=${bitmap.width}x${bitmap.height} credits=$positive stable=$stable captureMs=$elapsedMs darkFraction=${assessment.darkFraction} brightFraction=${assessment.brightFraction} textBands=${assessment.textBands}")
                    if (positive && stable) {
                        val previous = previousPositive
                        if (previous != null && state.positionMs - previous in 1_000..15_000) {
                            prompt = previous
                            com.saab.tv.AppDiagnostics.event("Smart Next Episode", "Live Prompt Found", "promptMs=$prompt")
                        }
                        previousPositive = state.positionMs
                    } else previousPositive = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    previousPositive = null
                    com.saab.tv.AppDiagnostics.failure("Smart Next Episode", "Live Image Analysis Failed", error)
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
                delay(5_000)
            }
    }
    return prompt
}
