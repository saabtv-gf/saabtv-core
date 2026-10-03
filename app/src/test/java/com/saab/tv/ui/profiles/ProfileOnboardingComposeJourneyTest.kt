package com.saab.tv.ui.profiles

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import com.saab.tv.testing.FeatureFixture
import com.saab.tv.testing.awaitAppState
import kotlinx.coroutines.cancel
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
class ProfileOnboardingComposeJourneyTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: FeatureFixture
    private lateinit var viewModel: ProfileViewModel

    @Before fun setUp() {
        fixture = FeatureFixture(RuntimeEnvironment.getApplication())
        viewModel = ProfileViewModel(fixture.dao, fixture.app.configuration, fixture.traktAuth, fixture.cache)
        awaitAppState { !viewModel.isLoading.value }
        compose.setContent {
            MaterialTheme {
                ProfileScreen(profiles = emptyList(), onProfileSelected = {}, viewModel = viewModel)
            }
        }
    }

    @After fun tearDown() {
        viewModel.viewModelScope.cancel()
        fixture.close()
    }

    @Test fun welcomeCanEnterNameAndAvatarStepsAndReturnWithoutLosingName() {
        compose.onNodeWithText("Welcome To Saab TV").assertExists()
        compose.onNodeWithText("Create First Profile").performClick()
        compose.onNodeWithText("Name Your Profile").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("Cinema")
        compose.onNodeWithText("Continue").performClick()

        compose.onNodeWithText("Choose Your Avatar").assertExists()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Edit Your Profile").assertExists()
        compose.runOnIdle { assertEquals("Cinema", viewModel.tempName) }
    }
}
