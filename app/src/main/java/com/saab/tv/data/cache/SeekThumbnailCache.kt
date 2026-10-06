package com.saab.tv.data.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class SeekThumbnailProgress(
    val cachedFrames: Int,
    val totalFrames: Int,
    val percent: Int
)

@Singleton
class SeekThumbnailCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val root = File(context.cacheDir, "$CACHE_DIRECTORY/${com.saab.tv.data.account.AccountStorage.scope(context)}")
    private val ioDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "seek-thumbnail-cache").apply { priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()
    private val thumbnailIndexByDirectory = mutableMapOf<String, MutableMap<Long, File>>()
    private val lastDirectoryScanElapsedMs = mutableMapOf<String, Long>()
    private var localWriterMode = false
    private var writesSinceTrim = 0

    init {
        // Older versions may contain PlayerView captures or libmpv BGRA frames
        // interpreted as RGBA. Never expose stale control overlays or blue-tinted
        // thumbnails after upgrading the pixel pipeline.
        Thread({
            OBSOLETE_CACHE_DIRECTORIES.forEach { directory ->
                runCatching { File(context.cacheDir, directory).deleteRecursively() }
            }
        }, "seek-thumbnail-cache-migration").apply {
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    suspend fun store(
        profileId: Int,
        contentId: String,
        positionMs: Long,
        intervalSeconds: Int,
        bitmap: Bitmap
    ): Boolean = withContext(ioDispatcher) {
        try {
            if (profileId <= 0 || contentId.isBlank() || bitmap.width <= 0 || bitmap.height <= 0) {
                return@withContext false
            }
            val safeInterval = normalizeInterval(intervalSeconds)
            val timestampSeconds = quantizePositionSeconds(positionMs, safeInterval)
            val directory = contentDirectory(profileId, contentId)
            if (!directory.exists() && !directory.mkdirs()) return@withContext false
            val destination = File(directory, "$timestampSeconds.webp")
            if (destination.exists()) {
                destination.setLastModified(System.currentTimeMillis())
                thumbnailIndexByDirectory[directory.absolutePath]?.set(timestampSeconds, destination)
                return@withContext false
            }

            val temporary = File(
                directory,
                "$timestampSeconds-${android.os.Process.myPid()}-${System.nanoTime()}.tmp"
            )
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            val written = runCatching {
                FileOutputStream(temporary).use { output ->
                    bitmap.compress(format, WEBP_QUALITY, output)
                }
            }.getOrDefault(false)
            if (!written || !temporary.renameTo(destination)) {
                temporary.delete()
                return@withContext false
            }
            destination.setLastModified(System.currentTimeMillis())
            thumbnailIndexByDirectory[directory.absolutePath]?.set(timestampSeconds, destination)
            directory.setLastModified(System.currentTimeMillis())
            writesSinceTrim++
            if (writesSinceTrim >= TRIM_EVERY_WRITES) {
                trimToSize()
                writesSinceTrim = 0
            }
            true
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    suspend fun loadNearest(
        profileId: Int,
        contentId: String,
        positionMs: Long,
        intervalSeconds: Int
    ): Bitmap? = withContext(ioDispatcher) {
        if (profileId <= 0 || contentId.isBlank()) return@withContext null
        val targetSeconds = ThumbnailTimelinePolicy.gridPosition(positionMs, normalizeInterval(intervalSeconds)) / 1_000L
        val directory = if (ThumbnailTimelinePolicy.isSourceScoped(contentId)) contentDirectory(profileId, contentId)
            else profileDirectory(profileId).listFiles().orEmpty()
                .filter { decodeContentId(it.name)?.let { id -> ThumbnailTimelinePolicy.belongsTo(id, contentId) } == true }
                .maxByOrNull { it.lastModified() } ?: contentDirectory(profileId, contentId)
        val nearest = thumbnailIndex(directory)[targetSeconds]?.takeIf { it.exists() }
            ?: return@withContext null
        nearest.setLastModified(System.currentTimeMillis())
        BitmapFactory.decodeFile(nearest.absolutePath)
    }

    suspend fun progressPercent(
        profileId: Int,
        contentId: String,
        durationMs: Long,
        intervalSeconds: Int
    ): Int = progress(profileId, contentId, durationMs, intervalSeconds).percent

    suspend fun progress(
        profileId: Int,
        contentId: String,
        durationMs: Long,
        intervalSeconds: Int
    ): SeekThumbnailProgress = withContext(ioDispatcher) {
        if (profileId <= 0 || contentId.isBlank() || durationMs <= 0L) {
            return@withContext SeekThumbnailProgress(0, 0, 0)
        }
        val targets = storyboardTargets(durationMs, intervalSeconds)
        if (targets.isEmpty()) return@withContext SeekThumbnailProgress(0, 0, 0)
        val expectedSeconds = targets.asSequence().map { it / 1_000L }.toHashSet()
        val directory = contentDirectory(profileId, contentId)
        val cached = thumbnailIndex(directory).count { (seconds, file) ->
            seconds in expectedSeconds && file.exists()
        }
        SeekThumbnailProgress(
            cachedFrames = cached,
            totalFrames = targets.size,
            percent = calculateProgressPercent(cached, targets.size)
        )
    }

    suspend fun contains(
        profileId: Int,
        contentId: String,
        positionMs: Long,
        intervalSeconds: Int
    ): Boolean = withContext(ioDispatcher) {
        if (profileId <= 0 || contentId.isBlank()) return@withContext false
        val timestampSeconds = quantizePositionSeconds(positionMs, intervalSeconds)
        val directory = contentDirectory(profileId, contentId)
        thumbnailIndex(directory)[timestampSeconds]?.exists() == true
    }

    suspend fun clearContent(profileId: Int, contentId: String) = withContext(ioDispatcher) {
        if (profileId > 0 && contentId.isNotBlank()) {
            profileDirectory(profileId).listFiles().orEmpty().filter {
                decodeContentId(it.name)?.let { id -> ThumbnailTimelinePolicy.belongsTo(id, contentId) } == true
            }.forEach { directory ->
                thumbnailIndexByDirectory.remove(directory.absolutePath)
                lastDirectoryScanElapsedMs.remove(directory.absolutePath)
                directory.deleteRecursively()
            }
        }
    }

    suspend fun clearContentPrefix(profileId: Int, contentIdPrefix: String) = withContext(ioDispatcher) {
        if (profileId <= 0 || contentIdPrefix.isBlank()) return@withContext
        profileDirectory(profileId).listFiles().orEmpty().forEach { directory ->
            val decodedId = decodeContentId(directory.name) ?: return@forEach
            if (decodedId.startsWith(contentIdPrefix)) {
                thumbnailIndexByDirectory.remove(directory.absolutePath)
                lastDirectoryScanElapsedMs.remove(directory.absolutePath)
                directory.deleteRecursively()
            }
        }
    }

    suspend fun clearProfile(profileId: Int) = withContext(ioDispatcher) {
        if (profileId > 0) {
            val directory = profileDirectory(profileId)
            thumbnailIndexByDirectory.keys.removeAll { it.startsWith(directory.absolutePath) }
            lastDirectoryScanElapsedMs.keys.removeAll { it.startsWith(directory.absolutePath) }
            directory.deleteRecursively()
        }
    }

    /**
     * The isolated worker is the only writer in its process, so one initial disk
     * scan is enough. Avoiding a directory listing before every frame prevents
     * generation from becoming progressively slower as the cache grows.
     */
    fun optimizeForLocalWriter() {
        localWriterMode = true
    }

    private fun trimToSize() {
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension == "webp" }
            .sortedBy(File::lastModified)
            .toList()
        var totalBytes = files.sumOf(File::length)
        for (file in files) {
            if (totalBytes <= MAX_CACHE_BYTES) break
            val length = file.length()
            if (file.delete()) {
                totalBytes -= length
                thumbnailIndexByDirectory.values.forEach { index ->
                    index.entries.removeAll { it.value == file }
                }
            }
        }
    }

    private fun thumbnailIndex(directory: File): MutableMap<Long, File> {
        val path = directory.absolutePath
        val index = thumbnailIndexByDirectory.getOrPut(path) { mutableMapOf() }
        val now = SystemClock.elapsedRealtime()
        val lastScan = lastDirectoryScanElapsedMs[path]
        val shouldScan = lastScan == null ||
            (!localWriterMode && now - lastScan >= DIRECTORY_RESCAN_INTERVAL_MS)
        if (shouldScan) {
            index.clear()
            directory.listFiles { file -> file.isFile && file.extension == "webp" }
                .orEmpty()
                .forEach { file ->
                    file.nameWithoutExtension.toLongOrNull()?.let { timestamp ->
                        index[timestamp] = file
                    }
                }
            lastDirectoryScanElapsedMs[path] = now
        }
        return index
    }

    private fun contentDirectory(profileId: Int, contentId: String): File =
        File(profileDirectory(profileId), encodeContentId(contentId))

    private fun profileDirectory(profileId: Int): File = File(root, profileId.toString())

    private fun encodeContentId(contentId: String): String = Base64.encodeToString(
        contentId.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )

    private fun decodeContentId(encoded: String): String? = runCatching {
        String(
            Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
            Charsets.UTF_8
        )
    }.getOrNull()

    companion object {
        private const val CACHE_DIRECTORY = "seek_thumbnails_v5"
        private val OBSOLETE_CACHE_DIRECTORIES = listOf("seek_thumbnails", "seek_thumbnails_v2", "seek_thumbnails_v3", "seek_thumbnails_v4")
        private const val MAX_CACHE_BYTES = 500L * 1_024L * 1_024L
        private const val WEBP_QUALITY = 75
        private const val TRIM_EVERY_WRITES = 50
        private const val DIRECTORY_RESCAN_INTERVAL_MS = 400L
        val SUPPORTED_INTERVALS = setOf(10, 20, 30)

        fun normalizeInterval(seconds: Int): Int = seconds.takeIf(SUPPORTED_INTERVALS::contains) ?: 10

        fun quantizePositionSeconds(positionMs: Long, intervalSeconds: Int): Long {
            val interval = normalizeInterval(intervalSeconds).toLong()
            val seconds = positionMs.coerceAtLeast(0L) / 1_000L
            return (seconds / interval) * interval
        }

        /** Every configured interval, in playback order. */
        fun storyboardTargets(durationMs: Long, intervalSeconds: Int): List<Long> {
            if (durationMs <= 0L) return emptyList()
            val intervalMs = normalizeInterval(intervalSeconds) * 1_000L
            val lastTarget = (durationMs - 1_000L).coerceAtLeast(0L)
            return generateSequence(0L) { previous ->
                (previous + intervalMs).takeIf { it <= lastTarget }
            }.toList()
        }

        /**
         * Limits aggressive startup extraction to the beginning of the video.
         * Rendered playback frames can extend the cache without a second decoder.
         */
        fun startupStoryboardTargets(
            durationMs: Long,
            intervalSeconds: Int,
            startupWindowMs: Long
        ): List<Long> = storyboardTargets(durationMs, intervalSeconds)
            .takeWhile { it <= startupWindowMs.coerceAtLeast(0L) }

        /** Fast visible coverage first, followed by full-resolution refinement. */
        fun progressiveStoryboardTargets(durationMs: Long, intervalSeconds: Int): List<Long> {
            val normalizedInterval = normalizeInterval(intervalSeconds)
            // Coarse timestamps must align with final cache buckets. Thirty-second
            // sampling aligns with 10 seconds, while 20 and 30 already are coarse.
            val coarseInterval = if (normalizedInterval == 10) 30 else normalizedInterval
            return buildList {
                addAll(startupStoryboardTargets(durationMs, normalizedInterval, 5L * 60L * 1_000L))
                addAll(storyboardTargets(durationMs, coarseInterval))
                if (normalizedInterval < coarseInterval) {
                    addAll(storyboardTargets(durationMs, normalizedInterval))
                }
            }.distinct()
        }

        /**
         * ThumbFast-style persistent decoders start with useful seek positions.
         * Timestamp zero is commonly a black pre-roll frame, so it is filled last.
         */
        fun persistentDecoderTargets(
            durationMs: Long,
            intervalSeconds: Int,
            priorityPositionMs: Long = 0L
        ): List<Long> {
            val targets = progressiveStoryboardTargets(durationMs, intervalSeconds)
            if (priorityPositionMs <= 0L) {
                return targets.filterNot { it == 0L } + targets.filter { it == 0L }
            }
            val all = storyboardTargets(durationMs, intervalSeconds)
            val anchor = quantizePositionSeconds(priorityPositionMs, intervalSeconds) * 1_000L
            val step = normalizeInterval(intervalSeconds) * 1_000L
            // Five carousel frames first, then monotonic forward reads, followed
            // by earlier gaps. No timestamp is lost when the priority changes.
            val nearby = listOf(anchor, anchor + step, anchor + 2 * step, anchor - step, anchor - 2 * step)
                .filter { it in all }
            return (nearby + all.filter { it >= anchor } + all.filter { it < anchor }).distinct()
        }

        fun calculateProgressPercent(cachedFrames: Int, totalFrames: Int): Int {
            if (cachedFrames <= 0 || totalFrames <= 0) return 0
            return kotlin.math.ceil(cachedFrames * 100.0 / totalFrames)
                .toInt()
                .coerceIn(1, 100)
        }
    }
}
