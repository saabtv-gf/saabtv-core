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
class AccountCloudStore(private val context: Context, private val auth: AccountAuthManager, private val db: SaabTvDatabase) {
    private val metadata = AccountStorage.preferences(context, "account_sync_metadata")
    private val snapshots = AccountSnapshotStore(context, db)
    private val restoreJournal = AtomicFile(File(AccountStorage.files(context), "pending_cloud_restore.json"))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private suspend fun remote(): JsonObject? = JsonParser.parseString(auth.dataRequest(
        "saabtv_account_state?select=revision,ciphertext&limit=1")).asJsonArray.firstOrNull()?.asJsonObject

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
            val remoteBytes = AccountSnapshotCrypto.decrypt(Base64.decode(cloud.get("ciphertext").asString, Base64.NO_WRAP),
                auth.syncKey, requireNotNull(auth.userId))
            if (hash(current) == hash(remoteBytes)) {
                saveMetadata(revision, hash(current))
                return
            }
            val dirty = hasLocalProfiles && metadata.getString("hash", null) != hash(current)
            if (dirty) throw IOException("This TV and another device both have changes. Your local data is safe. Use Account settings to choose which copy to keep.")
            restoreCloud(cloud)
        } else if (cloud == null && localRevision > 0) {
            throw IOException("Cloud account data is missing. Local data has been preserved.")
        }
    }

    suspend fun upload(): Boolean {
        val bytes = snapshots.capture()
        val digest = hash(bytes)
        if (metadata.getString("hash", null) == digest) return false
        val body = JsonObject().apply {
            addProperty("expected_revision", metadata.getLong("revision", 0))
            addProperty("encrypted_state", Base64.encodeToString(AccountSnapshotCrypto.encrypt(bytes, auth.syncKey,
                requireNotNull(auth.userId)), Base64.NO_WRAP))
        }
        val result = JsonParser.parseString(auth.dataRequest("rpc/saabtv_save_account", body)).asJsonObject
        if (result.get("conflict").asBoolean) throw IOException("Sync conflict: another device has newer data. Local changes were not overwritten.")
        saveMetadata(result.get("revision").asLong, digest)
        return true
    }

    suspend fun useCloudCopy() {
        val cloud = remote() ?: throw IOException("No cloud backup is available.")
        restoreCloud(cloud)
    }

    private fun restoreCloud(cloud: JsonObject) {
        // Crash recovery: keep the authenticated encrypted source until every
        // database/file/preference restore has completed. Never journal plaintext.
        val bytes = AccountSnapshotCrypto.decrypt(Base64.decode(cloud.get("ciphertext").asString, Base64.NO_WRAP), auth.syncKey, requireNotNull(auth.userId))
        val stream = restoreJournal.startWrite()
        try { stream.write(cloud.toString().toByteArray()); restoreJournal.finishWrite(stream) }
        catch (e: Exception) { restoreJournal.failWrite(stream); throw e }
        snapshots.restore(bytes)
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
    @Volatile private var suspended = false
    private val preferenceStores = AccountSnapshotStore(context, db).preferenceStores()
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> requestSync() }
    private val databaseObserver = object : InvalidationTracker.Observer(AccountSnapshotStore.TABLES.toTypedArray()) {
        override fun onInvalidated(tables: Set<String>) { requestSync() }
    }

    fun start() {
        if (started || !auth.hasSession) return
        started = true
        db.invalidationTracker.addObserver(databaseObserver)
        preferenceStores.forEach { it.registerOnSharedPreferenceChangeListener(preferenceListener) }
        scope.launch {
            while (isActive) { delay(AccountSyncCadence.INTERVAL_MS); syncNow(automatic = true) }
        }
        requestSync()
    }

    fun requestSync() {
        if (suspended) return
        cadence.markDirty()
    }

    suspend fun syncNow(automatic: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!auth.hasSession || suspended) return@withLock false
            if (!cadence.beginAttempt(SystemClock.elapsedRealtime(), automatic)) return@withLock true
            try {
                _status.value = "Syncing…"
                profiles.saveActiveRuntimeState()
                cloud.upload()
                _status.value = "Synced"
                true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                cadence.markDirty()
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
                _status.value = "Synced"
            } catch (e: Exception) {
                // A failed restore must not leave routine sync disabled forever.
                suspended = false
                cadence.markDirty()
                throw e
            } finally { if (!useCloud) suspended = false }
        }
    }

    fun stop() {
        suspended = true
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
