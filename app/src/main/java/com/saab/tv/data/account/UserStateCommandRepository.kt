package com.saab.tv.data.account

import androidx.room.withTransaction
import com.google.gson.Gson
import com.saab.tv.data.local.SaabTvDatabase
import com.saab.tv.data.model.SyncClientStateEntity
import com.saab.tv.data.model.SyncEntityRevisionEntity
import com.saab.tv.data.model.SyncOutboxEntity
import com.saab.tv.data.model.WatchHistoryEntity
import com.saab.tv.data.model.WatchlistEntity
import com.saab.tv.data.model.SeriesNextUpEntity
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.profile.ProfileRuntimeSnapshot
import com.saab.tv.data.repository.CanonicalIdentityStore
import com.saab.tv.data.repository.NoopCanonicalIdentityStore
import com.saab.tv.domain.schema.CanonicalEntityKey
import com.saab.tv.domain.schema.addonInstallationKey
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A local user-state mutation and its sync event are committed as one Room transaction. */
@Singleton
class UserStateCommandRepository @Inject constructor(
    private val database: SaabTvDatabase,
    private val auth: AccountAuthManager,
    private val canonicalIdentityStore: CanonicalIdentityStore
) {
    constructor(database: SaabTvDatabase, auth: AccountAuthManager) :
        this(database, auth, NoopCanonicalIdentityStore)

    private val gson = Gson()

    /**
     * Persists a profile preference change locally and journals one portable profile value.
     * Room's integer row id and active-device flag are deliberately excluded from the payload.
     */
    suspend fun saveProfile(profile: ProfileEntity): ProfileEntity = database.withTransaction {
        require(profile.portableId.isNotBlank() && profile.portableId.length <= 128)
        val dao = database.addonDao()
        val existing = dao.getProfileByPortableId(profile.portableId)
        val value = profile.copy(
            id = existing?.id ?: 0,
            isActive = existing?.isActive ?: false
        )
        dao.insertProfile(value)
        val stored = dao.getProfileByPortableId(profile.portableId)
            ?: error("Profile could not be persisted")
        val payload = ProfileStatePayload(stored.copy(id = 0, isActive = false))
        append(
            stored.portableId,
            "profile_state",
            CanonicalEntityKey.profile(stored.portableId),
            "UPSERT",
            gson.toJson(payload)
        )
        stored
    }

    suspend fun deleteProfile(profileId: Int): Boolean = database.withTransaction {
        val dao = database.addonDao()
        val profile = dao.getProfileById(profileId) ?: return@withTransaction false
        dao.deleteWatchlistForProfile(profileId)
        dao.deleteHistoryForProfile(profileId)
        dao.deleteSeriesNextUpForProfile(profileId)
        dao.deleteRuntimeState(profile.portableId)
        dao.deleteProfile(profileId)
        auth.userId?.let { database.syncOutboxDao().clearPendingForProfile(it, profile.portableId) }
        append(
            profile.portableId,
            "profile_state",
            CanonicalEntityKey.profile(profile.portableId),
            "DELETE",
            gson.toJson(ProfileDeletionPayload(profile.portableId))
        )
        true
    }

    /** One bounded current-state value for portable runtime configuration. */
    suspend fun saveProfileRuntime(profileId: Int, snapshot: ProfileRuntimeSnapshot): Boolean = database.withTransaction {
        val profile = requireNotNull(database.addonDao().getProfileById(profileId)) { "Profile no longer exists" }
        val identity = CanonicalEntityKey.profileRuntime(profile.portableId)
        val portableSnapshot = snapshot.ownedBy(profile.portableId)
        val payload = gson.toJson(ProfileRuntimeStatePayload(portableSnapshot))
        // Keep very large addon manifests on the encrypted compatibility snapshot path rather
        // than failing a successful local installation or exceeding the bounded v3 envelope.
        if (payload.toByteArray(Charsets.UTF_8).size > MAX_OPERATION_BYTES) {
            removePendingRuntime(profile.portableId, identity)
            return@withTransaction false
        }
        append(profile.portableId, "profile_runtime", identity, "UPSERT", payload)
        true
    }

    private suspend fun removePendingRuntime(portableProfileId: String, identity: String) {
        val accountUserId = auth.userId ?: return
        val aggregate = "$accountUserId\u0000$portableProfileId\u0000profile_runtime\u0000$identity"
        database.syncOutboxDao().coalesceEntity(sha256(aggregate))
    }

    suspend fun toggleLibrary(
        profileId: Int,
        item: WatchlistEntity,
        addonInstallationId: String? = null
    ): Boolean = database.withTransaction {
        val dao = database.addonDao()
        val origin = item.sourceAddonBaseUrl
        val installKey = addonInstallationId ?: origin?.let(::addonInstallationKey)
        val key = canonicalIdentityStore.canonicalTitleKey(item.id, item.type, installKey)
        val existing = (if (installKey == null) dao.getWatchlistItem(profileId, item.id)
            else dao.getWatchlistItemFromAddon(profileId, item.id, installKey))
            ?: dao.getWatchlistItemsByCanonicalIdentity(profileId, key).firstOrNull()
        if (existing != null) {
            dao.removeWatchlistItemsByCanonicalIdentity(profileId, key)
            appendForProfile(profileId, "library", key, "DELETE", LibraryStatePayload(
                existing.id, existing.type, null, existing.sourceAddonBaseUrl,
                existing.sourceAddonKey, key
            ))
            false
        } else {
            val stored = item.copy(
                sourceAddonKey = installKey.orEmpty(),
                canonicalIdentityKey = key
            )
            dao.addToWatchlist(stored)
            appendForProfile(profileId, "library", key, "UPSERT", LibraryStatePayload(item.id, item.type, stored, origin, installKey))
            true
        }
    }

    /** Marks a title and removes its library membership atomically, including series episode rows. */
    suspend fun markWatched(
        profileId: Int,
        titleId: String,
        type: String,
        history: List<WatchHistoryEntity>,
        nextUp: SeriesNextUpEntity? = null,
        libraryAliases: Set<String> = setOf(titleId),
        addonInstallationId: String? = null
    ) = database.withTransaction {
        require(history.all { it.profileId == profileId && it.watched })
        require(nextUp == null || nextUp.profileId == profileId)
        val dao = database.addonDao()
        val normalizedHistory = history.map { it.withCanonicalIdentity() }
        val normalizedNextUp = nextUp?.withCanonicalIdentity()
        if (normalizedHistory.isNotEmpty()) dao.insertHistoryItems(normalizedHistory)
        normalizedNextUp?.let { dao.insertSeriesNextUp(it) }
        libraryAliases.forEach { id ->
            val originKey = id.takeIf { it.isOpaqueProviderId() }?.let { addonInstallationId }
                ?.let(::normalizedAddonKey)
            if (originKey == null) dao.removeFromWatchlist(profileId, id)
            else dao.removeWatchlistItemFromAddon(profileId, id, originKey)
            val identityKey = canonicalIdentityStore.canonicalTitleKey(id, type, originKey)
            dao.removeWatchlistItemsByCanonicalIdentity(profileId, identityKey)
            appendForProfile(profileId, "library", identityKey, "DELETE",
                LibraryStatePayload(id, type, null, sourceAddonKey = originKey, canonicalIdentityKey = identityKey))
        }
        appendForProfile(profileId, "watched_state", canonicalIdentityStore.canonicalTitleKey(titleId, type, addonInstallationId), "UPSERT", WatchedStatePayload(
            titleId = titleId, type = type, watched = true, history = normalizedHistory,
            seriesNextUp = normalizedNextUp,
            canonicalTitleIdentityKey = canonicalIdentityStore.canonicalTitleKey(titleId, type, addonInstallationId)
        ))
        Unit
    }

    suspend fun unmarkWatched(
        profileId: Int,
        titleId: String,
        type: String,
        history: List<WatchHistoryEntity>,
        nextUp: SeriesNextUpEntity? = null
    ) = database.withTransaction {
        require(history.all { it.profileId == profileId && !it.watched })
        require(nextUp == null || nextUp.profileId == profileId)
        val dao = database.addonDao()
        val normalizedHistory = history.map { it.withCanonicalIdentity() }
        val normalizedNextUp = nextUp?.withCanonicalIdentity()
        if (normalizedHistory.isNotEmpty()) dao.insertHistoryItems(normalizedHistory)
        normalizedNextUp?.let { dao.insertSeriesNextUp(it) }
        appendForProfile(profileId, "watched_state", canonicalIdentityStore.canonicalTitleKey(titleId, type), "UPSERT", WatchedStatePayload(
            titleId = titleId, type = type, watched = false, history = normalizedHistory, seriesNextUp = normalizedNextUp
        ))
        Unit
    }

    /** A single episode is a watched-state entity in its own right, not a title payload. */
    suspend fun setEpisodeWatched(
        profileId: Int,
        playbackId: String,
        watched: Boolean,
        history: List<WatchHistoryEntity>,
        nextUp: SeriesNextUpEntity? = null
    ) = database.withTransaction {
        require(playbackId.isNotBlank())
        require(history.isNotEmpty() && history.all { it.profileId == profileId && it.watched == watched })
        require(nextUp == null || nextUp.profileId == profileId)
        val dao = database.addonDao()
        val normalizedHistory = history.map { it.withCanonicalIdentity() }
        val normalizedNextUp = nextUp?.withCanonicalIdentity()
        dao.insertHistoryItems(normalizedHistory)
        normalizedNextUp?.let { dao.insertSeriesNextUp(it) }
        appendForProfile(
            profileId = profileId,
            entityType = "watched_state",
            semanticIdentity = canonicalIdentityStore.canonicalPlayableKey(playbackId, "series"),
            operation = "UPSERT",
            payload = WatchedStatePayload(
                titleId = playbackId,
                type = "series",
                watched = watched,
                history = normalizedHistory,
                seriesNextUp = normalizedNextUp,
                canonicalTitleIdentityKey = canonicalIdentityStore.canonicalTitleKey(playbackId, "series")
            )
        )
        Unit
    }

    /** Replaces the pending episode snapshot after next-up has been recomputed locally. */
    suspend fun updateEpisodeSyncState(
        profileId: Int,
        seriesId: String,
        playbackId: String,
        watched: Boolean,
        history: List<WatchHistoryEntity>,
        nextUp: SeriesNextUpEntity?,
        clearNextUp: Boolean
    ) = database.withTransaction {
        require(seriesId.isNotBlank() && playbackId.isNotBlank())
        require(history.all { it.profileId == profileId && it.watched == watched })
        require(nextUp == null || (nextUp.profileId == profileId && nextUp.seriesId == seriesId))
        val normalizedHistory = history.map { it.withCanonicalIdentity() }
        val normalizedNextUp = nextUp?.withCanonicalIdentity()
        appendForProfile(
            profileId = profileId,
            entityType = "watched_state",
            semanticIdentity = canonicalIdentityStore.canonicalPlayableKey(playbackId, "series"),
            operation = "UPSERT",
            payload = WatchedStatePayload(
                titleId = playbackId,
                type = "series",
                watched = watched,
                history = normalizedHistory,
                seriesNextUp = normalizedNextUp,
                seriesId = seriesId,
                clearSeriesNextUp = clearNextUp,
                canonicalTitleIdentityKey = canonicalIdentityStore.canonicalTitleKey(seriesId, "series")
            )
        )
        Unit
    }

    /** Persists the per-series next-up projection and its tombstone as one portable state write. */
    suspend fun saveSeriesNextUp(profileId: Int, seriesId: String, nextUp: SeriesNextUpEntity?) = database.withTransaction {
        require(seriesId.isNotBlank() && (nextUp == null || nextUp.seriesId == seriesId))
        val dao = database.addonDao()
        val identityKey = canonicalIdentityStore.canonicalTitleKey(seriesId, "series")
        val scoped = nextUp?.copy(profileId = profileId, canonicalIdentityKey = identityKey)
        if (scoped == null) {
            dao.deleteSeriesNextUpForProfile(profileId, seriesId)
            dao.deleteSeriesNextUpByCanonicalIdentity(profileId, identityKey)
        } else {
            dao.insertSeriesNextUp(scoped)
            if (scoped.isComplete) {
                dao.removeFromWatchlist(profileId, seriesId)
                dao.removeWatchlistItemsByCanonicalIdentity(profileId, identityKey)
                appendForProfile(profileId, "library", identityKey, "DELETE",
                    LibraryStatePayload(seriesId, "series", null, canonicalIdentityKey = identityKey))
            }
        }
        appendForProfile(
            profileId = profileId,
            entityType = "series_next_up",
            semanticIdentity = identityKey,
            operation = if (scoped == null) "DELETE" else "UPSERT",
            payload = SeriesNextUpStatePayload(seriesId, scoped, identityKey)
        )
    }

    /** A progress checkpoint is a complete per-playable value, not a max-position merge. */
    suspend fun savePlaybackProgress(entry: WatchHistoryEntity, titleId: String = entry.id): Boolean = database.withTransaction {
        val dao = database.addonDao()
        val existing = dao.getHistoryItemForProfile(entry.profileId, entry.id)
        if (existing != null && existing.lastWatched > entry.lastWatched) return@withTransaction false
        val normalized = entry.withCanonicalIdentity()
        dao.insertHistory(normalized)
        val playableKey = canonicalIdentityStore.canonicalPlayableKey(entry.id, entry.type)
        appendForProfile(entry.profileId, "playback_progress", playableKey, "UPSERT", normalized)
        if (entry.watched && entry.type != "series") {
            val titleKey = canonicalIdentityStore.canonicalTitleKey(titleId, entry.type)
            dao.removeWatchlistItemsByCanonicalIdentity(entry.profileId, titleKey)
            appendForProfile(entry.profileId, "library", titleKey, "DELETE", LibraryStatePayload(titleId, entry.type, null, canonicalIdentityKey = titleKey))
        }
        true
    }

    /** Syncs only the stable torrent identity; signed stream URLs never enter the v3 outbox. */
    suspend fun saveSourceSelection(profileId: Int, selection: SourceSelectionStatePayload) = database.withTransaction {
        require(selection.titleId.isNotBlank() && selection.titleId.length <= 512)
        require(selection.type == "movie" || selection.type == "series")
        require(selection.torrentIdentity.matches(TORRENT_IDENTITY))
        appendForProfile(
            profileId, "source_selection", canonicalIdentityStore.canonicalTitleKey(selection.titleId, selection.type),
            "UPSERT", selection
        )
    }

    suspend fun clearSourceSelection(profileId: Int, titleId: String, type: String) = database.withTransaction {
        require(titleId.isNotBlank() && titleId.length <= 512)
        require(type == "movie" || type == "series")
        appendForProfile(
            profileId, "source_selection", canonicalIdentityStore.canonicalTitleKey(titleId, type), "DELETE",
            SourceSelectionStatePayload(titleId, type, torrentIdentity = "")
        )
    }

    suspend fun markScrobbleAcknowledged(profileId: Int, playableId: String) = database.withTransaction {
        val dao = database.addonDao()
        val current = dao.getHistoryItemForProfile(profileId, playableId) ?: return@withTransaction
        if (current.scrobbled) return@withTransaction
        val acknowledged = current.copy(scrobbled = true).withCanonicalIdentity()
        dao.insertHistory(acknowledged)
        appendForProfile(
            profileId,
            "playback_progress",
            canonicalIdentityStore.canonicalPlayableKey(playableId, acknowledged.type),
            "UPSERT",
            acknowledged
        )
    }

    suspend fun clearPlaybackProgress(profileId: Int, id: String, type: String): Boolean = database.withTransaction {
        val identityKey = canonicalIdentityStore.canonicalPlayableKey(id, type)
        database.addonDao().deleteHistoryItemForProfile(profileId, id)
        database.addonDao().deleteHistoryByCanonicalIdentity(profileId, identityKey)
        appendForProfile(profileId, "playback_progress", identityKey, "DELETE", ProgressTombstone(id, type, identityKey))
        true
    }

    /** Removes externally-cleared watched state locally and publishes both current-state tombstones. */
    suspend fun clearImportedHistory(profileId: Int, entry: WatchHistoryEntity, seriesId: String? = null) = database.withTransaction {
        require(entry.id.isNotBlank() && entry.profileId == profileId)
        val identityKey = canonicalIdentityStore.canonicalPlayableKey(entry.id, entry.type)
        database.addonDao().deleteHistoryItemForProfile(profileId, entry.id)
        database.addonDao().deleteHistoryByCanonicalIdentity(profileId, identityKey)
        appendForProfile(
            profileId, "playback_progress", identityKey, "DELETE",
            ProgressTombstone(entry.id, entry.type, identityKey)
        )
        appendForProfile(
            profileId, "watched_state", canonicalIdentityStore.canonicalPlayableKey(entry.id, entry.type), "UPSERT",
            WatchedStatePayload(
                titleId = entry.id,
                type = entry.type,
                watched = false,
                history = emptyList(),
                seriesNextUp = null,
                seriesId = seriesId,
                clearHistoryIds = listOf(entry.id),
                clearTitleHistory = true,
                clearHistoryCanonicalKeys = listOf(identityKey),
                canonicalTitleIdentityKey = canonicalIdentityStore.canonicalTitleKey(seriesId ?: entry.id, entry.type)
            )
        )
    }

    /** Clears every locally known title/episode row and journals matching sync tombstones atomically. */
    suspend fun clearTitleProgress(
        profileId: Int,
        titleId: String,
        type: String,
        history: List<WatchHistoryEntity>
    ) = database.withTransaction {
        require(titleId.isNotBlank())
        require(history.all { it.profileId == profileId })
        val dao = database.addonDao()
        val ids = (history.map { it.id } + titleId).distinct()
        val canonicalById = ids.associateWith { canonicalIdentityStore.canonicalPlayableKey(it, type) }
        ids.forEach { id ->
            dao.deleteHistoryItemForProfile(profileId, id)
            canonicalById[id]?.let { dao.deleteHistoryByCanonicalIdentity(profileId, it) }
        }
        val canonicalTitleKey = canonicalIdentityStore.canonicalTitleKey(titleId, type)
        if (type == "series") {
            dao.deleteSeriesNextUpForProfile(profileId, titleId)
            dao.deleteSeriesNextUpByCanonicalIdentity(profileId, canonicalTitleKey)
        }
        ids.forEach { id ->
            appendForProfile(
                profileId, "playback_progress", canonicalIdentityStore.canonicalPlayableKey(id, type), "DELETE",
                ProgressTombstone(id, type, canonicalById[id])
            )
        }
        appendForProfile(
            profileId, "watched_state", canonicalIdentityStore.canonicalTitleKey(titleId, type), "UPSERT",
            WatchedStatePayload(
                titleId = titleId,
                type = type,
                watched = false,
                history = emptyList(),
                seriesNextUp = null,
                seriesId = titleId.takeIf { type == "series" },
                clearSeriesNextUp = type == "series",
                clearHistoryIds = ids,
                clearTitleHistory = true,
                canonicalTitleIdentityKey = canonicalTitleKey,
                clearHistoryCanonicalKeys = canonicalById.values.distinct()
            )
        )
        Unit
    }

    suspend fun <T : Any> execute(
        profileId: Int,
        entityType: String,
        entityIdentity: String,
        operation: String,
        payload: Any,
        mutation: suspend () -> T
    ): T = database.withTransaction {
        require(entityType in ENTITY_TYPES) { "Unsupported user-state entity type" }
        require(isSupportedOperation(entityType, operation)) { "Unsupported user-state operation" }
        val profile = requireNotNull(database.addonDao().getProfileById(profileId)) {
            "Profile no longer exists"
        }
        val payloadJson = gson.toJson(payload)
        require(payloadJson.toByteArray(Charsets.UTF_8).size <= MAX_OPERATION_BYTES) {
            "User-state operation exceeds the sync limit"
        }
        val result = mutation()
        append(profile.portableId, entityType, entityIdentity, operation, payloadJson)
        result
    }

    suspend fun appendOnly(
        profileId: Int,
        entityType: String,
        entityIdentity: String,
        operation: String,
        payload: Any
    ) {
        execute(profileId, entityType, entityIdentity, operation, payload) { Unit }
    }

    suspend fun addToLibrary(
        profileId: Int,
        id: String,
        type: String,
        addonInstallationId: String? = null,
        payload: Any,
        mutation: suspend () -> Unit
    ) = execute(
        profileId, "library", canonicalIdentityStore.canonicalTitleKey(id, type, addonInstallationId),
        "UPSERT", payload, mutation
    )

    /** Updates cached display facts without toggling/removing the membership itself. */
    suspend fun saveLibraryItem(
        profileId: Int,
        item: WatchlistEntity,
        addonInstallationId: String? = null,
        mutation: suspend () -> Unit
    ) {
        val installKey = addonInstallationId ?: item.sourceAddonBaseUrl?.let(::addonInstallationKey)
        val identityKey = canonicalIdentityStore.canonicalTitleKey(item.id, item.type, installKey)
        val stored = item.copy(
            sourceAddonKey = installKey.orEmpty(),
            canonicalIdentityKey = identityKey
        )
        execute(
            profileId = profileId,
            entityType = "library",
            entityIdentity = identityKey,
            operation = "UPSERT",
            payload = LibraryStatePayload(stored.id, stored.type, stored, stored.sourceAddonBaseUrl, installKey, identityKey),
            mutation = mutation
        )
    }

    suspend fun removeFromLibrary(
        profileId: Int,
        id: String,
        type: String,
        addonInstallationId: String? = null,
        mutation: suspend () -> Unit
    ) = execute(
        profileId, "library", canonicalIdentityStore.canonicalTitleKey(id, type, addonInstallationId),
        "DELETE", LibraryStatePayload(
            id, type, item = null, sourceAddonKey = addonInstallationId,
            canonicalIdentityKey = canonicalIdentityStore.canonicalTitleKey(id, type, addonInstallationId)
        ), mutation
    )

    suspend fun savePlaybackProgress(
        profileId: Int,
        id: String,
        type: String,
        payload: Any,
        mutation: suspend () -> Unit
    ) = execute(
        profileId, "playback_progress", canonicalIdentityStore.canonicalPlayableKey(id, type),
        "UPSERT", payload, mutation
    )

    suspend fun clearPlaybackProgress(
        profileId: Int,
        id: String,
        type: String,
        mutation: suspend () -> Unit
    ) = execute(
        profileId, "playback_progress", canonicalIdentityStore.canonicalPlayableKey(id, type),
        "DELETE", emptyMap<String, String>(), mutation
    )

    suspend fun pending(limit: Int = 200): List<SyncOutboxEntity> {
        val userId = auth.userId ?: return emptyList()
        val rows = database.syncOutboxDao().pending(userId, limit.coerceIn(1, MAX_BATCH))
        if (rows.isEmpty()) return rows
        return auth.withSyncKey { key ->
            rows.map { it.copy(payloadJson = SyncV3Protocol.decryptLocalPayload(it, userId, key)) }
        }
    }

    suspend fun acknowledge(operationIds: List<String>): Int {
        if (operationIds.isEmpty()) return 0
        return database.syncOutboxDao().acknowledge(operationIds.distinct())
    }

    suspend fun pendingCount(): Int = database.syncOutboxDao().pendingCount()

    private suspend fun appendForProfile(
        profileId: Int,
        entityType: String,
        semanticIdentity: String,
        operation: String,
        payload: Any
    ) {
        require(entityType in ENTITY_TYPES)
        require(isSupportedOperation(entityType, operation))
        val profile = requireNotNull(database.addonDao().getProfileById(profileId)) {
            "Profile no longer exists"
        }
        val payloadJson = gson.toJson(payload)
        require(payloadJson.toByteArray(Charsets.UTF_8).size <= MAX_OPERATION_BYTES) {
            "User-state operation exceeds the sync limit"
        }
        append(profile.portableId, entityType, semanticIdentity, operation, payloadJson)
    }

    private suspend fun append(
        portableProfileId: String,
        entityType: String,
        semanticIdentity: String,
        operation: String,
        payloadJson: String
    ) {
        // Logged-out changes remain local and are included by the compatibility snapshot path.
        // Never let them leak into the first account that signs in on this device.
        val accountUserId = auth.userId ?: return
        val dao = database.syncOutboxDao()
        var clientState = dao.getClientState()
        if (clientState == null) {
            val initial = SyncClientStateEntity()
            dao.insertClientStateIfMissing(initial)
            clientState = dao.getClientState() ?: initial
        }
        val aggregate = "$accountUserId\u0000$portableProfileId\u0000$entityType\u0000$semanticIdentity"
        val key = sha256(aggregate)
        val revision = (dao.getRevision(key)?.revision ?: 0L) + 1L
        val sequence = clientState.nextSequence
        // Keep only the newest not-yet-sent state for an entity. The revision remains monotonic,
        // while frequent playback checkpoints do not grow a second unbounded history log.
        dao.coalesceEntity(key)
        dao.putRevision(SyncEntityRevisionEntity(key, revision))
        dao.setNextSequence(sequence + 1L)
        val operationId = UUID.randomUUID().toString()
        val encryptedPayload = auth.withSyncKey { syncKey ->
            check(auth.userId == accountUserId) { "Account changed during the user-state write." }
            SyncV3Protocol.encryptLocalPayload(operationId, payloadJson, accountUserId, syncKey)
        }
        check(auth.userId == accountUserId) { "Account changed during the user-state write." }
        dao.append(
            SyncOutboxEntity(
                operationId = operationId,
                accountUserId = accountUserId,
                portableProfileId = portableProfileId,
                entityKey = key,
                entityType = entityType,
                operation = operation,
                entityRevision = revision,
                deviceId = clientState.deviceId,
                sequence = sequence,
                createdAtMillis = System.currentTimeMillis(),
                payloadJson = encryptedPayload
            )
        )
    }

    private suspend fun WatchHistoryEntity.withCanonicalIdentity(): WatchHistoryEntity =
        copy(canonicalIdentityKey = canonicalIdentityStore.canonicalPlayableKey(id, type))

    private suspend fun SeriesNextUpEntity.withCanonicalIdentity(): SeriesNextUpEntity =
        copy(canonicalIdentityKey = canonicalIdentityStore.canonicalTitleKey(seriesId, "series"))

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_OPERATION_BYTES = 256 * 1024
        const val MAX_BATCH = 500
        val ENTITY_TYPES = setOf("profile_state", "profile_runtime", "library", "watched_state", "series_next_up", "playback_progress", "source_selection")
        val TORRENT_IDENTITY = Regex("(?:[a-f0-9]{40}|[a-z2-7]{32})", RegexOption.IGNORE_CASE)
        fun isSupportedOperation(entityType: String, operation: String): Boolean = when (entityType) {
            "profile_state" -> operation == "UPSERT" || operation == "DELETE"
            "profile_runtime" -> operation == "UPSERT"
            "library" -> operation == "UPSERT" || operation == "DELETE"
            "watched_state" -> operation == "UPSERT"
            "series_next_up" -> operation == "UPSERT" || operation == "DELETE"
            "playback_progress" -> operation == "UPSERT" || operation == "DELETE"
            "source_selection" -> operation == "UPSERT" || operation == "DELETE"
            else -> false
        }
    }
}

data class WatchedStatePayload(
    val titleId: String,
    val type: String,
    val watched: Boolean,
    val history: List<WatchHistoryEntity>,
    val seriesNextUp: SeriesNextUpEntity?,
    val seriesId: String? = null,
    val clearSeriesNextUp: Boolean = false,
    val clearHistoryIds: List<String> = emptyList(),
    val clearTitleHistory: Boolean = false,
    val canonicalTitleIdentityKey: String? = null,
    val clearHistoryCanonicalKeys: List<String> = emptyList()
)

data class ProfileStatePayload(val profile: ProfileEntity)
data class ProfileDeletionPayload(val portableProfileId: String)
data class ProfileRuntimeStatePayload(val snapshot: ProfileRuntimeSnapshot)
data class SeriesNextUpStatePayload(
    val seriesId: String,
    val nextUp: SeriesNextUpEntity?,
    val canonicalIdentityKey: String? = nextUp?.canonicalIdentityKey
)

data class ProgressTombstone(
    val playableId: String,
    val type: String,
    val canonicalIdentityKey: String? = null
)
/** Portable source choice. URLs, credentials, file indexes, names, and local track IDs are excluded. */
data class SourceSelectionStatePayload(
    val titleId: String,
    val type: String,
    val torrentIdentity: String,
    val schemaVersion: Int = 1
)
data class LibraryStatePayload(
    val id: String,
    val type: String,
    val item: WatchlistEntity?,
    val sourceAddonBaseUrl: String? = item?.sourceAddonBaseUrl,
    val sourceAddonKey: String? = item?.sourceAddonKey,
    val canonicalIdentityKey: String? = item?.canonicalIdentityKey
)

private fun normalizedAddonKey(value: String): String =
    if (value.matches(Regex("[a-f0-9]{24}"))) value else addonInstallationKey(value)

private fun String.isOpaqueProviderId(): Boolean =
    !startsWith("tmdb:", ignoreCase = true) && !matches(Regex("tt[0-9]+"))
