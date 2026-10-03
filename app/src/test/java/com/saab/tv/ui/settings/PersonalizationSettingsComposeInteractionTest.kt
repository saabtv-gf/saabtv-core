package com.saab.tv.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.cache.SeekThumbnailCache
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.testing.OfflineAppFixture
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PersonalizationSettingsComposeInteractionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: OfflineAppFixture
    private lateinit var vm: SettingsViewModel

    @Before fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        app = OfflineAppFixture(context)
        app.dao.insertProfile(ProfileEntity(id = 1, name = "Personalized"))
        vm = SettingsViewModel(app.dao, app.configuration, SeekThumbnailCache(context), context, app.display)
        compose.setContent {
            MaterialTheme {
                val profile = app.dao.getProfileFlow(1).collectAsState(initial = ProfileEntity(id = 1, name = "Personalized"))
                PersonalizationSettings(profile.value, vm, {})
            }
        }
    }

    @After fun tearDown() {
        vm.viewModelScope.cancel()
        app.close()
    }

    @Test fun posterAndContinueWatchingSegmentsPersistSelection() {
        compose.onAllNodesWithText("Sharp")[0].performClick()
        compose.waitUntil(5_000) { runBlocking { app.dao.getProfileById(1)?.roundCorners == false } }

        compose.onNodeWithText("Landscape").performClick()
        compose.waitUntil(5_000) { runBlocking { app.dao.getProfileById(1)?.continueWatchingShape == "landscape" } }
        compose.runOnIdle {
            val profile = runBlocking { app.dao.getProfileById(1)!! }
            assertEquals(false, profile.roundCorners)
            assertEquals("landscape", profile.continueWatchingShape)
        }
    }

    @Test fun menuPositionAndSplashSettingPersist() {
        compose.onNodeWithText("Top").performClick()
        compose.waitUntil(5_000) { runBlocking { app.dao.getProfileById(1)?.navPosition == "top" } }

        compose.onNodeWithText("Off").performClick()
        compose.waitUntil(5_000) { runBlocking { app.dao.getProfileById(1)?.splashEnabled == false } }
    }
}
