package com.saab.tv.ui.player.base

import android.graphics.Bitmap
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.saab.tv.data.player.PlaybackDiagnostics
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
    requestMissingFrame: ((Long) -> Unit)? = null,
    diagnosticsSessionId: String = ""
): Long? {
    val context = LocalContext.current
    var prompt by remember(enabled, cacheKey, durationMs, intervalSeconds, provider != null) {
        mutableStateOf<Long?>(null)
    }
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
    LaunchedEffect(enabled, cacheKey, durationMs, intervalSeconds, provider != null, recognizer, diagnosticsSessionId) {
        fun log(event: String, details: String = "") {
            com.saab.tv.AppDiagnostics.event("Smart Next Episode", event, details)
            PlaybackDiagnostics.event(
                context = context,
                sessionId = diagnosticsSessionId,
                component = "Smart Next Episode",
                event = event,
                details = details
            )
        }

        fun logDetailed(event: String, details: String = "") {
            com.saab.tv.AppDiagnostics.detailed("Smart Next Episode", event, details)
            PlaybackDiagnostics.event(
                context = context,
                sessionId = diagnosticsSessionId,
                component = "Smart Next Episode",
                event = event,
                details = details,
                level = "DEBUG"
            )
        }

        if (!enabled || provider == null) return@LaunchedEffect
        if (recognizer == null) {
            log("Fallback Scan Failed", "reason=ocr_recognizer_unavailable")
            com.saab.tv.AppDiagnostics.failure(
                "Playback", "Smart Credits OCR Unavailable",
                IllegalStateException("Unable to initialize bundled text recognizer")
            )
            return@LaunchedEffect
        }
        val targets = SmartCreditsPolicy.targets(durationMs, intervalSeconds)
        if (targets.isEmpty()) {
            log("Fallback Scan Not Started", "reason=duration_under_5_minutes durationMs=$durationMs")
            return@LaunchedEffect
        }
        log(
            "Fallback Scan Started",
            "durationMs=$durationMs intervalSeconds=${intervalSeconds.coerceIn(10, 30)} " +
                "targetFrames=${targets.size} missingFrameRequester=${requestMissingFrame != null}"
        )

        val results = mutableMapOf<Long, Boolean>()
        val missingFramesReported = mutableSetOf<Long>()
        val providerFailuresReported = mutableSetOf<Long>()
        var lastRequestedTarget: Long? = null
        var lastRequestElapsedMs = 0L
        var lastHeartbeatElapsedMs = android.os.SystemClock.elapsedRealtime()
        var lastLoggedResults = 0
        var providerMisses = 0
        var ocrFailures = 0

        while (isActive) {
            for (timestamp in targets) {
                if (timestamp in results) continue
                val bitmap = try {
                    latestProvider?.invoke(timestamp)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    logDetailed("Fallback Scan Cancelled", "atMs=$timestamp reason=provider_cancelled")
                    throw cancelled
                } catch (error: Exception) {
                    if (providerFailuresReported.add(timestamp)) {
                        logDetailed(
                            "Cached Frame Read Failed",
                            "atMs=$timestamp error=${error.javaClass.simpleName}"
                        )
                        com.saab.tv.AppDiagnostics.failure("Playback", "Smart Credits Cache Read Failed", error)
                    }
                    null
                }
                if (bitmap == null) {
                    if (missingFramesReported.add(timestamp)) {
                        providerMisses++
                        logDetailed(
                            "Cached Frame Missing",
                            "atMs=$timestamp processed=${results.size}/${targets.size}"
                        )
                    }
                    continue
                }

                try {
                    val text = try {
                        withContext(Dispatchers.IO) {
                            Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).text
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        logDetailed("Fallback Scan Cancelled", "atMs=$timestamp reason=ocr_cancelled")
                        throw cancelled
                    } catch (error: Exception) {
                        ocrFailures++
                        logDetailed(
                            "OCR Frame Failed",
                            "atMs=$timestamp error=${error.javaClass.simpleName}"
                        )
                        com.saab.tv.AppDiagnostics.failure("Playback", "Smart Credits OCR Failed", error)
                        ""
                    }
                    val looksLikeCredits = SmartCreditsPolicy.looksLikeCreditsText(text)
                    results[timestamp] = looksLikeCredits
                    logDetailed(
                        "Frame Classified",
                        "atMs=$timestamp result=${if (looksLikeCredits) "credits" else "not_credits"} " +
                            "textChars=${text.length} processed=${results.size}/${targets.size}"
                    )
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }

            prompt = SmartCreditsPolicy.promptAt(targets, results, intervalSeconds)
            if (prompt != null) {
                log(
                    "Fallback Prompt Found",
                    "promptMs=$prompt processed=${results.size}/${targets.size} " +
                        "creditsFrames=${results.values.count { it }} cacheMisses=$providerMisses " +
                        "ocrFailures=$ocrFailures"
                )
                break
            }
            if (results.size == targets.size) {
                log(
                    "Fallback Prompt Not Found",
                    "reason=all_frames_classified processed=${results.size}/${targets.size} " +
                        "creditsFrames=${results.values.count { it }} cacheMisses=$providerMisses " +
                        "ocrFailures=$ocrFailures"
                )
                break
            }

            // Use the existing isolated thumbnail decoder; do not open a competing decoder.
            SmartCreditsPolicy.nextMissingTarget(targets, results)?.let { missing ->
                val now = android.os.SystemClock.elapsedRealtime()
                if (missing != lastRequestedTarget || now - lastRequestElapsedMs >= 5_000L) {
                    if (latestRequestMissingFrame != null) {
                        latestRequestMissingFrame?.invoke(missing)
                        logDetailed(
                            "Missing Frame Prioritized",
                            "atMs=$missing processed=${results.size}/${targets.size} request=thumbnail_worker"
                        )
                    } else {
                        log("Fallback Cannot Request Frame", "atMs=$missing reason=requester_unavailable")
                    }
                    lastRequestedTarget = missing
                    lastRequestElapsedMs = now
                }
            }

            val now = android.os.SystemClock.elapsedRealtime()
            if (results.size != lastLoggedResults || now - lastHeartbeatElapsedMs >= 30_000L) {
                log(
                    "Fallback Scan Progress",
                    "processed=${results.size}/${targets.size} creditsFrames=${results.values.count { it }} " +
                        "cacheMisses=$providerMisses ocrFailures=$ocrFailures " +
                        "nextMissingMs=${SmartCreditsPolicy.nextMissingTarget(targets, results)}"
                )
                lastLoggedResults = results.size
                lastHeartbeatElapsedMs = now
            }
            delay(3_000L)
        }
    }
    return prompt
}
