package com.saab.tv.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.cache.SeekThumbnailCache
import com.saab.tv.data.localization.AppLanguage
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.R
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
class SourcePreferencesSettingsComposeInteractionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: OfflineAppFixture
    private lateinit var viewModel: SettingsViewModel
    private lateinit var profile: ProfileEntity

    @Before fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        app = OfflineAppFixture(context)
        app.dao.insertProfile(ProfileEntity(id = 1, name = "Source Preferences"))
        profile = app.dao.getProfileById(1)!!
        viewModel = SettingsViewModel(app.dao, app.configuration, SeekThumbnailCache(context), context, app.display, app.userStateCommands)
    }

    @After fun tearDown() {
        viewModel.viewModelScope.cancel()
        app.close()
    }

    @Test fun sourceSortingCanBeDisabledAndEnabledAgain() {
        render()
        compose.onNodeWithText("Sort Sources").performClick()
        compose.waitUntil(5_000) { runBlocking { app.dao.getProfileById(1)?.sourceSortingEnabled == false } }
        assertEquals(false, runBlocking { app.dao.getProfileById(1)?.sourceSortingEnabled })
    }

    @Test fun qualityRankingAndSizeSeasonSeederOptionsAreReachable() {
        render()
        compose.onNodeWithText("Best Stream").performClick()
        compose.waitUntil(5_000) { runBlocking { app.dao.getProfileById(1)?.sourceSortPrimary == "smart_tcl_c755" } }

        compose.onNodeWithText("Size, Seasons & Seeders", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("Complete Season Torrents Only").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { app.dao.getProfileById(1)?.sourceSeasonPacksOnly == false } }
        compose.onNodeWithText("Hide Zero-Seeder Streams").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { app.dao.getProfileById(1)?.sourceHideZeroSeeders == false } }
        compose.runOnIdle {
            val saved = runBlocking { app.dao.getProfileById(1)!! }
            assertEquals(false, saved.sourceSeasonPacksOnly)
            assertEquals(false, saved.sourceHideZeroSeeders)
        }
    }

    @Test fun excludeFormatChipsUpdateDeviceScopedPreferences() {
        render()
        compose.onNodeWithText("Excluded Formats", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("Dolby Vision").performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(setOf("3d", "dv"), app.display.effective(runBlocking { app.dao.getProfileById(1)!! }).sourceExcludedFormats.split(",").toSet())
        }
    }

    @Test fun sortAndFilterSurfaceRendersLocalizedCopy() {
        val localized = AppLanguage.localizedContext(RuntimeEnvironment.getApplication(), "ml")
        render(localized)
        compose.onNodeWithText(localized.getString(R.string.sort_filter_title)).assertExists()
        compose.onNodeWithText(localized.getString(R.string.sort_filter_best_stream)).assertExists()
        compose.onNodeWithText(localized.getString(R.string.sort_filter_allowed_qualities), substring = true)
            .performScrollTo().performClick()
        compose.onNodeWithText(localized.getString(R.string.sort_filter_allowed_qualities_description))
            .performScrollTo().assertExists()
    }

    private fun render(context: android.content.Context = RuntimeEnvironment.getApplication()) {
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    SourcePreferencesSettings(profile, viewModel, {})
                }
            }
        }
    }
}
