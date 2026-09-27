package com.saab.tv.data.account

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.AtomicFile
import android.os.SystemClock
import androidx.room.InvalidationTracker
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.saab.tv.data.local.SaabTvDatabase
import com.saab.tv.data.profile.ProfileConfigurationManager
import com.saab.tv.di.DatabaseModule
import dagger.hilt.android.qualifiers.ApplicationContext
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
    private val metadata = AccountStorage.preferences(context, "account_sync_metadata")
    private val snapshots = AccountSnapshotStore(context, db)
    private val restoreJournal = AtomicFile(File(AccountStorage.files(context), "pending_cloud_restore.json"))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private suspend fun remote(): JsonObject? = JsonParser.parseString(auth.dataRequest(
        "saabtv_account_state?select=revision,ciphertext,updated_at&limit=1")).asJsonArray.firstOrNull()?.asJsonObject

    private fun payload(cloud: JsonObject): JsonObject = JsonParser.parseString(
        AccountSnapshotCrypto.decrypt(Base64.decode(cloud.get("ciphertext").asString, Base64.NO_WRAP),
            auth.syncKey, requireNotNull(auth.userId)).toString(Charsets.UTF_8)).asJsonObject

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

    suspend fun initialize() {
        if (restoreJournal.baseFile.exists()) {
            val pending = JsonParser.parseString(restoreJournal.openRead().use { it.readBytes().toString(Charsets.UTF_8) }).asJsonObject
            restoreCloud(pending)
        }
        val cloud = remote()
        val revision = cloud?.get("revision")?.asLong ?: 0
        val localRevision = metadata.getLong("revision", 0)
        if (cloud != null && revision != localRevision) {
            val hasLocalProfiles = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM profiles").use { it.moveToFirst(); it.getInt(0) > 0 }
            val current = snapshots.capture()
            // Recover a successful server write whose response was lost before
            // local metadata committed (network loss/process termination).
            val remoteSnapshot = payload(cloud)
            val remoteBytes = contentBytes(remoteSnapshot)
            if (hash(current) == hash(remoteBytes)) {
                saveMetadata(revision, hash(current))
                return
            }
            val dirty = hasLocalProfiles && metadata.getString("hash", null) != hash(current)
            if (dirty && localSnapshotIsNewer(metadata.getLong("local_changed_at", 0), cloudChangedAt(cloud, remoteSnapshot))) {
                try { upload() } catch (_: AccountSyncConflict) {
                    restoreCloud(remote() ?: throw IOException("Cloud backup is unavailable. Please retry."))
                }
                return
            }
            restoreCloud(cloud)
        } else if (cloud == null && localRevision > 0) {
            throw IOException("Cloud account data is missing. Local data has been preserved.")
        } else if (cloud != null) {
            try { upload() } catch (_: AccountSyncConflict) {
                restoreCloud(remote() ?: throw IOException("Cloud backup is unavailable. Please retry."))
            }
        }
    }

    suspend fun upload(): Boolean {
        val bytes = snapshots.capture()
        val digest = hash(bytes)
        if (metadata.getString("hash", null) == digest) return false
        val changedAt = metadata.getLong("local_changed_at", 0).takeIf { it > 0 } ?: System.currentTimeMillis()
        val snapshot = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject.apply {
            addProperty("changedAt", changedAt)
        }.toString().toByteArray(Charsets.UTF_8)
        val body = JsonObject().apply {
            addProperty("expected_revision", metadata.getLong("revision", 0))
            addProperty("encrypted_state", Base64.encodeToString(AccountSnapshotCrypto.encrypt(snapshot, auth.syncKey,
                requireNotNull(auth.userId)), Base64.NO_WRAP))
        }
        val result = JsonParser.parseString(auth.dataRequest("rpc/saabtv_save_account", body)).asJsonObject
        if (result.get("conflict").asBoolean) {
            val latest = remote() ?: throw IOException("Cloud backup changed during sync. Please retry.")
            val latestSnapshot = payload(latest)
            if (hash(bytes) == hash(contentBytes(latestSnapshot))) {
                saveMetadata(latest.get("revision").asLong, digest)
                return false
            }
            if (!localSnapshotIsNewer(changedAt, cloudChangedAt(latest, latestSnapshot)))
                throw AccountSyncConflict("A newer cloud copy is available. Reopen Saab TV to load it automatically.")
            body.addProperty("expected_revision", latest.get("revision").asLong)
            val retry = JsonParser.parseString(auth.dataRequest("rpc/saabtv_save_account", body)).asJsonObject
            if (retry.get("conflict").asBoolean) throw IOException("Cloud backup changed again. Please retry.")
            saveMetadata(retry.get("revision").asLong, digest)
            return true
        }
        saveMetadata(result.get("revision").asLong, digest)
        return true
    }

    suspend fun useCloudCopy() {
        val cloud = remote() ?: throw IOException("No cloud backup is available.")
        restoreCloud(cloud)
    }

    /** Read-only probe; the launcher performs any replacement before opening repositories. */
    suspend fun hasNewerCleanBackup(): Boolean {
        val cloud = remote() ?: return false
        if (cloud.get("revision").asLong <= metadata.getLong("revision", 0)) return false
        return metadata.getString("hash", null) == hash(snapshots.capture()) ||
            !localSnapshotIsNewer(metadata.getLong("local_changed_at", 0), cloudChangedAt(cloud, payload(cloud)))
    }

    private fun restoreCloud(cloud: JsonObject) {
        // Crash recovery: keep the authenticated encrypted source until every
        // database/file/preference restore has completed. Never journal plaintext.
        val bytes = AccountSnapshotCrypto.decrypt(Base64.decode(cloud.get("ciphertext").asString, Base64.NO_WRAP), auth.syncKey, requireNotNull(auth.userId))
        val stream = restoreJournal.startWrite()
        try { stream.write(cloud.toString().toByteArray()); restoreJournal.finishWrite(stream) }
        catch (e: Exception) { restoreJournal.failWrite(stream); throw e }
        snapshots.restore(bytes)
        check(metadata.edit().putLong("local_changed_at", cloudChangedAt(cloud,
            JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject)).commit())
        saveMetadata(cloud.get("revision").asLong, hash(snapshots.capture()))
        restoreJournal.delete()
    }

    suspend fun keepLocalCopy() {
        val revision = remote()?.get("revision")?.asLong ?: 0
        check(metadata.edit().putLong("revision", revision).remove("hash").commit())
        upload()
    }

    private fun saveMetadata(revision: Long, hash: String) {
        check(metadata.edit().putLong("revision", revision).putString("hash", hash)
            .putLong("synced_at", System.currentTimeMillis()).commit())
    }
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
    @Volatile private var conflictPending = false
    private val preferenceStores = AccountSnapshotStore(context, db).preferenceStores()
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> cloud.noteLocalChange(); requestSync() }
    private val databaseObserver = object : InvalidationTracker.Observer(AccountSnapshotStore.TABLES.toTypedArray()) {
        override fun onInvalidated(tables: Set<String>) { cloud.noteLocalChange(); requestSync(progressOnly = tables.all { it == "watch_history" || it == "series_next_up" }) }
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

    suspend fun syncNow(automatic: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!auth.hasSession || suspended) return@withLock false
            if (conflictPending) return@withLock false
            if (!cadence.beginAttempt(SystemClock.elapsedRealtime(), automatic)) return@withLock true
            try {
                _status.value = "Syncing…"
                com.saab.tv.AppDiagnostics.event(context, "Cloud Sync", "Started", "automatic=$automatic")
                profiles.saveActiveRuntimeState()
                cloud.upload()
                failures = 0
                _status.value = "Synced"
                com.saab.tv.AppDiagnostics.event(context, "Cloud Sync", "Completed")
                true
            } catch (e: CancellationException) { cadence.markDirty(SystemClock.elapsedRealtime()); throw e }
            catch (e: Exception) {
                com.saab.tv.AppDiagnostics.failure("Cloud Sync", "Failed", e)
                failures++
                if (e is AccountSyncConflict) {
                    conflictPending = true
                    cadence.deferUntilEvent(SystemClock.elapsedRealtime())
                } else if (failures <= 3) {
                    cadence.retryAt(SystemClock.elapsedRealtime(), 15_000L * (1L shl (failures - 1)))
                    wakeups.trySend(Unit)
                } else cadence.deferUntilEvent(SystemClock.elapsedRealtime())
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
        suspend fun prepareBeforeOpeningApp(context: Context, auth: AccountAuthManager) = withContext(Dispatchers.IO) {
            val database = DatabaseModule.provideDatabase(context)
            try { AccountCloudStore(context, auth, database).initialize() }
            finally { database.close() }
        }
    }
}
