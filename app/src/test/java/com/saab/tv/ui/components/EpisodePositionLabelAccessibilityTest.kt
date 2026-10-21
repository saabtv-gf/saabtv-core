package com.saab.tv.ui.components

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.tv.material3.MaterialTheme
import com.saab.tv.R
import com.saab.tv.data.localization.AppLanguage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class EpisodePositionLabelAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactEpisodeLabelHasLocalizedKannadaAnnouncement() = assertLocalizedLabel("kn")

    @Test fun compactEpisodeLabelHasLocalizedMalayalamAnnouncement() = assertLocalizedLabel("ml")

    private fun assertLocalizedLabel(language: String) {
        val base = RuntimeEnvironment.getApplication()
        val localized = AppLanguage.localizedContext(base, language)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized) {
                MaterialTheme {
                    EpisodePositionLabel(seasonNumber = 2, episodeNumber = 5)
                }
            }
        }

        compose.onNodeWithText("S2 · E5").assertIsDisplayed()
        compose.onNodeWithContentDescription(
            localized.getString(R.string.episode_accessibility_label, 2, 5)
        ).assertIsDisplayed()
    }
}
