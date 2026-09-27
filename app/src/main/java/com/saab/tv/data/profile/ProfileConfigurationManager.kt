package com.saab.tv.data.profile

import android.content.Context
import android.content.SharedPreferences
import com.saab.tv.data.account.AccountStorage
import android.util.AtomicFile
import com.google.gson.Gson
import com.saab.tv.data.auth.StremioAuthManager
import com.saab.tv.data.local.AddonDao
import com.saab.tv.data.model.AddonEntity
import com.saab.tv.data.model.CatalogConfigEntity
import com.saab.tv.data.model.HubRowEntity
import com.saab.tv.data.model.HubRowItemEntity
import com.saab.tv.data.model.stremio.CatalogManifest
import com.saab.tv.data.repository.AddonRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
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
)

@Singleton
class ProfileConfigurationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AddonDao,
    private val stremioAuthManager: StremioAuthManager,
    private val addonRepository: AddonRepository
) {
    companion object {
        private const val PREFS_FILE = "profile_configuration_prefs"
        private const val KEY_PENDING_SETUP_PROFILES = "pending_setup_profiles"
        private const val KEY_LAST_ACTIVE_PROFILE_ID = "last_active_profile_id"
        private const val KEY_SPLASH_ENABLED_PREFIX = "splash_enabled_"
        private const val SNAPSHOT_DIR = "profile_snapshots"
        private const val DEFAULT_CINEMETA_MANIFEST_URL = "https://v3-cinemeta.strem.io/manifest.json"
        private const val DEFAULT_CINEMETA_TRANSPORT_URL = "https://v3-cinemeta.strem.io"
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
        val snapshot = captureRuntimeSnapshot()
        writeSnapshot(profileId, snapshot)
        stremioAuthManager.saveCredentialsForProfile(profileId)
        setLastActiveProfileId(profileId)
    }

    suspend fun saveActiveRuntimeState() {
        val activeId = getLastActiveProfileId() ?: return
        saveRuntimeState(activeId)
    }

    suspend fun loadRuntimeState(profileId: Int) = withContext(Dispatchers.IO) {
        com.saab.tv.AppDiagnostics.event(context, "Profile", "Load Settings")
        runtimeMutex.withLock {
            dao.activateProfile(profileId)
            val existingSnapshot = readSnapshot(profileId)
            val snapshot = existingSnapshot ?: if (!needsInitialSetup(profileId)) {
                createDefaultRuntimeSnapshot().also {
                    writeSnapshot(profileId, it)
                    stremioAuthManager.clearCredentialsForProfile(profileId)
                }
            } else {
                ProfileRuntimeSnapshot()
            }
            dao.replaceRuntimeState(
                addons = snapshot.addons,
                catalogConfigs = snapshot.catalogConfigs,
                hubRows = snapshot.hubRows,
                hubRowItems = snapshot.hubRowItems
            )
            stremioAuthManager.loadCredentialsForProfile(profileId)
            setLastActiveProfileId(profileId)
        }
    }

    suspend fun initializeFromScratch(profileId: Int) = withContext(Dispatchers.IO) {
        com.saab.tv.AppDiagnostics.event(context, "Profile", "Initialize Defaults")
        runtimeMutex.withLock {
            writeSnapshot(profileId, createDefaultRuntimeSnapshot())
            stremioAuthManager.clearCredentialsForProfile(profileId)
            clearPendingSetup(profileId)
        }
    }

    suspend fun initializeByCopying(targetProfileId: Int, sourceProfileId: Int) {
        com.saab.tv.AppDiagnostics.event(context, "Profile", "Copy Settings")
        captureStartupRuntimeIfNeeded()
        withContext(Dispatchers.IO) {
            runtimeMutex.withLock {
                val sourceSnapshot = readSnapshot(sourceProfileId) ?: captureRuntimeSnapshot().also {
                    writeSnapshot(sourceProfileId, it)
                    stremioAuthManager.saveCredentialsForProfile(sourceProfileId)
                }
                writeSnapshot(targetProfileId, sourceSnapshot)
                stremioAuthManager.copyCredentialsBetweenProfiles(sourceProfileId, targetProfileId)
                copyProfileDisplayAndDashboardConfig(targetProfileId, sourceProfileId)
                clearPendingSetup(targetProfileId)
            }
        }
    }

    fun deleteProfileState(profileId: Int) {
        com.saab.tv.AppDiagnostics.event(context, "Profile", "Delete Settings")
        clearPendingSetup(profileId)
        prefs.edit().remove("$KEY_SPLASH_ENABLED_PREFIX$profileId").apply()
        snapshotFile(profileId).delete()
        stremioAuthManager.clearCredentialsForProfile(profileId)

        if (getLastActiveProfileId() == profileId) {
            prefs.edit().remove(KEY_LAST_ACTIVE_PROFILE_ID).apply()
        }
    }

    private suspend fun copyProfileDisplayAndDashboardConfig(targetProfileId: Int, sourceProfileId: Int) {
        val sourceProfile = dao.getProfileById(sourceProfileId) ?: return
        val targetProfile = dao.getProfileById(targetProfileId) ?: return

        dao.insertProfile(
            sourceProfile.copy(
                id = targetProfile.id,
                name = targetProfile.name,
                avatarRef = targetProfile.avatarRef,
                pinHash = targetProfile.pinHash,
                isActive = targetProfile.isActive,
                themeId = targetProfile.themeId
            )
        )
    }

    private suspend fun captureRuntimeSnapshot(): ProfileRuntimeSnapshot {
        return ProfileRuntimeSnapshot(
            addons = dao.getAllAddons().firstOrNull() ?: emptyList(),
            catalogConfigs = dao.getAllCatalogConfigs().firstOrNull() ?: emptyList(),
            hubRows = dao.getAllHubRows().firstOrNull() ?: emptyList(),
            hubRowItems = dao.getAllHubRowItems().firstOrNull() ?: emptyList()
        )
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

    private fun writeSnapshot(profileId: Int, snapshot: ProfileRuntimeSnapshot) {
        val file = snapshotFile(profileId)
        file.parentFile?.mkdirs()
        val atomicFile = AtomicFile(file)
        val stream = atomicFile.startWrite()
        try {
            val writer = stream.writer(Charsets.UTF_8)
            writer.write(gson.toJson(snapshot))
            writer.flush()
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            atomicFile.failWrite(stream)
            throw error
        }
    }

    private fun readSnapshot(profileId: Int): ProfileRuntimeSnapshot? {
        val file = snapshotFile(profileId)
        if (!file.exists()) return null
        return runCatching {
            AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use {
                gson.fromJson(it, ProfileRuntimeSnapshot::class.java)
            }
        }.getOrNull()
    }

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
}
