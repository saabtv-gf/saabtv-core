package com.saab.tv.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.graphics.Bitmap
import com.saab.tv.data.cache.SeekThumbnailCache
import com.saab.tv.data.cache.SeekThumbnailProgress
import com.saab.tv.data.local.AddonDao
import com.saab.tv.data.model.WatchHistoryEntity
import com.saab.tv.data.trakt.TraktScrobbleManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val dao: AddonDao,
    private val traktScrobbleManager: TraktScrobbleManager,
    private val subtitleRepository: com.saab.tv.data.repository.SubtitleRepository,
    private val seekThumbnailCache: SeekThumbnailCache,
    private val accountSync: com.saab.tv.data.account.AccountSyncManager
) : ViewModel() {
    private val completedThisSession = ConcurrentHashMap.newKeySet<String>()
    private val historyWriteMutex = Mutex()

    suspend fun streamSubtitles(
        mediaType: String,
        playbackId: String,
        source: com.saab.tv.ui.player.base.PlayerSourceOption
    ): List<com.saab.tv.ui.player.base.PlayerSubtitleSource> =
        subtitleRepository.getSubtitles(
            mediaType, playbackId,
            videoSize = source.videoSize,
            filename = source.fileName.takeIf { it.isNotBlank() } ?: source.title?.lineSequence()?.firstOrNull(),
            lookupTimeoutMs = 2_000L
        ).map { subtitle ->
            com.saab.tv.ui.player.base.PlayerSubtitleSource(
                id = subtitle.id,
                url = subtitle.url,
                label = listOfNotNull(subtitle.addonName, subtitle.releaseName).joinToString(" · "),
                language = subtitle.lang
            )
        }

    fun saveProgress(
        id: String,
        type: String,
        title: String,
        poster: String?,
        position: Long,
        duration: Long?,
        syncBoundary: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            if (id.startsWith("trailer_")) return@launch
            val safePosition = position.coerceAtLeast(0L)
            if (safePosition < 5_000L) return@launch
            historyWriteMutex.withLock {
                val existing = dao.getHistoryItem(id)
                val watchedThreshold = watchedThresholdRatio()
                val progress = WatchProgressPolicy.evaluate(
                    positionMs = safePosition,
                    reportedDurationMs = duration,
                    existingDurationMs = existing?.duration,
                    watchedThreshold = watchedThreshold,
                    forceCompleted = completedThisSession.contains(id)
                )

                val entry = WatchHistoryEntity(
                    id = id,
                    title = title,
                    poster = poster ?: existing?.poster,
                    background = existing?.background,
                    logo = existing?.logo,
                    position = safePosition,
                    duration = progress.storedDurationMs,
                    lastWatched = System.currentTimeMillis(),
                    type = type.ifBlank { "movie" },
                    watched = progress.isCompleted || existing?.watched == true,
                    scrobbled = existing?.scrobbled ?: traktScrobbleManager.isScrobbled(id)
                )
                dao.upsertHistory(entry)
                accountSync.requestSync(progressOnly = !syncBoundary, urgent = syncBoundary || progress.isCompleted)
                if (progress.isCompleted) {
                    dao.getActiveProfileId()?.let { profileId ->
                        seekThumbnailCache.clearContent(profileId, id)
                    }
                }
            }
        }
    }

    fun markCompleted(
        id: String,
        type: String,
        title: String,
        poster: String?,
        position: Long,
        duration: Long?
    ) {
        if (id.startsWith("trailer_")) return
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            historyWriteMutex.withLock {
                val existing = dao.getHistoryItem(id)
                val safePosition = position.coerceAtLeast(0L)
                val progress = WatchProgressPolicy.evaluate(
                    positionMs = safePosition,
                    reportedDurationMs = duration,
                    existingDurationMs = existing?.duration,
                    watchedThreshold = watchedThresholdRatio(),
                    forceCompleted = true
                )
                if (progress.isCompleted) completedThisSession.add(id)
                dao.upsertHistory(
                    WatchHistoryEntity(
                        id = id,
                        title = title.ifBlank { existing?.title.orEmpty() },
                        poster = poster ?: existing?.poster,
                        background = existing?.background,
                        logo = existing?.logo,
                        position = safePosition,
                        duration = progress.storedDurationMs,
                        lastWatched = System.currentTimeMillis(),
                        type = type.ifBlank { existing?.type ?: "movie" },
                        watched = progress.isCompleted,
                        scrobbled = existing?.scrobbled ?: traktScrobbleManager.isScrobbled(id)
                    )
                )
                accountSync.requestSync(urgent = true)
                if (progress.isCompleted) {
                    dao.getActiveProfileId()?.let { profileId ->
                        seekThumbnailCache.clearContent(profileId, id)
                    }
                }
            }
        }
    }

    suspend fun storeSeekThumbnail(
        profileId: Int,
        contentId: String,
        positionMs: Long,
        intervalSeconds: Int,
        bitmap: Bitmap
    ): Boolean = seekThumbnailCache.store(
        profileId = profileId,
        contentId = contentId,
        positionMs = positionMs,
        intervalSeconds = intervalSeconds,
        bitmap = bitmap
    )

    suspend fun loadSeekThumbnail(
        profileId: Int,
        contentId: String,
        positionMs: Long,
        intervalSeconds: Int
    ): Bitmap? = seekThumbnailCache.loadNearest(
        profileId = profileId,
        contentId = contentId,
        positionMs = positionMs,
        intervalSeconds = intervalSeconds
    )

    suspend fun seekThumbnailCacheProgress(
        profileId: Int,
        contentId: String,
        durationMs: Long,
        intervalSeconds: Int
    ): SeekThumbnailProgress = seekThumbnailCache.progress(
        profileId = profileId,
        contentId = contentId,
        durationMs = durationMs,
        intervalSeconds = intervalSeconds
    )

    // ── Trakt Scrobbling ──

    fun scrobbleStart(id: String, type: String, positionMs: Long, durationMs: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            traktScrobbleManager.scrobbleStart(id, type, positionMs, durationMs)
        }
    }

    fun scrobblePause(id: String, type: String, positionMs: Long, durationMs: Long, force: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            traktScrobbleManager.scrobblePause(id, type, positionMs, durationMs, force = force)
        }
    }

    fun scrobbleStop(id: String, type: String, positionMs: Long, durationMs: Long) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            traktScrobbleManager.scrobbleStop(id, type, positionMs, durationMs)
        }
    }

    suspend fun getResumePosition(id: String): Long {
        return withContext(Dispatchers.IO) {
            val item = dao.getHistoryItem(id)
            val watchedThreshold = watchedThresholdRatio()
            val canResume = item != null && WatchProgressPolicy.canResume(
                positionMs = item.position,
                durationMs = item.duration,
                isWatched = item.watched,
                watchedThreshold = watchedThreshold
            )
            if (canResume) item.position else 0L
        }
    }

    private suspend fun watchedThresholdRatio(): Double {
        val profileId = dao.getActiveProfileId() ?: return 0.95
        val percent = dao.getProfileById(profileId)?.watchedThreshold ?: 95
        return percent.coerceIn(50, 99) / 100.0
    }
}
