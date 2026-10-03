package com.saab.tv.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
class PlaybackSettingsComposeInteractionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: OfflineAppFixture
    private lateinit var profile: ProfileEntity
    private lateinit var viewModel: SettingsViewModel

    @Before fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        app = OfflineAppFixture(context)
        profile = ProfileEntity(id = 1, name = "Playback Settings")
        app.dao.insertProfile(profile)
        viewModel = SettingsViewModel(app.dao, app.configuration, SeekThumbnailCache(context), context, app.display)
        compose.setContent { MaterialTheme { PlaybackSettings(profile, viewModel, {}) } }
    }

    @After fun tearDown() {
        viewModel.viewModelScope.cancel()
        app.close()
    }

    @Test fun episodeSectionPersistsAutoSkipCountdownAndDisablesIntroSkip() {
        compose.onNode(hasText("Episodes & Auto-Skip", substring = true) and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasText("10 Seconds") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Skip Intro").performScrollTo().performClick()

        compose.waitUntil(5_000) {
            runBlocking {
                val saved = app.dao.getProfileById(1) ?: return@runBlocking false
                saved.introSkipCountdownSeconds == 10 && saved.outroSkipCountdownSeconds == 10 && !saved.skipIntro
            }
        }
        val saved = runBlocking { app.dao.getProfileById(1)!! }
        assertEquals(10, saved.introSkipCountdownSeconds)
        assertEquals(10, saved.outroSkipCountdownSeconds)
        assertEquals(false, saved.skipIntro)
    }

    @Test fun subtitleAppearanceSectionPersistsStyledSubtitlePreference() {
        compose.onNode(hasText("Subtitle Appearance", substring = true) and hasClickAction())
            .performScrollTo().performClick()
        compose.onNodeWithText("Styled ASS/SSA Subtitles").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { app.dao.getProfileById(1)?.assRendererEnabled == true } }
        assertEquals(true, runBlocking { app.dao.getProfileById(1)?.assRendererEnabled })
    }
}
