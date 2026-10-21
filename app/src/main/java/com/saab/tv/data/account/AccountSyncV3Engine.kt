package com.saab.tv.data.account

import androidx.room.withTransaction
import com.google.gson.Gson
import com.saab.tv.data.local.SaabTvDatabase
import com.saab.tv.data.model.SyncClientStateEntity
import com.saab.tv.data.model.WatchHistoryEntity
import com.saab.tv.data.player.SourceSelectionStore
import com.saab.tv.data.profile.ProfileConfigurationManager
import com.saab.tv.domain.schema.CanonicalEntityKey
import java.io.IOException

/** Incremental account-state transport. Room remains the source of truth and the journal is durable. */
internal class AccountSyncV3Engine(
    private val auth: AccountAuthManager,
    private val database: SaabTvDatabase,
    private val remote: SyncV3Remote,
    private val profiles: ProfileConfigurationManager? = null,
    private val sourceSelections: SourceSelectionStore? = null
) {
    private val gson = Gson()

    /** Returns false when the server is still on v2; the encrypted snapshot path remains active. */
    suspend fun synchronize(): Boolean {
        val userId = auth.userId ?: return false
        val outbox = database.syncOutboxDao()
        database.withTransaction {
            if (outbox.getClientState() == null) outbox.insertClientStateIfMissing(SyncClientStateEntity())
        }

        var pushBatches = 0
        while (pushBatches < MAX_PUSH_BATCHES) {
            val pending = outbox.pending(userId, SyncV3Protocol.MAX_PUSH_BATCH)
            // Pull already negotiates protocol support. An empty push consumes a
            // server write quota without publishing any state.
            if (pending.isEmpty()) break
            val protected = protectLegacyPayloads(userId, pending) ?: return false
            val pushed = remote.push(protected) ?: return false
            if (!pushed.acceptedOperationIds.containsAll(protected.map { it.operationId })) {
                throw IOException("Cloud sync did not acknowledge the complete operation batch.")
            }
            outbox.acknowledge(protected.map { it.operationId })
            pushBatches++
        }
        if (auth.userId != userId) return false

        // A winning v2 snapshot can contain state newer than the last v3 checkpoint. Rebase
        // before applying any retained v3 rows, or old incremental state could undo the restore.
        val initialState = requireNotNull(outbox.getClientState())
        if (initialState.serverCursor == SyncV3Protocol.REBASE_CURSOR_REQUIRED) {
            val head = remote.pull(SyncV3Protocol.REBASE_CURSOR_REQUIRED, 1) ?: return false
            require(head.operations.isEmpty() && head.cursor < SyncV3Protocol.REBASE_CURSOR_REQUIRED) {
                "Cloud returned an invalid sync checkpoint."
            }
            if (auth.userId != userId) return false
            outbox.setServerCursor(head.cursor)
        }

        repeat(MAX_PULL_PAGES) {
            val state = requireNotNull(outbox.getClientState())
            // A missing/unsupported pull RPC is not a completed sync: returning true here
            // suppresses the caller's encrypted v2 compatibility upload after a v3 push.
            // Keep the checkpoint durable and let the caller fall back/retry instead.
            val page = remote.pull(state.serverCursor, SyncV3Protocol.MAX_BATCH) ?: return false
            if (page.operations.isEmpty()) {
                if (page.cursor > state.serverCursor) outbox.setServerCursor(page.cursor)
                return true
            }
            if (auth.userId != userId) return false
            val decoded = auth.withSyncKey { syncKey ->
                page.operations.map { operation ->
                    operation to SyncV3Protocol.decryptPayload(operation, userId, syncKey)
                }
            }
            if (auth.userId != userId) return false
            var blockedByLocalWrite = false
            var blockedByUnsupportedOperation = false
            val applyPage: suspend () -> Unit = { database.withTransaction {
                for ((operation, payload) in decoded) {
                    if (auth.userId != userId) throw IOException("Account changed during sync; retry after sign-in.")
                    val current = requireNotNull(outbox.getClientState())
                    if (operation.serverSequence <= current.serverCursor) continue
                    // Don't move the global cursor past an entity with a local write created
                    // while push/pull was in flight. The next pass pushes that command first,
                    // then re-reads this same server row and applies whichever value won.
                    // Advancing here would make a losing concurrent command permanently hide
                    // the server's winner from this device.
                    if (outbox.hasPendingEntity(userId, operation.entityKey)) {
                        blockedByLocalWrite = true
                        break
                    }
                    if (!applyRemote(operation, payload)) {
                        blockedByUnsupportedOperation = true
                        break
                    }
                    outbox.observeRevision(operation.entityKey, operation.entityRevision)
                    outbox.setServerCursor(operation.serverSequence)
                }
            } }
            // Match local configuration writes' lock order. Taking the Room
            // transaction first can deadlock against a concurrent profile save.
            if (profiles != null) profiles.withRuntimeStateLock(applyPage) else applyPage()
            if (blockedByLocalWrite || blockedByUnsupportedOperation) return true
            if (auth.userId != userId) return false
            if (!page.hasMore) {
                val cursor = outbox.getClientState()!!.serverCursor
                if (page.cursor > cursor) outbox.setServerCursor(page.cursor)
                return true
            }
        }
        // The next scheduled pass continues from the durable cursor; don't monopolize the worker.
        return true
    }

    suspend fun hasPendingOperations(): Boolean = auth.userId?.let {
        database.syncOutboxDao().pendingCount(it) > 0
    } ?: false

    /** Upgrade old plaintext journal rows before they remain queued for an offline/retrying push. */
    private suspend fun protectLegacyPayloads(
        userId: String,
        operations: List<com.saab.tv.data.model.SyncOutboxEntity>
    ): List<com.saab.tv.data.model.SyncOutboxEntity>? {
        if (operations.all(SyncV3Protocol::isLocallyEncrypted)) return operations
        val syncKey = auth.syncKey
        return try {
            if (auth.userId != userId) return null
            operations.map { operation ->
                if (SyncV3Protocol.isLocallyEncrypted(operation)) return@map operation
                val encrypted = SyncV3Protocol.encryptLocalPayload(
                    operation.operationId, operation.payloadJson, userId, syncKey
                )
                database.syncOutboxDao().replacePayload(operation.operationId, operation.payloadJson, encrypted)
                operation.copy(payloadJson = encrypted)
            }.takeIf { auth.userId == userId }
        } finally {
            syncKey.fill(0)
        }
    }

    private suspend fun applyRemote(operation: SyncV3RemoteOperation, payloadJson: String): Boolean {
        val dao = database.addonDao()
        val profile = dao.getProfileByPortableId(operation.portableProfileId)
        when (operation.entityType) {
            "profile_state" -> {
                if (operation.operation == "DELETE") {
                    val payload = gson.fromJson(payloadJson, ProfileDeletionPayload::class.java)
                    require(payload.portableProfileId == operation.portableProfileId)
                    if (profile != null) {
                        dao.deleteWatchlistForProfile(profile.id)
                        dao.deleteHistoryForProfile(profile.id)
                        dao.deleteSeriesNextUpForProfile(profile.id)
                        dao.deleteRuntimeState(profile.portableId)
                        profiles?.deleteProfileState(profile.id)
                        dao.deleteProfile(profile.id)
                    }
                } else if (operation.operation == "UPSERT") {
                    val payload = gson.fromJson(payloadJson, ProfileStatePayload::class.java)
                    val incoming = requireNotNull(payload.profile)
                    require(incoming.portableId == operation.portableProfileId)
                    require(incoming.name.isNotBlank() && incoming.name.length <= 80)
                    dao.insertProfile(incoming.copy(id = profile?.id ?: 0, isActive = profile?.isActive ?: false))
                } else return false
            }
            "profile_runtime" -> {
                if (profile == null) return true
                if (operation.operation != "UPSERT") return false
                val payload = gson.fromJson(payloadJson, ProfileRuntimeStatePayload::class.java)
                val snapshot = requireNotNull(payload.snapshot)
                require(snapshot.addons.size <= MAX_RUNTIME_ROWS && snapshot.catalogConfigs.size <= MAX_RUNTIME_ROWS)
                require(snapshot.hubRows.size <= MAX_RUNTIME_ROWS && snapshot.hubRowItems.size <= MAX_RUNTIME_ROWS)
                if (profiles != null) profiles.applyRuntimeSnapshotLocked(profile.id, snapshot.ownedBy(profile.portableId))
                else {
                    dao.replaceRuntimeState(profile.portableId, snapshot.addons, snapshot.catalogConfigs,
                        snapshot.hubRows, snapshot.hubRowItems)
                }
            }
            "library" -> {
                if (profile == null) return true
                val payload = gson.fromJson(payloadJson, LibraryStatePayload::class.java)
                require(payload.id.isNotBlank())
                if (operation.operation == "DELETE") {
                    val canonicalKey = payload.canonicalIdentityKey
                    if (!canonicalKey.isNullOrBlank()) dao.removeWatchlistItemsByCanonicalIdentity(profile.id, canonicalKey)
                    else if (payload.sourceAddonKey.isNullOrBlank()) dao.removeFromWatchlist(profile.id, payload.id)
                    else dao.removeWatchlistItemFromAddon(profile.id, payload.id, payload.sourceAddonKey)
                } else if (operation.operation == "UPSERT") {
                    val item = requireNotNull(payload.item) { "Library upsert has no item" }
                    dao.addToWatchlist(item.copy(profileId = profile.id,
                        sourceAddonKey = payload.sourceAddonKey ?: item.sourceAddonKey,
                        canonicalIdentityKey = payload.canonicalIdentityKey?.takeIf(String::isNotBlank)
                            ?: item.canonicalIdentityKey.takeIf(String::isNotBlank)
                            ?: CanonicalEntityKey.title(item.id, item.type, payload.sourceAddonKey ?: item.sourceAddonKey)))
                } else return false
            }
            "watched_state" -> {
                if (profile == null) return true
                if (operation.operation != "UPSERT") return false
                val payload = gson.fromJson(payloadJson, WatchedStatePayload::class.java)
                require(payload.titleId.isNotBlank())
                if (payload.clearTitleHistory) dao.deleteHistoryItemForProfile(profile.id, payload.titleId)
                payload.clearHistoryIds.forEach { id ->
                    require(id.isNotBlank())
                    dao.deleteHistoryItemForProfile(profile.id, id)
                }
                payload.clearHistoryCanonicalKeys.filter(String::isNotBlank).distinct().forEach { key ->
                    dao.deleteHistoryByCanonicalIdentity(profile.id, key)
                }
                if (payload.history.isNotEmpty()) {
                    require(payload.history.all { it.id.isNotBlank() })
                    dao.insertHistoryItems(payload.history.map {
                        it.copy(profileId = profile.id, canonicalIdentityKey = it.canonicalIdentityKey.takeIf(String::isNotBlank)
                            ?: CanonicalEntityKey.playable(it.id, it.type))
                    })
                }
                payload.seriesNextUp?.let {
                    dao.insertSeriesNextUp(it.copy(
                        profileId = profile.id,
                        canonicalIdentityKey = it.canonicalIdentityKey.takeIf(String::isNotBlank)
                            ?: CanonicalEntityKey.title(it.seriesId, "series")
                    ))
                    if (it.isComplete && payload.seriesId != null) {
                        val titleKey = payload.canonicalTitleIdentityKey ?: it.canonicalIdentityKey
                        if (titleKey.isNullOrBlank()) dao.removeFromWatchlist(profile.id, payload.seriesId)
                        else dao.removeWatchlistItemsByCanonicalIdentity(profile.id, titleKey)
                    }
                }
                if (payload.clearSeriesNextUp && !payload.seriesId.isNullOrBlank()) {
                    dao.deleteSeriesNextUpForProfile(profile.id, payload.seriesId)
                    payload.canonicalTitleIdentityKey?.let { dao.deleteSeriesNextUpByCanonicalIdentity(profile.id, it) }
                }
                if (payload.watched) {
                    val titleKey = payload.canonicalTitleIdentityKey
                    if (!titleKey.isNullOrBlank()) dao.removeWatchlistItemsByCanonicalIdentity(profile.id, titleKey)
                    else payload.history.map { it.id }.toSet().plus(payload.titleId).forEach {
                        dao.removeFromWatchlist(profile.id, it)
                    }
                }
            }
            "series_next_up" -> {
                if (profile == null) return true
                val payload = gson.fromJson(payloadJson, SeriesNextUpStatePayload::class.java)
                require(payload.seriesId.isNotBlank())
                if (operation.operation == "DELETE") {
                    dao.deleteSeriesNextUpForProfile(profile.id, payload.seriesId)
                    payload.canonicalIdentityKey?.let { dao.deleteSeriesNextUpByCanonicalIdentity(profile.id, it) }
                } else if (operation.operation == "UPSERT") {
                    val nextUp = requireNotNull(payload.nextUp)
                    require(nextUp.seriesId == payload.seriesId)
                    dao.insertSeriesNextUp(nextUp.copy(
                        profileId = profile.id,
                        canonicalIdentityKey = payload.canonicalIdentityKey?.takeIf(String::isNotBlank)
                            ?: nextUp.canonicalIdentityKey.takeIf(String::isNotBlank)
                            ?: CanonicalEntityKey.title(nextUp.seriesId, "series")
                    ))
                    if (nextUp.isComplete) dao.removeFromWatchlist(profile.id, payload.seriesId)
                } else return false
            }
            "playback_progress" -> {
                if (profile == null) return true
                if (operation.operation == "DELETE") {
                    val tombstone = gson.fromJson(payloadJson, ProgressTombstone::class.java)
                    require(tombstone.playableId.isNotBlank())
                    dao.deleteHistoryItemForProfile(profile.id, tombstone.playableId)
                    tombstone.canonicalIdentityKey?.let { dao.deleteHistoryByCanonicalIdentity(profile.id, it) }
                } else if (operation.operation == "UPSERT") {
                    val entry = gson.fromJson(payloadJson, WatchHistoryEntity::class.java)
                    require(entry.id.isNotBlank() && entry.position >= 0 && entry.duration >= 0)
                    dao.insertHistory(entry.copy(
                        profileId = profile.id,
                        canonicalIdentityKey = entry.canonicalIdentityKey.takeIf(String::isNotBlank)
                            ?: CanonicalEntityKey.playable(entry.id, entry.type)
                    ))
                    if (entry.watched && entry.type != "series") dao.removeFromWatchlist(profile.id, entry.id)
                } else return false
            }
            "source_selection" -> {
                if (profile == null) return true
                val store = sourceSelections ?: return false
                if (operation.operation == "DELETE") {
                    val payload = gson.fromJson(payloadJson, SourceSelectionStatePayload::class.java)
                    require(payload.titleId.isNotBlank() && payload.type in setOf("movie", "series"))
                    store.clearSyncedSelection(profile.portableId, profile.id, payload.titleId, payload.type)
                } else if (operation.operation == "UPSERT") {
                    val payload = gson.fromJson(payloadJson, SourceSelectionStatePayload::class.java)
                    require(payload.schemaVersion == 1)
                    store.applySyncedSelection(profile.portableId, profile.id, payload)
                } else return false
            }
            else -> return false // Do not advance the durable cursor past state this client cannot apply.
        }
        return true
    }

    private companion object {
        const val MAX_PULL_PAGES = 40
        // Four requests × the 4 MiB per-request cap limit this TV's burst to 16 MiB,
        // leaving some headroom under the prepared 25 MiB/account/minute quota. The
        // server remains the final guard because other TVs share the account budget.
        const val MAX_PUSH_BATCHES = 4
        const val MAX_RUNTIME_ROWS = 2_000
    }
}
