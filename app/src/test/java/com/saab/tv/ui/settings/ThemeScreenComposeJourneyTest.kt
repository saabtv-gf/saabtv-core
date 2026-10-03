package com.saab.tv.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.ThemeEntity
import com.saab.tv.testing.OfflineAppFixture
import com.saab.tv.ui.theme.DefaultThemes
import com.saab.tv.ui.theme.ThemeManager
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
class ThemeScreenComposeJourneyTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: OfflineAppFixture
    private lateinit var profile: ProfileEntity
    private lateinit var themeManager: ThemeManager
    private var backCalls = 0
    private var createCalls = 0
    private var editedTheme: ThemeEntity? = null

    @Before fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        app = OfflineAppFixture(context)
        profile = ProfileEntity(id = 72, name = "Themes", themeId = "void")
        app.dao.insertProfile(profile)
        DefaultThemes.ALL.forEach { app.dao.insertTheme(it) }
        themeManager = ThemeManager(app.dao)
        themeManager.setCurrentProfile(profile.id, profile.themeId)
    }

    @After fun tearDown() {
        themeManager.viewModelScope.cancel()
        app.close()
    }

    private fun show() {
        compose.setContent {
            MaterialTheme {
                ThemeScreen(
                    currentProfile = profile,
                    themeManager = themeManager,
                    onBack = { backCalls++ },
                    onCreateCustom = { createCalls++ },
                    onEditTheme = { editedTheme = it }
                )
            }
        }
    }

    @Test fun builtInThemeCatalogShowsCurrentAndAlternativeThemes() {
        show()
        compose.onNodeWithText("Neon").assertExists()
        compose.onNodeWithText("Void").assertExists()
        compose.runOnIdle { assertEquals("void", themeManager.currentTheme.value.id) }
    }

    @Test fun createCustomThemeActionReturnsToTheCaller() {
        show()
        compose.onNodeWithText("Create Custom Theme").performClick()
        compose.runOnIdle { assertEquals(1, createCalls) }
        compose.onNodeWithText("All Themes").assertExists()
        compose.onNodeWithText("Void").assertExists()
    }

    @Test fun customThemeCanBeOpenedForEditingOrDeletedAfterConfirmation() = runBlocking {
        app.dao.insertTheme(
            ThemeEntity(
                id = "custom_test", name = "My Custom", primaryColor = 0xFF33AAFF,
                backgroundColor = 0xFF101010, surfaceColor = 0xFF202020,
                textColor = 0xFFFFFFFF, textMutedColor = 0xFFAAAAAA,
                errorColor = 0xFFFF0000, isBuiltIn = false, category = "custom"
            )
        )
        show()
        compose.waitUntil(2_000) { themeManager.availableThemes.value.any { it.id == "custom_test" } }

        compose.onNodeWithText("My Themes")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performClick()
        compose.onNodeWithText("My Custom").assertExists()
        compose.onNodeWithContentDescription("Edit Theme").performClick()
        compose.onNodeWithText("Manage Theme").assertExists()
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithText("Edit", substring = false).performClick()
        compose.runOnIdle { assertEquals("custom_test", editedTheme?.id) }

        compose.onNodeWithContentDescription("Edit Theme").performClick()
        compose.onNodeWithText("Delete", substring = false).performClick()
        compose.onNodeWithText("Delete Theme?").assertExists()
        compose.mainClock.advanceTimeBy(250)
        compose.onNode(hasText("No") and hasClickAction()).performClick()
        compose.onNodeWithText("My Custom").assertExists()
        assertTrue(runBlocking { app.dao.getThemeById("custom_test") != null })

        compose.onNodeWithContentDescription("Edit Theme").performClick()
        compose.onNodeWithText("Delete", substring = false).performClick()
        compose.onNode(hasText("Yes") and hasClickAction()).performClick()
        compose.waitUntil(2_000) { runBlocking { app.dao.getThemeById("custom_test") == null } }
        Thread.sleep(150) // Allow Room's delete coroutine to finish before closing the in-memory DB.
        compose.runOnIdle { assertEquals(1, backCalls) }
    }
}
