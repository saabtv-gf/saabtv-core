package com.saab.tv.data.profile

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import com.saab.tv.data.account.AccountStorage
import com.saab.tv.data.account.UserStateCommandRepository
import com.google.gson.Gson
import com.saab.tv.data.auth.StremioAuthManager
import com.saab.tv.data.local.AddonDao
import com.saab.tv.data.model.AddonEntity
import com.saab.tv.data.model.CatalogConfigEntity
import com.saab.tv.data.model.HubRowEntity
import com.saab.tv.data.model.HubRowItemEntity
import com.saab.tv.data.model.stremio.CatalogManifest
import com.saab.tv.data.repository.AddonRepository
import com.saab.tv.data.security.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class ProfileRuntimeSnapshot(
    val addons: List<AddonEntity> = emptyList(),
    val catalogConfigs: List<CatalogConfigEntity> = emptyList(),
    val hubRows: List<HubRowEntity> = emptyList(),
    val hubRowItems: List<HubRowItemEntity> = emptyList()
) {
    fun ownedBy(profileOwnerId: String) = copy(
        addons = addons.map { it.copy(profileOwnerId = profileOwnerId) },
        catalogConfigs = catalogConfigs.map { it.copy(profileOwnerId = profileOwnerId) },
        hubRows = hubRows.map { it.copy(profileOwnerId = profileOwnerId) },
        hubRowItems = hubRowItems.map { it.copy(profileOwnerId = profileOwnerId) }
    )

    fun hasRuntimeRows() = addons.isNotEmpty() || catalogConfigs.isNotEmpty() ||
        hubRows.isNotEmpty() || hubRowItems.isNotEmpty()
}

@Singleton
class ProfileConfigurationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AddonDao,
    private val stremioAuthManager: StremioAuthManager,
    private val addonRepository: AddonRepository,
    private val deviceDisplay: DeviceDisplayPreferences,
    private val userStateCommands: UserStateCommandRepository
) {
    companion object {
        private const val PREFS_FILE = "profile_configuration_prefs"
        private const val KEY_PENDING_SETUP_PROFILES = "pending_setup_profiles"
        private const val KEY_LAST_ACTIVE_PROFILE_ID = "last_active_profile_id"
        private const val KEY_PORTABLE_PROFILE_PREFIX = "portable_profile_id_"
        private const val KEY_SPLASH_ENABLED_PREFIX = "splash_enabled_"
        private const val SNAPSHOT_DIR = "profile_snapshots"
        private const val SNAPSHOT_PREFS_PREFIX = "profile_runtime_snapshot_"
        private const val SNAPSHOT_KEY_ALIAS_PREFIX = "saabtv_profile_runtime_"
        private const val SNAPSHOT_JSON_KEY = "runtime_snapshot_json"
        private const val LEGACY_PROFILE_OWNER_ID = "legacy-default"
        private const val DEFAULT_CINEMETA_MANIFEST_URL = "https://v3-cinemeta.strem.io/manifest.json"
        private const val DEFAULT_CINEMETA_TRANSPORT_URL = "https://v3-cinemeta.strem.io"
        private val PROFILE_OWNED_PREFERENCE_PREFIXES = listOf(
            "hide_episode_spoilers",
            "trailer_preview",
            "trailer_delay",
            "trailer_muted",
            "trailer_presentation"
        )
    }

    private val gson = Gson()
    private val prefs: SharedPreferences by lazy {
        AccountStorage.preferences(context, PREFS_FILE)
    }

    private var startupRuntimeCaptured = false
    private val runtimeMutex = Mutex()

    fun resetStartupCapture() { startupRuntimeCaptured = false }

    fun markPendingSetup(profileId: Int) {
        val updated = getPendingSetupIds().toMutableSet().apply { add(profileId.toString()) }
        prefs.edit().putStringSet(KEY_PENDING_SETUP_PROFILES, updated).apply()
    }

    fun needsInitialSetup(profileId: Int): Boolean {
        return getPendingSetupIds().contains(profileId.toString())
    }

    fun clearPendingSetup(profileId: Int) {
        val updated = getPendingSetupIds().toMutableSet().apply { remove(profileId.toString()) }
        prefs.edit().putStringSet(KEY_PENDING_SETUP_PROFILES, updated).apply()
    }

    suspend fun captureStartupRuntimeIfNeeded() {
        if (startupRuntimeCaptured) return
        delay(500L)
        withContext(Dispatchers.IO) {
            runtimeMutex.withLock {
                if (startupRuntimeCaptured) return@withLock
                startupRuntimeCaptured = true
                val lastActive = getLastActiveProfileId() ?: return@withLock
                if (needsInitialSetup(lastActive)) return@withLock
                saveRuntimeStateUnlocked(lastActive)
            }
        }
    }

    suspend fun saveRuntimeState(profileId: Int) = withContext(Dispatchers.IO) {
        runtimeMutex.withLock { saveRuntimeStateUnlocked(profileId) }
    }

    private suspend fun saveRuntimeStateUnlocked(profileId: Int) {
        val snapshot = captureRuntimeSnapshot(profileId)
        val previousSnapshot = readSnapshot(profileId)
        stremioAuthManager.saveCredentialsForProfile(profileId)
        setLastActiveProfileId(profileId)
        cachePortableProfileIdentity(profileId)
        // Runtime configuration and credential-bearing addon URLs use Keystore-encrypted local
        // storage, an encrypted local outbox, and end-to-end encryption for v3 transport.
        if (previousSnapshot != snapshot) {
            if (!userStateCommands.saveProfileRuntime(profileId, snapshot)) {
                com.saab.tv.data.account.AccountCloudStore.markCompatibilitySnapshotDirty(context)
            }
            // Commit the comparison baseline only after the durable outbox/fallback marker.
            // If journaling fails, the next save must retry the same local configuration.
            writeSnapshot(profileId, snapshot)
        }
    }

    suspend fun saveActiveRuntimeState() {
        val activeId = getLastActiveProfileId() ?: return
        saveRuntimeState(activeId)
    }

    suspend fun runtimeSnapshot(profileId: Int): ProfileRuntimeSnapshot = withContext(Dispatchers.IO) {
        runtimeMutex.withLock {
            val rows = captureRuntimeSnapshot(profileId)
            if (rows.hasRuntimeRows()) rows else readSnapshot(profileId) ?: rows
        }
    }

    /** Always acquire the runtime lock before a Room write transaction, including remote sync. */
    internal suspend fun <T> withRuntimeStateLock(block: suspend () -> T): T = runtimeMutex.withLock { block() }

    /** Called by sync while holding withRuntimeStateLock and the account database transaction. */
    internal suspend fun applyRuntimeSnapshotLocked(profileId: Int, snapshot: ProfileRuntimeSnapshot) {
        val owner = profileOwner(profileId)
        val portableSnapshot = snapshot.ownedBy(owner)
        if (readSnapshot(profileId) != portableSnapshot) writeSnapshot(profileId, portableSnapshot)
        // A server echo of our own accepted command must not create another Room
        // invalidation and schedule a new sync of the same configuration.
        if (captureRuntimeSnapshot(profileId) != portableSnapshot) {
            dao.replaceRuntimeState(owner, portableSnapshot.addons, portableSnapshot.catalogConfigs,
                portableSnapshot.hubRows, portableSnapshot.hubRowItems)
        }
    }

    suspend fun loadRuntimeState(profileId: Int) = withContext(Dispatchers.IO) {
        com.saab.tv.AppDiagnostics.event(context, "Profile", "Load Settings")
        runtimeMutex.withLock {
            dao.activateProfile(profileId)
            val existingSnapshot = readSnapshot(profileId)
            val persistedSnapshot = captureRuntimeSnapshot(profileId)
            val snapshot = if (persistedSnapshot.hasRuntimeRows()) {
                persistedSnapshot.also { if (existingSnapshot != it) writeSnapshot(profileId, it) }
            } else existingSnapshot ?: if (!needsInitialSetup(profileId)) {
                createDefaultRuntimeSnapshot().ownedBy(profileOwner(profileId)).also {
                    writeSnapshot(profileId, it)
                    stremioAuthManager.clearCredentialsForProfile(profileId)
                }
            } else {
                ProfileRuntimeSnapshot()
            }
            val owner = profileOwner(profileId)
            val ownedSnapshot = snapshot.ownedBy(owner)
            dao.replaceRuntimeState(
                owner,
                addons = ownedSnapshot.addons,
                catalogConfigs = ownedSnapshot.catalogConfigs,
                hubRows = ownedSnapshot.hubRows,
                hubRowItems = ownedSnapshot.hubRowItems
            )
            stremioAuthManager.loadCredentialsForProfile(profileId)
            setLastActiveProfileId(profileId)
            cachePortableProfileIdentity(profileId)
        }
    }

    suspend fun initializeFromScratch(profileId: Int) = withContext(Dispatchers.IO) {
        com.saab.tv.AppDiagnostics.event(context, "Profile", "Initialize Defaults")
        runtimeMutex.withLock {
            val owner = profileOwner(profileId)
            val snapshot = createDefaultRuntimeSnapshot().ownedBy(owner)
            writeSnapshot(profileId, snapshot)
            dao.replaceRuntimeState(owner, snapshot.addons, snapshot.catalogConfigs,
                snapshot.hubRows, snapshot.hubRowItems)
            stremioAuthManager.clearCredentialsForProfile(profileId)
            clearPendingSetup(profileId)
        }
    }

    suspend fun initializeByCopying(targetProfileId: Int, sourceProfileId: Int) {
        com.saab.tv.AppDiagnostics.event(context, "Profile", "Copy Settings")
        captureStartupRuntimeIfNeeded()
        withContext(Dispatchers.IO) {
            runtimeMutex.withLock {
                val sourceSnapshot = readSnapshot(sourceProfileId) ?: captureRuntimeSnapshot(sourceProfileId).also {
                    writeSnapshot(sourceProfileId, it)
                    stremioAuthManager.saveCredentialsForProfile(sourceProfileId)
                }
                val targetSnapshot = sourceSnapshot.ownedBy(profileOwner(targetProfileId))
                writeSnapshot(targetProfileId, targetSnapshot)
                dao.replaceRuntimeState(profileOwner(targetProfileId), targetSnapshot.addons,
                    targetSnapshot.catalogConfigs, targetSnapshot.hubRows, targetSnapshot.hubRowItems)
                stremioAuthManager.copyCredentialsBetweenProfiles(sourceProfileId, targetProfileId)
                if (!userStateCommands.saveProfileRuntime(targetProfileId, targetSnapshot)) {
                    com.saab.tv.data.account.AccountCloudStore.markCompatibilitySnapshotDirty(context)
                }
                copyProfileDisplayAndDashboardConfig(targetProfileId, sourceProfileId)
                deviceDisplay.copyProfile(sourceProfileId, targetProfileId)
                EpisodeSpoilerPreferences.copy(context, sourceProfileId, targetProfileId)
                TrailerPreviewPreferences.copy(context, sourceProfileId, targetProfileId)
                clearPendingSetup(targetProfileId)
            }
        }
    }

    fun deleteProfileState(profileId: Int) {
        com.saab.tv.AppDiagnostics.event(context, "Profile", "Delete Settings")
        getPortableProfileId(profileId)?.let {
            com.saab.tv.data.player.SourceSelectionStore.clearPortableProfile(context, it)
        }
        clearPendingSetup(profileId)
        prefs.edit().apply {
            remove("$KEY_SPLASH_ENABLED_PREFIX$profileId")
            remove("$KEY_PORTABLE_PROFILE_PREFIX$profileId")
            PROFILE_OWNED_PREFERENCE_PREFIXES.forEach { prefix -> remove("${prefix}_$profileId") }
            apply()
        }
        secureSnapshotPreferences(profileId).preferences.edit().clear().apply()
        AtomicFile(snapshotFile(profileId)).delete()
        stremioAuthManager.clearCredentialsForProfile(profileId)

        if (getLastActiveProfileId() == profileId) {
            prefs.edit().remove(KEY_LAST_ACTIVE_PROFILE_ID).apply()
        }
    }

    private suspend fun copyProfileDisplayAndDashboardConfig(targetProfileId: Int, sourceProfileId: Int) {
        val sourceProfile = dao.getProfileById(sourceProfileId) ?: return
        val targetProfile = dao.getProfileById(targetProfileId) ?: return

        userStateCommands.saveProfile(
            sourceProfile.copy(
                id = targetProfile.id,
                name = targetProfile.name,
                portableId = targetProfile.portableId,
                avatarRef = targetProfile.avatarRef,
                pinHash = targetProfile.pinHash,
                isActive = targetProfile.isActive,
                themeId = targetProfile.themeId
            )
        )
    }

    private suspend fun captureRuntimeSnapshot(profileId: Int): ProfileRuntimeSnapshot {
        val owner = profileOwner(profileId)
        val state = dao.getRuntimeStateForProfile(owner)
        return ProfileRuntimeSnapshot(
            addons = state.addons,
            catalogConfigs = state.catalogConfigs,
            hubRows = state.hubRows,
            hubRowItems = state.hubRowItems
        ).ownedBy(owner)
    }

    private suspend fun createDefaultRuntimeSnapshot(): ProfileRuntimeSnapshot {
        val fallbackCatalogs = listOf(
            CatalogManifest(type = "movie", id = "top", name = "Top"),
            CatalogManifest(type = "series", id = "top", name = "Top")
        )

        val manifest = runCatching { addonRepository.fetchManifest(DEFAULT_CINEMETA_MANIFEST_URL) }.getOrNull()
        val catalogs = manifest?.catalogs
            ?.filter { it.type == "movie" || it.type == "series" }
            ?.ifEmpty { fallbackCatalogs }
            ?: fallbackCatalogs

        val addonName = manifest?.name ?: "Cinemeta"
        val addonEntity = AddonEntity(
            transportUrl = DEFAULT_CINEMETA_TRANSPORT_URL,
            id = manifest?.id ?: "org.stremio.cinemeta",
            name = addonName,
            version = manifest?.version ?: "1.0.0",
            description = manifest?.description ?: "Official Stremio metadata addon",
            iconUrl = manifest?.logo,
            isTrusted = false,
            isEnabled = true,
            nickname = null,
            catalogsJson = gson.toJson(catalogs)
        )

        val configs = catalogs.mapIndexed { index, catalog ->
            val isMovie = catalog.type == "movie"
            val isSeries = catalog.type == "series"
            CatalogConfigEntity(
                uniqueId = "${DEFAULT_CINEMETA_TRANSPORT_URL}/${catalog.type}/${catalog.id}",
                transportUrl = DEFAULT_CINEMETA_TRANSPORT_URL,
                addonName = addonName,
                catalogType = catalog.type,
                catalogId = catalog.id,
                catalogName = catalog.name,
                customTitle = null,
                showInHome = true,
                showInMovies = isMovie,
                showInSeries = isSeries,
                homeOrder = index,
                moviesOrder = if (isMovie) index else 999,
                seriesOrder = if (isSeries) index else 999
            )
        }

        return ProfileRuntimeSnapshot(
            addons = listOf(addonEntity),
            catalogConfigs = configs,
            hubRows = emptyList(),
            hubRowItems = emptyList()
        )
    }

    private suspend fun writeSnapshot(profileId: Int, snapshot: ProfileRuntimeSnapshot) {
        val json = gson.toJson(snapshot.ownedBy(profileOwner(profileId)))
        val storage = secureSnapshotPreferences(profileId)
        check(storage.preferences.edit().putString(SNAPSHOT_JSON_KEY, json).commit()) {
            "Unable to save profile runtime configuration."
        }
        if (!storage.isPersistent) {
            com.saab.tv.AppDiagnostics.event(context, "Profile", "Runtime Snapshot Memory Only")
        }
        migrateLegacySnapshotIfPresent(profileId, storage, json)
    }

    private suspend fun readSnapshot(profileId: Int): ProfileRuntimeSnapshot? {
        val owner = profileOwner(profileId)
        val storage = secureSnapshotPreferences(profileId)
        val encryptedJson = runCatching { storage.preferences.getString(SNAPSHOT_JSON_KEY, null) }.getOrNull()
        encryptedJson?.let { json -> parseSnapshot(json, owner)?.let { return it } }

        val file = snapshotFile(profileId)
        val legacyFile = AtomicFile(file)
        if (!hasLegacySnapshot(file)) return null
        val legacyJson = runCatching {
            legacyFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull() ?: return null
        val legacySnapshot = parseSnapshot(legacyJson, owner) ?: return null
        migrateLegacySnapshotIfPresent(profileId, storage, gson.toJson(legacySnapshot))
        return legacySnapshot
    }

    private fun parseSnapshot(json: String, owner: String): ProfileRuntimeSnapshot? = runCatching {
        gson.fromJson(json, ProfileRuntimeSnapshot::class.java).ownedBy(owner)
    }.getOrNull()

    private suspend fun profileOwner(profileId: Int): String = dao.getProfileById(profileId)?.portableId
        ?.takeIf(String::isNotBlank) ?: LEGACY_PROFILE_OWNER_ID

    /** Keep legacy plaintext until an encrypted, durable copy has been read back successfully. */
    private fun migrateLegacySnapshotIfPresent(
        profileId: Int,
        storage: com.saab.tv.data.security.SecurePreferencesResult,
        json: String
    ) {
        val file = snapshotFile(profileId)
        val legacyFile = AtomicFile(file)
        if (!hasLegacySnapshot(file)) return
        if (!storage.isPersistent) {
            com.saab.tv.AppDiagnostics.event(context, "Profile", "Legacy Runtime Snapshot Kept For Encryption")
            return
        }
        val preferences = storage.preferences
        val saved = runCatching {
            preferences.edit().putString(SNAPSHOT_JSON_KEY, json).commit() &&
                preferences.getString(SNAPSHOT_JSON_KEY, null) == json
        }.getOrDefault(false)
        if (saved) legacyFile.delete()
        else com.saab.tv.AppDiagnostics.event(context, "Profile", "Legacy Runtime Snapshot Migration Failed")
    }

    private fun hasLegacySnapshot(file: File): Boolean =
        file.exists() || File("${file.path}.bak").exists()

    private fun secureSnapshotPreferences(profileId: Int) = SecurePreferences.create(
        context,
        "$SNAPSHOT_PREFS_PREFIX$profileId",
        "$SNAPSHOT_KEY_ALIAS_PREFIX$profileId"
    )

    private fun snapshotFile(profileId: Int): File {
        return File(File(AccountStorage.files(context), SNAPSHOT_DIR), "profile_$profileId.json")
    }

    private fun getPendingSetupIds(): Set<String> {
        return prefs.getStringSet(KEY_PENDING_SETUP_PROFILES, emptySet()) ?: emptySet()
    }

    fun getLastActiveProfileId(): Int? {
        val value = prefs.getInt(KEY_LAST_ACTIVE_PROFILE_ID, -1)
        return if (value == -1) null else value
    }

    /** Cached portable identity lets synchronous playback preferences survive local Room ID changes. */
    fun getPortableProfileId(profileId: Int): String? =
        prefs.getString("$KEY_PORTABLE_PROFILE_PREFIX$profileId", null)?.takeIf(String::isNotBlank)

    fun getLastActivePortableProfileId(): String? = getLastActiveProfileId()?.let(::getPortableProfileId)

    fun clearLastActiveProfileId() {
        prefs.edit().remove(KEY_LAST_ACTIVE_PROFILE_ID).apply()
    }

    /** Fast startup cache so Activity creation never blocks on a database read. */
    fun getCachedSplashEnabled(profileId: Int): Boolean {
        return prefs.getBoolean("$KEY_SPLASH_ENABLED_PREFIX$profileId", true)
    }

    fun cacheSplashEnabled(profileId: Int, enabled: Boolean) {
        prefs.edit().putBoolean("$KEY_SPLASH_ENABLED_PREFIX$profileId", enabled).apply()
    }

    private fun setLastActiveProfileId(profileId: Int) {
        prefs.edit().putInt(KEY_LAST_ACTIVE_PROFILE_ID, profileId).apply()
    }

    private suspend fun cachePortableProfileIdentity(profileId: Int) {
        val portableId = dao.getProfileById(profileId)?.portableId?.takeIf(String::isNotBlank) ?: return
        prefs.edit().putString("$KEY_PORTABLE_PROFILE_PREFIX$profileId", portableId).apply()
    }
}
