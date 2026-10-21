package com.saab.tv.ui.profiles

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import com.saab.tv.R
import com.saab.tv.data.localization.AppLanguage
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.testing.FeatureFixture
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class ProfileOnboardingComposeJourneyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun welcomeAndLanguageWizardRenderLocalizedCopy() {
        val context = AppLanguage.localizedContext(RuntimeEnvironment.getApplication(), "ml")
        var started = 0
        val showWelcome = mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    if (showWelcome.value) {
                        WelcomeView(onStart = { started++; showWelcome.value = false })
                    } else {
                        ProfileLanguageStep(
                            priority = 1,
                            languages = listOf("en", "te", "hi"),
                            onSelect = {},
                            onBack = {}
                        )
                    }
                }
            }
        }

        compose.onNodeWithText(context.getString(R.string.profile_welcome_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.profile_create_first)).performClick()
        compose.runOnIdle { assertEquals(1, started) }

        compose.onNodeWithText(
            context.getString(R.string.profile_language_title, context.getString(R.string.profile_language_second))
        ).assertExists()
        compose.onNodeWithText(context.getString(R.string.common_continue)).assertExists()
    }

    @Test fun setupChoiceCanCopyFromAnotherProfileOrContinueManually() {
        val profiles = listOf(
            com.saab.tv.data.model.ProfileEntity(id = 1, name = "Living Room"),
            com.saab.tv.data.model.ProfileEntity(id = 2, name = "Bedroom")
        )
        var copiedFrom: Int? = null
        var manualSetups = 0
        compose.setContent {
            MaterialTheme {
                ProfileSetupChoiceStep(
                    profiles = profiles,
                    onCopy = { copiedFrom = it },
                    onManual = { manualSetups++ },
                    onBack = {}
                )
            }
        }

        compose.onNodeWithText("Copy Settings").performClick()
        assertEquals(1, copiedFrom)
        compose.onNodeWithText("Bedroom").performClick()
        compose.onNodeWithText("Copy Settings").performClick()
        assertEquals(2, copiedFrom)
        compose.onNodeWithText("Set Up Manually").performClick()
        assertEquals(1, manualSetups)
    }

    @Test fun languagePickerOffersUniqueChoicesAndReturnsTheSelectedCode() {
        var selectedLanguage: String? = null
        compose.setContent {
            MaterialTheme {
                ProfileLanguageStep(
                    priority = 1,
                    languages = listOf("ml", "en", "hi"),
                    onSelect = { selectedLanguage = it },
                    onBack = {}
                )
            }
        }

        compose.onNodeWithText("Malayalam").assertExists()
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.profile_language_change, "English")).performClick()
        compose.onAllNodesWithText("Second Language").assertCountEquals(2)
        compose.onAllNodesWithText("Malayalam").assertCountEquals(1)
        compose.onNodeWithText("French").performClick()
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.profile_language_change, "French")).assertExists()
        compose.onNodeWithText("Continue").performClick()
        compose.runOnIdle { assertEquals("fr", selectedLanguage) }
    }

    @Test fun finalLanguageStepUsesCreateProfileAction() {
        var selectedLanguage: String? = null
        compose.setContent {
            MaterialTheme {
                ProfileLanguageStep(
                    priority = 2,
                    languages = listOf("ml", "kn", "en"),
                    onSelect = { selectedLanguage = it },
                    onBack = {}
                )
            }
        }

        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.profile_language_change, "English")).assertExists()
        compose.onNodeWithText("Create Profile").performClick()
        compose.runOnIdle { assertEquals("en", selectedLanguage) }
    }

    @Test fun addProfileCardKeepsItsGeometryWhenFocused() {
        compose.setContent {
            MaterialTheme { AddProfileCard(onClick = {}, focusRequester = FocusRequester()) }
        }

        val label = compose.onNodeWithText("Add Profile")
        val boundsBeforeFocus = label.fetchSemanticsNode().boundsInRoot
        compose.onNode(hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle {
            assertEquals(boundsBeforeFocus, label.fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test fun profileSelectorScrollsByDpadToTheLastSupportedProfile() {
        val fixture = FeatureFixture(RuntimeEnvironment.getApplication())
        val viewModel = ProfileViewModel(
            fixture.dao,
            fixture.app.configuration,
            fixture.traktAuth,
            fixture.cache,
            fixture.userStateCommands
        )
        try {
            val profiles = (1..6).map { ProfileEntity(id = it, name = "Profile $it") }
            compose.setContent {
                MaterialTheme {
                    Box(Modifier.width(520.dp).height(500.dp)) {
                        ProfileSelectorView(
                            profiles = profiles,
                            onSelect = {},
                            onAdd = {},
                            onEdit = {},
                            onDelete = {},
                            viewModel = viewModel
                        )
                    }
                }
            }

            val first = compose.onNodeWithTag("profile-card-1")
            compose.waitUntil(2_000) { runCatching { first.fetchSemanticsNode(); true }.getOrDefault(false) }
            first.performSemanticsAction(SemanticsActions.RequestFocus)
            first.performKeyInput { repeat(5) { pressKey(Key.DirectionRight) } }
            compose.onNodeWithTag("profile-card-6").assertIsFocused()
        } finally {
            viewModel.viewModelScope.cancel()
            fixture.close()
        }
    }

    @Test fun avatarChoiceKeepsItsGeometryWhenFocused() {
        compose.setContent {
            MaterialTheme {
                AvatarGridItem(
                    resId = android.R.drawable.sym_def_app_icon,
                    onClick = {},
                    focusRequester = FocusRequester(),
                    modifier = Modifier.size(72.dp)
                )
            }
        }

        val choice = compose.onNode(hasClickAction())
        val boundsBeforeFocus = choice.fetchSemanticsNode().boundsInRoot
        choice.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle { assertEquals(boundsBeforeFocus, choice.fetchSemanticsNode().boundsInRoot) }
    }
}
