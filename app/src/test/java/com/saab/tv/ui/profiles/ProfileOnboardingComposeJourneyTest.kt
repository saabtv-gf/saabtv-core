package com.saab.tv.ui.profiles

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
class ProfileOnboardingComposeJourneyTest {
    @get:Rule val compose = createComposeRule()

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
        compose.onNodeWithText("English  ·  Change").performClick()
        compose.onAllNodesWithText("Second Language").assertCountEquals(2)
        compose.onAllNodesWithText("Malayalam").assertCountEquals(1)
        compose.onNodeWithText("French").performClick()
        compose.onNodeWithText("French  ·  Change").assertExists()
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

        compose.onNodeWithText("English  ·  Change").assertExists()
        compose.onNodeWithText("Create Profile").performClick()
        compose.runOnIdle { assertEquals("en", selectedLanguage) }
    }
}
