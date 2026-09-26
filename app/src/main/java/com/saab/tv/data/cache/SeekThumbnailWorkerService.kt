package com.saab.tv.data.cache

import android.app.Service
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Debug
import android.os.IBinder
import com.saab.tv.data.player.PlaybackDiagnostics
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext

data class SeekThumbnailWorkerRequest(
    val profileId: Int,
    val contentId: String,
    val mediaUrl: String,
    val durationMs: Long,
    val intervalSeconds: Int,
    val priorityPositionMs: Long = 0L,
    val diagnosticsSessionId: String = ""
) {
    val key: String = "$profileId:$contentId:${mediaUrl.hashCode()}:$intervalSeconds"
}

/**
 * Runs frame decoding outside the playback process. A native codec failure can
 * terminate this worker without bringing down the player or Compose UI.
 */
class SeekThumbnailWorkerService : Service() {
    private val workerDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "seek-thumbnail-worker").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + workerDispatcher)
    private lateinit var cache: SeekThumbnailCache
    private var generationJob: Job? = null
    private var activeRequestKey: String? = null
    private var generationId = 0L
    @Volatile private var pauseRequested = false
    @Volatile private var priorityPositionMs = 0L

    override fun onCreate() {
        super.onCreate()
        cache = SeekThumbnailCache(applicationContext).also { it.optimizeForLocalWriter() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startOrResume(intent, startId)
            // Preserve the open decoder across short playback buffering/seeking
            // periods. Cancelling a native seek here caused libmpv "A0 was
            // cancelled" failures and forced an expensive full-file reopen.
            ACTION_PAUSE -> pauseRequested = true
            ACTION_PRIORITIZE -> {
                if (intent.getStringExtra(EXTRA_REQUEST_KEY) == activeRequestKey) {
                    priorityPositionMs = intent.getLongExtra(EXTRA_PRIORITY_POSITION_MS, 0L).coerceAtLeast(0L)
                }
                if (generationJob?.isActive != true) stopSelf(startId)
            }
            ACTION_CANCEL -> cancel(intent.getStringExtra(EXTRA_REQUEST_KEY))
        }
        return START_NOT_STICKY
    }

    private fun startOrResume(intent: Intent, startId: Int) {
        val request = intent.toRequest() ?: run {
            stopSelf(startId)
            return
        }
        if (!TorBoxStreamUrlPolicy.isRemoteHttpStream(request.mediaUrl) ||
            !isProfileEnabled(this, request.profileId)
        ) {
            stopSelf(startId)
            return
        }
        if (!retryAllowed(request)) {
            stopSelf(startId)
            return
        }
        pauseRequested = false
        priorityPositionMs = request.priorityPositionMs
        if (request.key == activeRequestKey && generationJob?.isActive == true) {
            return
        }

        generationJob?.cancel()
        val currentGenerationId = ++generationId
        activeRequestKey = request.key
        generationJob = scope.launch {
            try {
                generate(request)
            } finally {
                if (generationId == currentGenerationId) {
                    generationJob = null
                    activeRequestKey = null
                    stopSelf()
                }
            }
        }
    }

    private suspend fun generate(request: SeekThumbnailWorkerRequest) {
        val mediaUrl = TorBoxStreamUrlPolicy.forThumbnailWorker(request.mediaUrl)
        var targets = SeekThumbnailCache.persistentDecoderTargets(
            durationMs = request.durationMs,
            intervalSeconds = request.intervalSeconds,
            priorityPositionMs = request.priorityPositionMs
        )
        val engine = LibMpvThumbnailEngine(
            context = applicationContext
        )
        val platformEngine = PlatformThumbnailEngine()
        val mpvGuard = mpvCrashGuard(request)
        val skipMpv = mpvGuard.exists()
        var consecutiveFailures = 0
        var attemptedFrames = 0
        var storedFrames = 0
        var mpvAvailable = false
        var platformAvailable = false
        var cancelled = false
        var plannedPriority = request.priorityPositionMs
        val attemptedPositions = mutableSetOf<Long>()
        var targetIndex = 0

        try {
            diagnostic(
                request,
                "Batch Started",
                "targets=${targets.size} interval=${request.intervalSeconds}s " +
                    PlaybackDiagnostics.memorySummary()
            )
            if (!hasMemoryHeadroom()) {
                diagnostic(request, "Memory Guard", PlaybackDiagnostics.memorySummary(), "WARN")
                return
            }
            if (!skipMpv) {
                runCatching {
                    mpvGuard.parentFile?.mkdirs()
                    mpvGuard.writeText(request.key)
                }
                mpvAvailable = engine.open(mediaUrl)
                diagnostic(
                    request,
                    "libmpv Configuration",
                    engine.initializationTrace,
                    if (engine.initializationTrace.contains("rejected=0")) "INFO" else "WARN"
                )
                diagnostic(
                    request,
                    if (mpvAvailable) "libmpv Opened" else "libmpv Open Failed",
                    engine.lastError.orEmpty(),
                    if (mpvAvailable) "INFO" else "WARN"
                )
            } else {
                // The guard is evidence that a previous worker process ended before
                // reaching its finally block. Skip libmpv once, then clear the guard
                // so a single failure cannot permanently disable the primary engine.
                mpvGuard.delete()
                diagnostic(
                    request,
                    "libmpv Crash Guard Recovered",
                    "platform fallback will be used for this batch",
                    "WARN"
                )
            }
            if (!mpvAvailable) {
                platformAvailable = platformEngine.open(mediaUrl)
                diagnostic(
                    request,
                    if (platformAvailable) "Platform Decoder Opened" else "Platform Decoder Open Failed",
                    platformEngine.lastError.orEmpty(),
                    if (platformAvailable) "INFO" else "ERROR"
                )
            }
            if (!mpvAvailable && !platformAvailable) {
                diagnostic(request, "Batch Stopped", "no thumbnail decoder opened", "ERROR")
                return
            }
            while (true) {
                if (!currentCoroutineContext().isActive) break
                if (!isProfileEnabled(this, request.profileId)) break
                while (pauseRequested && currentCoroutineContext().isActive) {
                    delay(PAUSE_POLL_INTERVAL_MS)
                }
                val priority = priorityPositionMs
                if (priority != plannedPriority) {
                    targets = SeekThumbnailCache.persistentDecoderTargets(
                        request.durationMs, request.intervalSeconds, priority
                    )
                    plannedPriority = priority
                    targetIndex = 0
                    diagnostic(request, "Queue Reprioritized", "position=${priority / 1_000}s forward-first")
                }
                if (targetIndex >= targets.size) break
                val positionMs = targets[targetIndex++]
                if (!attemptedPositions.add(positionMs)) continue
                if (attemptedFrames % MEMORY_CHECK_INTERVAL_FRAMES == 0 && !hasMemoryHeadroom()) {
                    diagnostic(request, "Memory Guard", PlaybackDiagnostics.memorySummary(), "WARN")
                    break
                }
                if (cache.contains(
                        request.profileId,
                        request.contentId,
                        positionMs,
                        request.intervalSeconds
                    )
                ) continue

                attemptedFrames++
                try {
                    var usedEngine = if (mpvAvailable) "libmpv" else "platform"
                    var bitmap = if (mpvAvailable) engine.capture(positionMs) else null
                    if (bitmap == null && mpvAvailable) {
                        diagnostic(
                            request,
                            "libmpv Frame Failed",
                            "position=${positionMs / 1_000}s ${engine.lastError.orEmpty()}",
                            "WARN"
                        )
                        // Do not spend the full native seek timeout again for every
                        // timestamp in this batch. The next batch may retry libmpv.
                        mpvAvailable = false
                        engine.close()
                    }
                    if (bitmap == null) {
                        if (!platformAvailable) {
                            platformAvailable = platformEngine.open(mediaUrl)
                            diagnostic(
                                request,
                                if (platformAvailable) "Platform Decoder Opened" else "Platform Decoder Open Failed",
                                platformEngine.lastError.orEmpty(),
                                if (platformAvailable) "INFO" else "ERROR"
                            )
                        }
                        if (platformAvailable) {
                            usedEngine = "platform"
                            bitmap = platformEngine.capture(positionMs)
                        }
                    }
                    if (bitmap == null) {
                        consecutiveFailures++
                        val engineError = if (usedEngine == "libmpv") {
                            engine.lastError
                        } else {
                            platformEngine.lastError
                        }
                        diagnostic(
                            request,
                            "Frame Failed",
                            "position=${positionMs / 1_000}s engine=$usedEngine " +
                                engineError.orEmpty(),
                            "WARN"
                        )
                    } else {
                        val stored = cache.store(
                            profileId = request.profileId,
                            contentId = request.contentId,
                            positionMs = positionMs,
                            intervalSeconds = request.intervalSeconds,
                            bitmap = bitmap
                        )
                        // A decoded bitmap proves the native pipeline is healthy.
                        // Duplicate/rejected cache writes must not repeatedly abort
                        // the same opening timestamps on every worker session.
                        consecutiveFailures = 0
                        if (stored) {
                            storedFrames++
                            diagnostic(
                                request,
                                "Frame Cached",
                                "position=${positionMs / 1_000}s engine=$usedEngine " +
                                    "batch=$storedFrames/$MAX_FRAMES_PER_SESSION"
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    consecutiveFailures++
                }
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) break
                // Keep one decoder alive for a larger batch. Reopening the same
                // remote file repeatedly is substantially slower than seeking an
                // already indexed stream in this isolated process.
                if (attemptedFrames >= MAX_FRAMES_PER_SESSION) break
                if (FRAME_GAP_MS > 0L) delay(FRAME_GAP_MS)
            }
        } catch (cancellation: CancellationException) {
            cancelled = true
            throw cancellation
        } finally {
            engine.close()
            platformEngine.close()
            // If this process reaches finally, it did not die in native code.
            // Never leave a known/recoverable error latched as a permanent crash.
            mpvGuard.delete()
            diagnostic(
                request,
                "Batch Finished",
                "attempted=$attemptedFrames cached=$storedFrames " +
                    PlaybackDiagnostics.memorySummary()
            )
            if (!cancelled) updateRetryBackoff(request, storedFrames > 0)
        }
    }

    private fun diagnostic(
        request: SeekThumbnailWorkerRequest,
        event: String,
        details: String = "",
        level: String = "INFO"
    ) {
        PlaybackDiagnostics.event(
            context = applicationContext,
            sessionId = request.diagnosticsSessionId,
            component = "Thumbnails",
            event = event,
            details = details,
            level = level
        )
    }

    private fun mpvCrashGuard(request: SeekThumbnailWorkerRequest): File = File(
        File(cacheDir, MPV_GUARD_DIRECTORY),
        "${request.key.hashCode()}.guard"
    )

    private fun retryAllowed(request: SeekThumbnailWorkerRequest): Boolean {
        val retryAt = com.saab.tv.data.account.AccountStorage.preferences(this, PREFS_NAME)
            .getLong("$RETRY_AFTER_PREFIX${request.key.hashCode()}", 0L)
        return System.currentTimeMillis() >= retryAt
    }

    private fun updateRetryBackoff(request: SeekThumbnailWorkerRequest, generatedFrame: Boolean) {
        val key = "$RETRY_AFTER_PREFIX${request.key.hashCode()}"
        com.saab.tv.data.account.AccountStorage.preferences(this, PREFS_NAME).edit().apply {
            if (generatedFrame) remove(key)
            else putLong(key, System.currentTimeMillis() + FAILED_BATCH_RETRY_DELAY_MS)
        }.apply()
    }

    private fun cancel(requestKey: String?) {
        if (requestKey == null || requestKey == activeRequestKey) {
            generationJob?.cancel()
            if (generationJob == null) {
                activeRequestKey = null
                stopSelf()
            }
        }
    }

    private fun hasMemoryHeadroom(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return true
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return SeekThumbnailWorkerMemoryPolicy.hasHeadroom(
            systemLowMemory = memoryInfo.lowMemory,
            availableSystemBytes = memoryInfo.availMem,
            systemLowMemoryThresholdBytes = memoryInfo.threshold,
            workerPssKb = Debug.getPss()
        )
    }

    override fun onDestroy() {
        generationJob?.cancel()
        scope.cancel()
        workerDispatcher.close()
        super.onDestroy()
    }

    private fun Intent.toRequest(): SeekThumbnailWorkerRequest? {
        val profileId = getIntExtra(EXTRA_PROFILE_ID, 0)
        val contentId = getStringExtra(EXTRA_CONTENT_ID).orEmpty()
        val mediaUrl = getStringExtra(EXTRA_MEDIA_URL).orEmpty()
        val durationMs = getLongExtra(EXTRA_DURATION_MS, 0L)
        val intervalSeconds = getIntExtra(EXTRA_INTERVAL_SECONDS, 10)
        val priorityPositionMs = getLongExtra(EXTRA_PRIORITY_POSITION_MS, 0L)
        val diagnosticsSessionId = getStringExtra(EXTRA_DIAGNOSTICS_SESSION_ID).orEmpty()
        return SeekThumbnailWorkerRequest(
            profileId = profileId,
            contentId = contentId,
            mediaUrl = mediaUrl,
            durationMs = durationMs,
            intervalSeconds = SeekThumbnailCache.normalizeInterval(intervalSeconds),
            priorityPositionMs = priorityPositionMs.coerceAtLeast(0L),
            diagnosticsSessionId = diagnosticsSessionId
        ).takeIf {
            it.profileId > 0 && it.contentId.isNotBlank() && it.mediaUrl.isNotBlank() &&
                it.durationMs > 0L
        }
    }

    companion object {
        private const val ACTION_START = "com.saab.tv.thumbnail.START"
        private const val ACTION_PAUSE = "com.saab.tv.thumbnail.PAUSE"
        private const val ACTION_PRIORITIZE = "com.saab.tv.thumbnail.PRIORITIZE"
        private const val ACTION_CANCEL = "com.saab.tv.thumbnail.CANCEL"
        private const val EXTRA_PROFILE_ID = "profile_id"
        private const val EXTRA_CONTENT_ID = "content_id"
        private const val EXTRA_MEDIA_URL = "media_url"
        private const val EXTRA_DURATION_MS = "duration_ms"
        private const val EXTRA_INTERVAL_SECONDS = "interval_seconds"
        private const val EXTRA_PRIORITY_POSITION_MS = "priority_position_ms"
        private const val EXTRA_DIAGNOSTICS_SESSION_ID = "diagnostics_session_id"
        private const val EXTRA_REQUEST_KEY = "request_key"
        private const val MPV_GUARD_DIRECTORY = "seek_thumbnail_mpv_guard"
        private const val MAX_FRAMES_PER_SESSION = 500
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val MEMORY_CHECK_INTERVAL_FRAMES = 8
        private const val FRAME_GAP_MS = 0L
        private const val PAUSE_POLL_INTERVAL_MS = 150L
        private const val PREFS_NAME = "seek_thumbnail_worker"
        private const val PROFILE_ENABLED_PREFIX = "profile_enabled_"
        private const val RETRY_AFTER_PREFIX = "retry_after_"
        private const val FAILED_BATCH_RETRY_DELAY_MS = 30_000L

        private fun isProfileEnabled(context: Context, profileId: Int): Boolean =
            com.saab.tv.data.account.AccountStorage.preferences(context, PREFS_NAME)
                .getBoolean("$PROFILE_ENABLED_PREFIX$profileId", true)

        fun setProfileEnabled(context: Context, profileId: Int, enabled: Boolean) {
            if (profileId <= 0) return
            com.saab.tv.data.account.AccountStorage.preferences(context, PREFS_NAME)
                .edit()
                .putBoolean("$PROFILE_ENABLED_PREFIX$profileId", enabled)
                .apply()
            if (!enabled) cancelAll(context)
        }

        fun start(context: Context, request: SeekThumbnailWorkerRequest) {
            val intent = Intent(context, SeekThumbnailWorkerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROFILE_ID, request.profileId)
                putExtra(EXTRA_CONTENT_ID, request.contentId)
                putExtra(EXTRA_MEDIA_URL, request.mediaUrl)
                putExtra(EXTRA_DURATION_MS, request.durationMs)
                putExtra(EXTRA_INTERVAL_SECONDS, request.intervalSeconds)
                putExtra(EXTRA_PRIORITY_POSITION_MS, request.priorityPositionMs)
                putExtra(EXTRA_DIAGNOSTICS_SESSION_ID, request.diagnosticsSessionId)
            }
            context.startService(intent)
        }

        fun pause(context: Context) {
            context.startService(Intent(context, SeekThumbnailWorkerService::class.java).apply {
                action = ACTION_PAUSE
            })
        }

        fun prioritize(context: Context, request: SeekThumbnailWorkerRequest, positionMs: Long) {
            runCatching {
                context.startService(Intent(context, SeekThumbnailWorkerService::class.java).apply {
                    action = ACTION_PRIORITIZE
                    putExtra(EXTRA_REQUEST_KEY, request.key)
                    putExtra(EXTRA_PRIORITY_POSITION_MS, positionMs)
                })
            }
        }

        fun cancel(context: Context, request: SeekThumbnailWorkerRequest) {
            context.startService(Intent(context, SeekThumbnailWorkerService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_REQUEST_KEY, request.key)
            })
        }

        fun cancelAll(context: Context) {
            runCatching {
                context.startService(Intent(context, SeekThumbnailWorkerService::class.java).apply {
                    action = ACTION_CANCEL
                })
            }
        }
    }
}

/** Pure policy kept separate so low-memory behavior is unit-testable. */
object SeekThumbnailWorkerMemoryPolicy {
    private const val ABSOLUTE_MIN_SYSTEM_HEADROOM_BYTES = 128L * 1024L * 1024L
    private const val LOW_MEMORY_THRESHOLD_MARGIN_BYTES = 64L * 1024L * 1024L
    private const val MAX_WORKER_PSS_KB = 256L * 1024L

    fun hasHeadroom(
        systemLowMemory: Boolean,
        availableSystemBytes: Long,
        systemLowMemoryThresholdBytes: Long,
        workerPssKb: Long
    ): Boolean {
        val requiredHeadroom = maxOf(
            ABSOLUTE_MIN_SYSTEM_HEADROOM_BYTES,
            systemLowMemoryThresholdBytes.coerceAtLeast(0L) +
                LOW_MEMORY_THRESHOLD_MARGIN_BYTES
        )
        return !systemLowMemory &&
            availableSystemBytes >= requiredHeadroom &&
            workerPssKb in 0..MAX_WORKER_PSS_KB
    }
}
