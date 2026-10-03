package com.saab.tv.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.auth.StremioAuthManager
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.remote.StremioAuthService
import com.saab.tv.data.stream.TorBoxAvailabilityService
import com.saab.tv.testing.FeatureFixture
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
class IntegrationsComposeInteractionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: FeatureFixture
    private lateinit var torbox: TorBoxAvailabilityService
    private lateinit var viewModel: IntegrationsViewModel

    @Before fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        fixture = FeatureFixture(context)
        fixture.dao.insertProfile(ProfileEntity(id = 1, name = "Integration Test"))
        fixture.tmdb.clearApiKey()
        torbox = TorBoxAvailabilityService(context)
        torbox.clear()
        viewModel = IntegrationsViewModel(
            StremioAuthManager(context, StremioAuthService()),
            fixture.app.repository,
            fixture.app.configuration,
            fixture.dao,
            fixture.tmdb,
            fixture.traktAuth,
            fixture.traktSync,
            torbox
        )
        compose.setContent { MaterialTheme { IntegrationsScreen(onBack = {}, viewModel = viewModel) } }
    }

    @After fun tearDown() {
        viewModel.viewModelScope.cancel()
        fixture.tmdb.clearApiKey()
        torbox.clear()
        fixture.close()
    }

    @Test fun tmdbIntegrationShowsKeySetupAndCanReturnWithoutChangingProfile() {
        compose.onNodeWithText("TMDB").performClick()
        compose.onNodeWithText("TMDB Settings").assertExists()
        compose.onNodeWithText("API Access").assertExists()
        compose.onNodeWithText("Save Key").assertExists()
        compose.onNodeWithText("Close").performClick()

        val profile = runBlocking { fixture.dao.getProfileById(1)!! }
        assertEquals(false, profile.tmdbEnabled)
        assertEquals("", profile.tmdbLanguage)
    }
}
