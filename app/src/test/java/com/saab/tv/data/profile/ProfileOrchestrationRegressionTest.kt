package com.saab.tv.data.profile

import android.app.Application
import com.saab.tv.data.account.AccountStorage
import com.saab.tv.data.security.SecurePreferences
import com.saab.tv.data.model.*
import com.saab.tv.testing.OfflineAppFixture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProfileOrchestrationRegressionTest {
    private lateinit var app: OfflineAppFixture
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun setup() = runBlocking {
        app = OfflineAppFixture(context)
        app.dao.insertProfile(ProfileEntity(id = 1, name = "One"))
        app.dao.insertProfile(ProfileEntity(id = 2, name = "Two"))
        Unit
    }
    @After fun cleanup() = app.close()
    @Test fun pendingSetupAndSplashFlagsAreProfileScoped() {
        app.configuration.markPendingSetup(1); app.configuration.markPendingSetup(2)
        app.configuration.clearPendingSetup(1)
        assertFalse(app.configuration.needsInitialSetup(1)); assertTrue(app.configuration.needsInitialSetup(2))
        app.configuration.cacheSplashEnabled(1, false); app.configuration.cacheSplashEnabled(2, true)
        assertFalse(app.configuration.getCachedSplashEnabled(1)); assertTrue(app.configuration.getCachedSplashEnabled(2))
    }
    @Test fun switchingProfilesRestoresTheirOwnCatalogAndHubRuntime() = runBlocking {
        val firstOwner = requireNotNull(app.dao.getProfileById(1)?.portableId)
        val secondOwner = requireNotNull(app.dao.getProfileById(2)?.portableId)
        val first = CatalogConfigEntity("first", "https://offline.invalid", "Addon", "movie", "first")
        val second = first.copy(uniqueId = "second", catalogId = "second")
        app.dao.saveCatalogConfig(first)
        app.dao.insertHubRowWithItems(HubRowEntity("hub", shape = "SQUARE"),
            listOf(HubRowItemEntity("hub", "first", "First")))
        app.configuration.saveRuntimeState(1)
        app.dao.replaceRuntimeState(secondOwner, emptyList(), listOf(second), emptyList(), emptyList())
        app.configuration.saveRuntimeState(2)
        app.configuration.loadRuntimeState(1)
        assertEquals(listOf(first.copy(profileOwnerId = firstOwner)), app.dao.getAllCatalogConfigs().first())
        assertEquals("hub", app.dao.getAllHubRows().first().single().id)
        assertEquals(1, app.dao.getActiveProfileId())
        app.configuration.loadRuntimeState(2)
        assertEquals(listOf(second.copy(profileOwnerId = secondOwner)), app.dao.getAllCatalogConfigs().first())
        assertTrue(app.dao.getAllHubRows().first().isEmpty())
        assertEquals(2, app.configuration.getLastActiveProfileId())
    }
    @Test fun profileRuntimeSnapshotDoesNotWriteCredentialBearingJsonToPlaintextFile() = runBlocking {
        val addon = AddonEntity(
            transportUrl = "https://user:password@addon.example/manifest.json",
            id = "private-addon", name = "Private", version = "1", description = null,
            iconUrl = null
        )
        app.dao.insertAddon(addon)

        app.configuration.saveRuntimeState(1)

        val legacyPlaintext = File(AccountStorage.files(context), "profile_snapshots/profile_1.json")
        assertFalse("New snapshots must not be persisted as plaintext JSON", legacyPlaintext.exists())
        assertEquals(addon.transportUrl, app.configuration.runtimeSnapshot(1).addons.single().transportUrl)
    }
    @Test fun legacyRuntimeSnapshotRemainsReadableAndIsRemovedOnlyAfterDurableEncryption() = runBlocking {
        val profileId = 97
        val addon = AddonEntity(
            transportUrl = "https://legacy-user:legacy-password@addon.example/manifest.json",
            id = "legacy-addon", name = "Legacy", version = "1", description = null, iconUrl = null
        )
        val file = File(AccountStorage.files(context), "profile_snapshots/profile_$profileId.json")
        file.parentFile!!.mkdirs()
        file.writeText(com.google.gson.Gson().toJson(ProfileRuntimeSnapshot(addons = listOf(addon))))
        val encryptedStorage = SecurePreferences.create(
            context,
            "profile_runtime_snapshot_$profileId",
            "saabtv_profile_runtime_$profileId"
        )

        val restored = app.configuration.runtimeSnapshot(profileId)

        assertEquals(addon.transportUrl, restored.addons.single().transportUrl)
        assertEquals(
            "A legacy copy is removed only after encrypted storage is durable",
            !encryptedStorage.isPersistent,
            file.exists()
        )
    }
    @Test fun copyTransfersPlaybackSortingAndRuntimeButPreservesIdentityAndTheme() = runBlocking {
        val source = ProfileEntity(id = 1, name = "Source", themeId = "source-theme", subtitleSize = 150,
            seekTimeIntervalSeconds = 20, seekThumbnailIntervalSeconds = 20, sourceLanguagePriority1 = "ml",
            sourceLanguagePriority2 = "kn", sourceHideZeroSeeders = false, rememberSourceSelection = false)
        val target = ProfileEntity(id = 2, name = "Target", themeId = "target-theme", pinHash = "private-pin", avatarRef = "avatar")
        app.dao.insertProfile(source); app.dao.insertProfile(target)
        val catalog = CatalogConfigEntity("copy", "https://offline.invalid", "Addon", "series", "copy")
        app.dao.saveCatalogConfig(catalog)
        app.configuration.saveRuntimeState(1)
        val trailer = TrailerPreviewSettings(false, 10, false, "fullscreen")
        TrailerPreviewPreferences.set(context, 1, trailer)
        app.display.configure(true); app.display.setQualities(1, "720p")
        app.configuration.markPendingSetup(2)
        app.configuration.initializeByCopying(2, 1)
        assertEquals(source.copy(id = target.id, portableId = target.portableId, name = target.name, avatarRef = target.avatarRef,
            pinHash = target.pinHash, isActive = target.isActive, themeId = target.themeId), app.dao.getProfileById(2))
        assertNotEquals(source.portableId, app.dao.getProfileById(2)?.portableId)
        assertFalse(app.configuration.needsInitialSetup(2))
        assertEquals(trailer, TrailerPreviewPreferences.read(context, 2))
        app.configuration.loadRuntimeState(2)
        assertEquals(listOf(catalog.copy(profileOwnerId = target.portableId)), app.dao.getAllCatalogConfigs().first())
    }
    @Test fun deletingProfileStateDoesNotDeleteOtherProfileSnapshot() = runBlocking {
        app.configuration.saveRuntimeState(1); app.configuration.saveRuntimeState(2)
        app.configuration.markPendingSetup(1)
        val prefs = AccountStorage.preferences(context, "profile_configuration_prefs")
        prefs.edit()
            .putBoolean("hide_episode_spoilers_1", true)
            .putBoolean("trailer_preview_1", true)
            .putInt("trailer_delay_1", 10)
            .putBoolean("trailer_muted_1", false)
            .putString("trailer_presentation_1", "fullscreen")
            .putBoolean("hide_episode_spoilers_2", true)
            .putBoolean("trailer_preview_2", true)
            .putInt("trailer_delay_2", 20)
            .putBoolean("trailer_muted_2", true)
            .putString("trailer_presentation_2", "inline")
            .commit()
        val legacySnapshot = File(AccountStorage.files(context), "profile_snapshots/profile_1.json")
        legacySnapshot.parentFile!!.mkdirs()
        legacySnapshot.writeText("plaintext legacy snapshot")
        File("${legacySnapshot.path}.bak").writeText("plaintext AtomicFile backup")
        app.configuration.deleteProfileState(1)
        assertFalse(app.configuration.needsInitialSetup(1))
        assertEquals(2, app.configuration.getLastActiveProfileId())
        assertFalse("Deleting a profile must remove its legacy plaintext snapshot", legacySnapshot.exists())
        assertFalse("Deleting a profile must remove the AtomicFile backup too", File("${legacySnapshot.path}.bak").exists())
        listOf("hide_episode_spoilers", "trailer_preview", "trailer_delay", "trailer_muted", "trailer_presentation")
            .forEach { prefix ->
                assertFalse("Deleted profile's $prefix setting must be removed", prefs.contains("${prefix}_1"))
                assertTrue("Other profile's $prefix setting must remain", prefs.contains("${prefix}_2"))
            }
        app.configuration.loadRuntimeState(2)
        assertEquals(2, app.dao.getActiveProfileId())
        app.configuration.deleteProfileState(2)
        assertNull(app.configuration.getLastActiveProfileId())
    }
}
