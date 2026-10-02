package com.saab.tv.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.cache.SeekThumbnailCache
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.testing.OfflineAppFixture
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Entire production settings screen connected to its real ViewModel and Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PlaybackSettingsScreenRegressionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: OfflineAppFixture
    private lateinit var vm: SettingsViewModel
    @Before fun setup() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        app = OfflineAppFixture(context)
        app.dao.insertProfile(ProfileEntity(id = 1, name = "One"))
        vm = SettingsViewModel(app.dao, app.configuration, SeekThumbnailCache(context), context, app.display)
        compose.setContent { MaterialTheme {
            val profile = app.dao.getProfileFlow(1).collectAsState(initial = ProfileEntity(id = 1, name = "One"))
            PlaybackSettings(profile.value, vm, {})
        } }
    }
    @After fun cleanup() { vm.viewModelScope.cancel(); app.close() }
    @Test fun collapsingDisplaySectionHidesItsControlsAndExpandingRestoresThem() {
        compose.onNodeWithText("Decoder Priority").assertExists()
        compose.onNodeWithText("Display & Player", substring = true).performClick()
        compose.onNodeWithText("Decoder Priority").assertDoesNotExist()
        compose.onNodeWithText("Display & Player", substring = true).performClick()
        compose.onNodeWithText("Decoder Priority").assertExists()
    }
    @Test fun seekControlWritesBothIntervalsThroughRealViewModel() {
        compose.onNodeWithText("Display & Player", substring = true).performClick()
        compose.onNodeWithText("Seeking & Source Selection", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("20 Seconds").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { app.dao.getProfileById(1)?.seekTimeIntervalSeconds == 20 } }
        runBlocking { assertEquals(20, app.dao.getProfileById(1)?.seekThumbnailIntervalSeconds) }
    }
    @Test fun playbackTogglePersistsAndScreenCanToggleItBack() {
        compose.onNodeWithText("Frame Rate Matching").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { app.dao.getProfileById(1)?.frameRateMatching == true } }
        compose.onNodeWithText("Frame Rate Matching").performClick()
        compose.waitUntil(5_000) { runBlocking { app.dao.getProfileById(1)?.frameRateMatching == false } }
    }
}
