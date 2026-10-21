package com.saab.tv.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.graphics.Bitmap
import com.saab.tv.data.cache.SeekThumbnailCache
import com.saab.tv.data.cache.SeekThumbnailProgress
import com.saab.tv.data.cache.ThumbnailStoreResult
import com.saab.tv.data.local.AddonDao
import com.saab.tv.data.model.WatchHistoryEntity
import com.saab.tv.data.trakt.TraktScrobbleManager
import com.saab.tv.data.tmdb.TmdbMetadataService
import com.saab.tv.data.tmdb.TmdbService
import com.saab.tv.data.tmdb.TmdbCastInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
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
    private val accountSync: com.saab.tv.data.account.AccountSyncManager,
    private val userStateCommands: com.saab.tv.data.account.UserStateCommandRepository,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService
) : ViewModel() {
    suspend fun portableProfileId(profileId: Int): String? = dao.getProfileById(profileId)?.portableId

    suspend fun loadPlaybackCast(contentId: String, mediaType: String, profileId: Int): List<TmdbCastInfo> =
        withContext(Dispatchers.IO) {
            try {
                val tmdbId = tmdbService.ensureTmdbId(contentId, mediaType) ?: return@withContext emptyList()
                val language = dao.getProfileById(profileId)?.tmdbLanguage?.takeIf(String::isNotBlank) ?: "en"
                tmdbMetadataService.fetchCastMembers(tmdbId, mediaType, language)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                com.saab.tv.AppDiagnostics.failure("Playback Cast", "Cast lookup failed", failure)
                emptyList()
            }
        }

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
        syncBoundary: Boolean = false,
        playbackEstablished: Boolean = true,
        profileId: Int
    ): Job {
        val recordedAt = System.currentTimeMillis()
        return viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            if (id.startsWith("trailer_")) return@launch
            val safePosition = position.coerceAtLeast(0L)
            if (!ProgressSnapshotPolicy.shouldSave(safePosition, syncBoundary, playbackEstablished)) return@launch
            historyWriteMutex.withLock {
                val existing = dao.getHistoryItemForProfile(profileId, id)
                if (existing != null && existing.lastWatched > recordedAt) return@withLock
                val watchedThreshold = watchedThresholdRatio(profileId)
                val progress = WatchProgressPolicy.evaluate(
                    positionMs = safePosition,
                    reportedDurationMs = duration,
                    existingDurationMs = existing?.duration,
                    watchedThreshold = watchedThreshold,
                    forceCompleted = completedThisSession.contains("$profileId:$id")
                )

                val entry = WatchHistoryEntity(
                    profileId = profileId,
                    id = id,
                    title = title,
                    poster = poster ?: existing?.poster,
                    background = existing?.background,
                    logo = existing?.logo,
                    position = safePosition,
                    duration = progress.storedDurationMs,
                    lastWatched = recordedAt,
                    type = type.ifBlank { "movie" },
                    watched = WatchProgressPolicy.preserveWatched(progress.isCompleted, existing?.watched == true),
                    scrobbled = existing?.scrobbled ?: traktScrobbleManager.isScrobbled(id)
                )
                val committed = userStateCommands.savePlaybackProgress(entry)
                if (!committed) return@withLock
                accountSync.historyChanged(progressOnly = !syncBoundary,
                    urgent = syncBoundary || (progress.isCompleted && existing?.watched != true))
                if (progress.isCompleted) {
                    seekThumbnailCache.clearContent(profileId, id)
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
        duration: Long?,
        profileId: Int
    ): Job {
        val recordedAt = System.currentTimeMillis()
        return viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            if (id.startsWith("trailer_")) return@launch
            historyWriteMutex.withLock {
                val existing = dao.getHistoryItemForProfile(profileId, id)
                if (existing != null && existing.lastWatched > recordedAt) return@withLock
                val safePosition = position.coerceAtLeast(0L)
                val progress = WatchProgressPolicy.evaluate(
                    positionMs = safePosition,
                    reportedDurationMs = duration,
                    existingDurationMs = existing?.duration,
                    watchedThreshold = watchedThresholdRatio(profileId),
                    forceCompleted = true
                )
                if (progress.isCompleted) completedThisSession.add("$profileId:$id")
                val entry = WatchHistoryEntity(
                        profileId = profileId,
                        id = id,
                        title = title.ifBlank { existing?.title.orEmpty() },
                        poster = poster ?: existing?.poster,
                        background = existing?.background,
                        logo = existing?.logo,
                        position = safePosition,
                        duration = progress.storedDurationMs,
                        lastWatched = recordedAt,
                        type = type.ifBlank { existing?.type ?: "movie" },
                        watched = WatchProgressPolicy.preserveWatched(progress.isCompleted, existing?.watched == true),
                        scrobbled = existing?.scrobbled ?: traktScrobbleManager.isScrobbled(id)
                    )
                val committed = userStateCommands.savePlaybackProgress(entry)
                if (!committed) return@withLock
                accountSync.historyChanged(urgent = true)
                if (progress.isCompleted) {
                    seekThumbnailCache.clearContent(profileId, id)
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
    ) == ThumbnailStoreResult.STORED

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

    suspend fun getResumePosition(id: String, profileId: Int): Long {
        return withContext(Dispatchers.IO) {
            val local = dao.getHistoryItemForProfile(profileId, id)
            // Start from the local snapshot. Waiting for cloud reconciliation here
            // can hold player.prepare() for up to the cloud-history timeout.
            val item = local
            val watchedThreshold = watchedThresholdRatio(profileId)
            val canResume = item != null && WatchProgressPolicy.canResume(
                positionMs = item.position,
                durationMs = item.duration,
                isWatched = item.watched,
                watchedThreshold = watchedThreshold
            )
            val position = if (canResume) item.position else 0L
            com.saab.tv.AppDiagnostics.event("Resume", "Timestamp Resolved",
                "profile=$profileId id=$id position=${position}ms duration=${item?.duration ?: 0}ms watched=${item?.watched ?: false}")
            position
        }
    }

    fun refreshPlaybackHistoryAfterStart(id: String, profileId: Int) {
        if (id.startsWith("trailer_") || profileId <= 0) return
        viewModelScope.launch(Dispatchers.IO) {
            val local = dao.getHistoryItemForProfile(profileId, id)
            accountSync.freshestPlaybackHistory(listOfNotNull(local), profileId)
        }
    }

    private suspend fun watchedThresholdRatio(profileId: Int): Double {
        val percent = dao.getProfileById(profileId)?.watchedThreshold ?: 95
        return percent.coerceIn(50, 99) / 100.0
    }
}
