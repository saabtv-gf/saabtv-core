package com.saab.tv.ui.components

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
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
class LocalizedTitleMetadataTest {
    @get:Rule val compose = createComposeRule()

    @Test fun seriesTypeAndRuntimeLabelsUseKannadaResources() = assertLocalizedMetadata("kn")

    @Test fun seriesTypeAndRuntimeLabelsUseMalayalamResources() = assertLocalizedMetadata("ml")

    private fun assertLocalizedMetadata(language: String) {
        val base = RuntimeEnvironment.getApplication()
        val localized = AppLanguage.localizedContext(base, language)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized) {
                MaterialTheme {
                    Text(localizedTitleType("series"))
                    Text(localizedRuntimeLabel(125))
                }
            }
        }

        compose.onNodeWithText(localized.getString(R.string.title_type_series)).assertIsDisplayed()
        compose.onNodeWithText(
            localized.getString(R.string.title_runtime_hours_minutes, 2, 5)
        ).assertIsDisplayed()
    }
}
