package com.saab.tv.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalDensity
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
class PlaybackSettingsComposeInteractionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: OfflineAppFixture
    private lateinit var profile: ProfileEntity
    private lateinit var viewModel: SettingsViewModel
    private var density = 1f

    @Before fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        app = OfflineAppFixture(context)
        profile = ProfileEntity(id = 1, name = "Playback Settings")
        app.dao.insertProfile(profile)
        viewModel = SettingsViewModel(app.dao, app.configuration, SeekThumbnailCache(context), context, app.display, app.userStateCommands)
        compose.setContent {
            density = LocalDensity.current.density
            MaterialTheme { PlaybackSettings(profile, viewModel, {}) }
        }
    }

    @After fun tearDown() {
        viewModel.viewModelScope.cancel()
        app.close()
    }

    @Test fun episodeSectionPersistsAutoSkipCountdownAndDisablesIntroSkip() {
        compose.onNode(hasText("Episodes & Auto-Skip", substring = true) and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
        val countdownOption = compose.onNode(hasText("10 Seconds") and hasClickAction())
        countdownOption.performScrollTo()
        compose.waitForIdle()
        countdownOption.performClick()

        compose.waitUntil(5_000) {
            runBlocking {
                val saved = app.dao.getProfileById(1) ?: return@runBlocking false
                saved.introSkipCountdownSeconds == 10 && saved.outroSkipCountdownSeconds == 10
            }
        }
        compose.onNodeWithText("Skip Intro").performScrollTo().performClick()

        compose.waitUntil(5_000) {
            runBlocking { app.dao.getProfileById(1)?.skipIntro == false }
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

    @Test fun skipRecapHasAnIndependentPlaybackSetting() {
        compose.onNode(hasText("Episodes & Auto-Skip", substring = true) and hasClickAction())
            .performScrollTo().performClick()
        compose.onNodeWithText("Skip Recap").performScrollTo().performClick()

        compose.waitUntil(5_000) {
            runBlocking { app.dao.getProfileById(1)?.skipRecap == false }
        }
        val saved = runBlocking { app.dao.getProfileById(1)!! }
        assertEquals(true, saved.skipIntro)
        assertEquals(false, saved.skipRecap)
    }

    @Test fun languagePriorityRowsAndSubtitleHaveClearSeparation() {
        compose.onNode(hasText("Languages", substring = true) and hasClickAction())
            .performScrollTo().performClick()
        compose.onNodeWithText("3rd Priority").performScrollTo()
        compose.onNodeWithTag("subtitle-language-primary").performScrollTo()
        compose.onNodeWithTag("subtitle-language-secondary").performScrollTo()
        compose.waitForIdle()

        val priority1 = compose.onNodeWithTag("language-priority-1").fetchSemanticsNode().boundsInRoot
        val priority2 = compose.onNodeWithTag("language-priority-2").fetchSemanticsNode().boundsInRoot
        val priority3 = compose.onNodeWithTag("language-priority-3").fetchSemanticsNode().boundsInRoot
        val subtitleTop = compose.onNodeWithTag("subtitle-language-primary")
            .fetchSemanticsNode().boundsInRoot.top
        val subtitleBottom = compose.onNodeWithTag("subtitle-language-primary")
            .fetchSemanticsNode().boundsInRoot.bottom
        val secondarySubtitleTop = compose.onNodeWithTag("subtitle-language-secondary")
            .fetchSemanticsNode().boundsInRoot.top
        val firstGapDp = (priority2.top - priority1.bottom) / density
        val secondGapDp = (priority3.top - priority2.bottom) / density
        val subtitleGapDp = (subtitleTop - priority3.bottom) / density

        assertTrue("Expected at least 12dp between the first two priorities, got ${firstGapDp}dp", firstGapDp >= 12f)
        assertTrue("Expected at least 12dp between the last two priorities, got ${secondGapDp}dp", secondGapDp >= 12f)
        assertTrue("Expected at least 12dp before subtitles, got ${subtitleGapDp}dp", subtitleGapDp >= 12f)
        val secondarySubtitleGapDp = (secondarySubtitleTop - subtitleBottom) / density
        assertTrue(
            "Expected at least 12dp between subtitle-language controls, got ${secondarySubtitleGapDp}dp",
            secondarySubtitleGapDp >= 12f
        )
    }
}
