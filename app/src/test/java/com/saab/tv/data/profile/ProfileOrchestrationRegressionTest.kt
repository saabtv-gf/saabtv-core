package com.saab.tv.data.profile

import android.app.Application
import com.saab.tv.data.model.*
import com.saab.tv.testing.OfflineAppFixture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
        val first = CatalogConfigEntity("first", "https://offline.invalid", "Addon", "movie", "first")
        val second = first.copy(uniqueId = "second", catalogId = "second")
        app.dao.saveCatalogConfig(first)
        app.dao.insertHubRowWithItems(HubRowEntity("hub", shape = "SQUARE"),
            listOf(HubRowItemEntity("hub", "first", "First")))
        app.configuration.saveRuntimeState(1)
        app.dao.replaceRuntimeState(emptyList(), listOf(second), emptyList(), emptyList())
        app.configuration.saveRuntimeState(2)
        app.configuration.loadRuntimeState(1)
        assertEquals(listOf(first), app.dao.getAllCatalogConfigs().first())
        assertEquals("hub", app.dao.getAllHubRows().first().single().id)
        assertEquals(1, app.dao.getActiveProfileId())
        app.configuration.loadRuntimeState(2)
        assertEquals(listOf(second), app.dao.getAllCatalogConfigs().first())
        assertTrue(app.dao.getAllHubRows().first().isEmpty())
        assertEquals(2, app.configuration.getLastActiveProfileId())
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
        assertEquals(source.copy(id = target.id, name = target.name, avatarRef = target.avatarRef,
            pinHash = target.pinHash, isActive = target.isActive, themeId = target.themeId), app.dao.getProfileById(2))
        assertFalse(app.configuration.needsInitialSetup(2))
        assertEquals(trailer, TrailerPreviewPreferences.read(context, 2))
        app.configuration.loadRuntimeState(2)
        assertEquals(listOf(catalog), app.dao.getAllCatalogConfigs().first())
    }
    @Test fun deletingProfileStateDoesNotDeleteOtherProfileSnapshot() = runBlocking {
        app.configuration.saveRuntimeState(1); app.configuration.saveRuntimeState(2)
        app.configuration.markPendingSetup(1)
        app.configuration.deleteProfileState(1)
        assertFalse(app.configuration.needsInitialSetup(1))
        assertEquals(2, app.configuration.getLastActiveProfileId())
        app.configuration.loadRuntimeState(2)
        assertEquals(2, app.dao.getActiveProfileId())
        app.configuration.deleteProfileState(2)
        assertNull(app.configuration.getLastActiveProfileId())
    }
}
