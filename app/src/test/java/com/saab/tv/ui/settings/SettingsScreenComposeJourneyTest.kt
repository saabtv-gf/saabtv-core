package com.saab.tv.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.focus.FocusRequester
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
import org.junit.Assert.assertTrue
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
class SettingsScreenComposeJourneyTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: OfflineAppFixture
    private lateinit var viewModel: SettingsViewModel
    private lateinit var profile: ProfileEntity

    @Before fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        app = OfflineAppFixture(context)
        profile = ProfileEntity(id = 41, name = "Settings Journey")
        app.dao.insertProfile(profile)
        viewModel = SettingsViewModel(app.dao, app.configuration, SeekThumbnailCache(context), context, app.display)
    }

    @After fun tearDown() {
        viewModel.viewModelScope.cancel()
        app.close()
    }

    @Test fun settingsNavigationOpensPlaybackAndPersistsToggleInteraction() {
        val entryRequester = FocusRequester()
        val drawerRequester = FocusRequester()
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    currentProfile = profile,
                    onBack = {},
                    entryRequester = entryRequester,
                    drawerRequester = drawerRequester,
                    viewModel = viewModel
                )
            }
        }

        compose.onNodeWithText("Playback").performClick()
        compose.onNodeWithText("Configure video decoding and display settings.").assertExists()
        compose.onNodeWithText("Frame Rate Matching").performScrollTo().performClick()

        compose.waitUntil(5_000) {
            runBlocking { app.dao.getProfileById(profile.id)?.frameRateMatching == true }
        }
        compose.runOnIdle { assertTrue(runBlocking { app.dao.getProfileById(profile.id)!!.frameRateMatching }) }
    }

    @Test fun settingsNavigationOpensSortAndFilterWithoutLeavingTheSection() {
        val entryRequester = FocusRequester()
        val drawerRequester = FocusRequester()
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    currentProfile = profile,
                    onBack = {},
                    entryRequester = entryRequester,
                    drawerRequester = drawerRequester,
                    viewModel = viewModel
                )
            }
        }

        compose.onNodeWithText("Sort & Filter").performClick()
        compose.onNodeWithText("Configure how sources are sorted and filtered.").assertExists()
        compose.onNodeWithText("Sort Sources").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { app.dao.getProfileById(profile.id)?.sourceSortingEnabled == false } }
        assertEquals(false, runBlocking { app.dao.getProfileById(profile.id)?.sourceSortingEnabled })
    }
}
