package com.saab.tv.data.account

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.AtomicFile
import android.os.SystemClock
import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.saab.tv.data.local.SaabTvDatabase
import com.saab.tv.data.profile.ProfileConfigurationManager
import com.saab.tv.data.player.SourceSelectionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Safe whole-account sync: CAS prevents silent overwrites between TVs. */
private class AccountSyncConflict(message: String) : IOException(message)

class AccountCloudStore(private val context: Context, private val auth: AccountAuthManager, private val db: SaabTvDatabase) {
    companion object {
        private const val COMPATIBILITY_SNAPSHOT_DIRTY = "compatibility_snapshot_dirty"

        fun markCompatibilitySnapshotDirty(context: Context) {
            AccountStorage.preferences(context, "account_sync_metadata").edit()
                .putBoolean(COMPATIBILITY_SNAPSHOT_DIRTY, true).apply()
        }
    }

    private val metadata = AccountStorage.preferences(context, "account_sync_metadata")
    private val snapshots = AccountSnapshotStore(context, db)
    private val components = AccountComponentCloud(context, auth)
    private val restoreJournal = AtomicFile(File(AccountStorage.files(context), "pending_cloud_restore.json"))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private suspend fun remote(): JsonObject? = components.fullRemote()

    private fun payload(cloud: JsonObject): JsonObject = auth.withSyncKey { key ->
        val plain = AccountSnapshotCrypto.decrypt(
            Base64.decode(cloud.get("ciphertext").asString, Base64.NO_WRAP), key, requireNotNull(auth.userId)
        )
        try { JsonParser.parseString(plain.toString(Charsets.UTF_8)).asJsonObject }
        finally { plain.fill(0) }
    }

    private fun contentBytes(snapshot: JsonObject): ByteArray = snapshot.deepCopy().apply {
        remove("changedAt")
    }.toString().toByteArray(Charsets.UTF_8)

    private fun cloudChangedAt(cloud: JsonObject, snapshot: JsonObject): Long =
        snapshot.get("changedAt")?.asLong?.takeIf { it > 0 } ?: runCatching {
            java.time.Instant.parse(cloud.get("updated_at").asString).toEpochMilli()
        }.getOrDefault(0)

    fun noteLocalChange() {
        // No baseline means an uninitialized device, not an independently newer copy.
        if (!metadata.contains("hash")) return
        check(metadata.edit().putLong("local_changed_at", System.currentTimeMillis()).commit())
    }

    fun needsCompatibilitySnapshotUpload(): Boolean = metadata.getBoolean(COMPATIBILITY_SNAPSHOT_DIRTY, false) ||
        metadata.getString("hash", null).isNullOrBlank()

    suspend fun initialize(): Boolean {
        if (restoreJournal.baseFile.exists()) {
            val pending = JsonParser.parseString(restoreJournal.openRead().use { it.readBytes().toString(Charsets.UTF_8) }).asJsonObject
            restoreCloud(pending)
            return true
        }
        val localRevision = metadata.getLong("revision", 0)
        val head = components.head()
        val revision = head?.get("revision")?.asLong ?: 0
        val cloud = if (head != null && revision != localRevision) remote() else head
        if (cloud != null && revision != localRevision) {
            val hasLocalProfiles = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM profiles").use { it.moveToFirst(); it.getInt(0) > 0 }
            val current = snapshots.capture()
            // Recover a successful server write whose response was lost before
            // local metadata committed (network loss/process termination).
            val remoteSnapshot = payload(cloud)
            val remoteBytes = contentBytes(remoteSnapshot)
            if (hash(current) == hash(remoteBytes)) {
                saveMetadata(revision, hash(current))
                return false
            }
            val dirty = hasLocalProfiles && metadata.getString("hash", null) != hash(current)
            if (dirty && localSnapshotIsNewer(metadata.getLong("local_changed_at", 0), cloudChangedAt(cloud, remoteSnapshot))) {
                try { upload() } catch (_: AccountSyncConflict) {
                    restoreCloud(remote() ?: throw IOException("Cloud backup is unavailable. Please retry."))
                    return true
                }
                return false
            }
            restoreCloud(cloud)
            return true
        } else if (cloud == null && localRevision > 0) {
            throw IOException("Cloud account data is missing. Local data has been preserved.")
        }
        return false
    }

    suspend fun upload(): Boolean {
        val bytes = snapshots.capture()
        val digest = hash(bytes)
        if (metadata.getString("hash", null) == digest) {
            metadata.edit().remove(COMPATIBILITY_SNAPSHOT_DIRTY).apply()
            return false
        }
        val changedAt = metadata.getLong("local_changed_at", 0).takeIf { it > 0 } ?: System.currentTimeMillis()
        val snapshot = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject.apply {
            addProperty("changedAt", changedAt)
        }.toString().toByteArray(Charsets.UTF_8)
        val result = components.save(snapshot, metadata.getLong("revision", 0))
        if (result.get("conflict").asBoolean) {
            val latest = remote() ?: throw IOException("Cloud backup changed during sync. Please retry.")
            val latestSnapshot = payload(latest)
            if (hash(bytes) == hash(contentBytes(latestSnapshot))) {
                saveMetadata(latest.get("revision").asLong, digest)
                return false
            }
            if (!localSnapshotIsNewer(changedAt, cloudChangedAt(latest, latestSnapshot)))
                throw AccountSyncConflict("A newer cloud copy is available. Reopen Saab TV to load it automatically.")
            val retry = components.save(snapshot, latest.get("revision").asLong)
            if (retry.get("conflict").asBoolean) throw IOException("Cloud backup changed again. Please retry.")
            saveMetadata(retry.get("revision").asLong, digest)
            return true
        }
        saveMetadata(result.get("revision").asLong, digest)
        metadata.edit().remove(COMPATIBILITY_SNAPSHOT_DIRTY).apply()
        return true
    }

    suspend fun useCloudCopy() {
        val cloud = remote() ?: throw IOException("No cloud backup is available.")
        restoreCloud(cloud)
    }

    internal suspend fun playbackHistory(): RemotePlaybackHistory {
        val userId = requireNotNull(auth.userId)
        val (revision, rows) = components.history()
        require(auth.userId == userId)
        val gson = com.google.gson.Gson()
        val items = rows.map { gson.fromJson(it, com.saab.tv.data.model.WatchHistoryEntity::class.java) }
            .filter { !it.id.isNullOrBlank() && it.position >= 0 && it.duration >= 0 }
        // Do not resurrect locally cleared rows from the already-applied revision.
        return RemotePlaybackHistory(items, canFillMissingProgress(revision), revision)
    }

    internal fun canFillMissingProgress(revision: Long): Boolean = revision >
        maxOf(metadata.getLong("revision", 0), metadata.getLong("progress_revision", 0))

    internal fun markProgressApplied(revision: Long) {
        check(metadata.edit().putLong("progress_revision", maxOf(revision, metadata.getLong("progress_revision", 0))).commit())
    }

    /** Read-only probe; the launcher performs any replacement before opening repositories. */
    suspend fun hasNewerCleanBackup(): Boolean {
        val head = components.head() ?: return false
        if (head.get("revision").asLong <= metadata.getLong("revision", 0)) return false
        val cloud = remote() ?: return false
        return metadata.getString("hash", null) == hash(snapshots.capture()) ||
            !localSnapshotIsNewer(metadata.getLong("local_changed_at", 0), cloudChangedAt(cloud, payload(cloud)))
    }

    private suspend fun restoreCloud(cloud: JsonObject) {
        // Crash recovery: keep the authenticated encrypted source until every
        // database/file/preference restore has completed. Never journal plaintext.
        val bytes = auth.withSyncKey { key ->
            AccountSnapshotCrypto.decrypt(Base64.decode(cloud.get("ciphertext").asString, Base64.NO_WRAP), key, requireNotNull(auth.userId))
        }
        try {
            val stream = restoreJournal.startWrite()
            try { stream.write(cloud.toString().toByteArray()); restoreJournal.finishWrite(stream) }
            catch (e: Exception) { restoreJournal.failWrite(stream); throw e }
            snapshots.restore(bytes)
            val restoredForUser = requireNotNull(auth.userId)
            db.withTransaction {
                val journal = db.syncOutboxDao()
                journal.clearPendingForAccount(restoredForUser)
                if (journal.getClientState() == null) journal.insertClientStateIfMissing(
                    com.saab.tv.data.model.SyncClientStateEntity()
                )
                journal.setServerCursor(SyncV3Protocol.REBASE_CURSOR_REQUIRED)
            }
            check(metadata.edit().putLong("local_changed_at", cloudChangedAt(cloud,
                JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject)).commit())
            saveMetadata(cloud.get("revision").asLong, hash(snapshots.capture()))
            metadata.edit().remove(COMPATIBILITY_SNAPSHOT_DIRTY).apply()
            restoreJournal.delete()
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun keepLocalCopy() {
        val revision = components.head()?.get("revision")?.asLong ?: 0
        check(metadata.edit().putLong("revision", revision).remove("hash").commit())
        upload()
    }

    private fun saveMetadata(revision: Long, hash: String) {
        check(metadata.edit().putLong("revision", revision).putString("hash", hash)
            .putLong("progress_revision", maxOf(revision, metadata.getLong("progress_revision", 0)))
            .putLong("synced_at", System.currentTimeMillis()).remove(COMPATIBILITY_SNAPSHOT_DIRTY).commit())
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AccountSyncEntryPoint {
    fun database(): SaabTvDatabase
    fun profileConfigurationManager(): ProfileConfigurationManager
}

@Singleton
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: AccountAuthManager,
    private val db: SaabTvDatabase,
    private val profiles: ProfileConfigurationManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val mutex = Mutex()
    private val cloud = AccountCloudStore(context, auth, db)
    private val sourceSelections = SourceSelectionStore(context, profiles)
    private val syncV3 = AccountSyncV3Engine(auth, db, AccountComponentCloud(context, auth), profiles, sourceSelections)
    private val playbackHistoryCache = com.saab.tv.data.cache.PreviewWarmupCache<RemotePlaybackHistory>(
        scope, SystemClock::elapsedRealtime, ttlMs = 10_000L, capacity = 1
    )

    fun preparePlaybackResume() {
        val userId = auth.userId ?: return
        if (!auth.hasSession || suspended) return
        playbackHistoryCache.prefetch(userId) { withTimeout(5_000L) { cloud.playbackHistory() } }
    }

    suspend fun freshestPlaybackHistory(
        local: List<com.saab.tv.data.model.WatchHistoryEntity>, profileId: Int
    ): List<com.saab.tv.data.model.WatchHistoryEntity> {
        val userId = auth.userId ?: return local
        if (!auth.hasSession || suspended) return local
        preparePlaybackResume()
        val remote = withTimeoutOrNull(1_500L) { playbackHistoryCache.awaitIfPresent(userId) } ?: return local
        if (auth.userId != userId) return local
        if (!mutex.tryLock()) return newestPlaybackHistory(local, remote, profileId)
        return try {
            if (auth.userId != userId || suspended) return local
            val effective = remote.copy(canFillMissing = cloud.canFillMissingProgress(remote.revision))
            // Import progress, not device/profile preferences or the whole account snapshot.
            // Preserve remote timestamps so this is not mistaken for a new local watch event.
            importedHistoryInvalidation.set(true)
            db.addonDao().mergeRemotePlaybackHistory(effective.items, effective.canFillMissing)
            cloud.markProgressApplied(remote.revision)
            val current = local.mapNotNull { db.addonDao().getHistoryItemForProfile(profileId, it.id) }
            newestPlaybackHistory(current, effective, profileId)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) {
            com.saab.tv.AppDiagnostics.failure("Cloud Sync", "Progress Import Failed", failure)
            newestPlaybackHistory(local, remote, profileId)
        } finally { mutex.unlock() }
    }
    private val _status = MutableStateFlow("Not synced yet")
    val status: StateFlow<String> = _status
    private var started = false
    private val cadence = AccountSyncCadence(SystemClock.elapsedRealtime())
    private val wakeups = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    @Volatile private var failures = 0
    private val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) { requestSync(urgent = true) }
    }
    @Volatile private var suspended = false
    private val importedHistoryInvalidation = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var conflictPending = false
    private val preferenceStores = AccountSnapshotStore(context, db).preferenceStores()
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        com.saab.tv.AppDiagnostics.detailed("Cloud Sync", "Preferences Changed")
        AccountCloudStore.markCompatibilitySnapshotDirty(context)
        cloud.noteLocalChange()
        requestSync()
    }
    private val databaseObserver = object : InvalidationTracker.Observer(AccountSnapshotStore.TABLES.toTypedArray()) {
        override fun onInvalidated(tables: Set<String>) {
            // Player saves notify historyChanged explicitly, even if Room coalesces
            // an import with a concurrent local progress write into this callback.
            if (tables.all { it == "watch_history" } && importedHistoryInvalidation.getAndSet(false)) return
            com.saab.tv.AppDiagnostics.detailed("Cloud Sync", "Database Changed", "tables=${tables.sorted().joinToString(",")}")
            if ("themes" in tables) AccountCloudStore.markCompatibilitySnapshotDirty(context)
            cloud.noteLocalChange()
            requestSync(progressOnly = tables.all { it == "watch_history" || it == "series_next_up" })
        }
    }

    fun start() {
        if (started || !auth.hasSession) return
        started = true
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
        db.invalidationTracker.addObserver(databaseObserver)
        preferenceStores.forEach { it.registerOnSharedPreferenceChangeListener(preferenceListener) }
        scope.launch {
            for (signal in wakeups) {
                while (isActive) {
                    if (suspended || !auth.hasSession) break
                    val wait = cadence.waitMs(SystemClock.elapsedRealtime()) ?: break
                    val changed = withTimeoutOrNull(wait.coerceAtLeast(1)) { wakeups.receive(); true } ?: false
                    if (!changed) syncNow(automatic = true)
                }
            }
        }
        requestSync()
    }

    fun requestSync(progressOnly: Boolean = false, urgent: Boolean = false) {
        if (suspended || conflictPending || !auth.hasSession) return
        if (urgent) failures = 0
        cadence.markDirty(SystemClock.elapsedRealtime(), progressOnly, urgent)
        wakeups.trySend(Unit)
    }

    fun historyChanged(progressOnly: Boolean = true, urgent: Boolean = false) {
        cloud.noteLocalChange()
        requestSync(progressOnly = progressOnly, urgent = urgent)
    }

    /** Process-lifetime scope survives Activity destruction long enough to flush local saves. */
    fun flushAfterBackground() { requestSync(urgent = true) }

    suspend fun newerCloudBackupAvailable(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!auth.hasSession || suspended) return@withLock false
            profiles.saveActiveRuntimeState()
            cloud.hasNewerCleanBackup()
        }
    }

    /** Reconcile both directions; true means the local account database was replaced by cloud state. */
    suspend fun syncAndReconcile(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!auth.hasSession) return@withLock false
            if (cadence.isRateLimited(SystemClock.elapsedRealtime())) return@withLock false
            suspended = true
            try {
                _status.value = "Checking cloud copy…"
                com.saab.tv.AppDiagnostics.event(context, "Cloud Sync", "Manual Reconcile Started")
                profiles.saveActiveRuntimeState()
                if (cloud.initialize()) {
                    conflictPending = false
                    failures = 0
                    _status.value = "Cloud copy restored; reopening…"
                    com.saab.tv.AppDiagnostics.event(context, "Cloud Sync", "Cloud Copy Restored")
                    return@withLock true
                }
                val incrementalSyncAvailable = syncV3.synchronize()
                if (!incrementalSyncAvailable || cloud.needsCompatibilitySnapshotUpload()) cloud.upload()
                // A bounded v3 pass can leave a migration backlog. Continue it on the normal
                // batched cadence so a large account does not immediately consume another
                // server quota window in a burst.
                if (incrementalSyncAvailable && syncV3.hasPendingOperations()) requestSync(progressOnly = true)
                conflictPending = false
                failures = 0
                _status.value = "Synced"
                com.saab.tv.AppDiagnostics.event(context, "Cloud Sync", "Manual Reconcile Completed")
                false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                com.saab.tv.AppDiagnostics.failure("Cloud Sync", "Manual Reconcile Failed", failure)
                if (failure is AccountApiException && failure.status == 429) {
                    cadence.rateLimited(SystemClock.elapsedRealtime(), RATE_LIMIT_RETRY_MS)
                }
                _status.value = failure.message?.take(200) ?: "Sync failed. Local data is safe."
                throw failure
            } finally {
                suspended = false
                wakeups.trySend(Unit)
            }
        }
    }

    suspend fun syncNow(automatic: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!auth.hasSession || suspended) return@withLock false
            if (conflictPending) return@withLock false
            // An automatic coalesced wakeup is a no-op; a manual caller must not
            // be told that sync succeeded while the quota window is still open.
            if (!cadence.beginAttempt(SystemClock.elapsedRealtime(), automatic)) return@withLock automatic
            try {
                _status.value = "Syncing…"
                com.saab.tv.AppDiagnostics.event(context, "Cloud Sync", "Started", "automatic=$automatic")
                profiles.saveActiveRuntimeState()
                // Resolve a changed compatibility snapshot before publishing offline v3 events;
                // otherwise a stale outbox row could overwrite the copy just restored from cloud.
                cloud.initialize()
                val incrementalSyncAvailable = syncV3.synchronize()
                if (!incrementalSyncAvailable || cloud.needsCompatibilitySnapshotUpload()) cloud.upload()
                if (incrementalSyncAvailable && syncV3.hasPendingOperations()) requestSync(progressOnly = true)
                failures = 0
                _status.value = "Synced"
                com.saab.tv.AppDiagnostics.event(context, "Cloud Sync", "Completed")
                true
            } catch (e: CancellationException) { cadence.markDirty(SystemClock.elapsedRealtime()); throw e }
            catch (e: Exception) {
                com.saab.tv.AppDiagnostics.failure("Cloud Sync", "Failed", e)
                failures++
                val now = SystemClock.elapsedRealtime()
                if (e is AccountSyncConflict) {
                    conflictPending = true
                    cadence.deferUntilEvent(now)
                } else if (e is AccountApiException && e.status == 429) {
                    // The server quota window is one minute; retrying on the generic
                    // 15/30-second schedule only adds traffic while the limit is open.
                    cadence.rateLimited(now, RATE_LIMIT_RETRY_MS)
                    wakeups.trySend(Unit)
                } else if (e is AccountApiException && e.status == 413) {
                    // A fixed-size payload cannot recover by retrying. Wait for a new
                    // local change or an explicit user retry instead of uploading it again.
                    cadence.deferUntilEvent(now)
                } else if (failures <= 3) {
                    cadence.retryAt(now, 15_000L * (1L shl (failures - 1)))
                    wakeups.trySend(Unit)
                } else cadence.deferUntilEvent(now)
                _status.value = e.message?.take(200) ?: "Sync failed. Local data is safe."
                false
            }
        }
    }

    suspend fun resolveConflict(useCloud: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            suspended = true
            try {
                if (useCloud) cloud.useCloudCopy() else { profiles.saveActiveRuntimeState(); cloud.keepLocalCopy() }
                conflictPending = false
                failures = 0
                _status.value = "Synced"
            } catch (e: Exception) {
                // A failed restore must not leave routine sync disabled forever.
                suspended = false
                requestSync()
                throw e
            } finally { if (!useCloud) suspended = false }
        }
    }

    fun stop() {
        suspended = true
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        db.invalidationTracker.removeObserver(databaseObserver)
        preferenceStores.forEach { it.unregisterOnSharedPreferenceChangeListener(preferenceListener) }
        scope.cancel()
    }

    companion object {
        private const val RATE_LIMIT_RETRY_MS = 60_000L

        suspend fun prepareBeforeOpeningApp(
            context: Context,
            auth: AccountAuthManager
        ) = withContext(Dispatchers.IO) {
            val dependencies = EntryPointAccessors.fromApplication(context, AccountSyncEntryPoint::class.java)
            // The profile manager and sync transaction must share the same Room
            // instance. Hilt owns its lifetime; the launcher must not close it.
            prepareAccountData(context, auth, dependencies.database(), dependencies.profileConfigurationManager())
        }

        internal suspend fun prepareAccountData(
            context: Context,
            auth: AccountAuthManager,
            database: SaabTvDatabase,
            profileManager: ProfileConfigurationManager
        ): Boolean {
            val cloud = AccountCloudStore(context, auth, database)
            val restored = cloud.initialize()
            val sourceSelections = SourceSelectionStore(context, profileManager)
            val incrementalSyncAvailable = AccountSyncV3Engine(
                auth, database, AccountComponentCloud(context, auth), profileManager, sourceSelections
            ).synchronize()
            if (!incrementalSyncAvailable || cloud.needsCompatibilitySnapshotUpload()) cloud.upload()
            return restored
        }
    }
}
