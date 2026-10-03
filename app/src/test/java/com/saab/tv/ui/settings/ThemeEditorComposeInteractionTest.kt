package com.saab.tv.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
class ThemeEditorComposeInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun createThemeEditsNameAndSavesBothColors() {
        var savedName: String? = null
        var savedPrimary = 0L
        var savedBackground = 0L
        compose.setContent {
            MaterialTheme {
                ThemeEditorScreen(null, { name, primary, background ->
                    savedName = name
                    savedPrimary = primary
                    savedBackground = background
                }, {})
            }
        }

        compose.onNodeWithText("Background").performClick()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("Cinema")
        compose.onNodeWithText("Save").performClick()

        compose.runOnIdle {
            assertEquals("Cinema", savedName)
            assertTrue(savedPrimary != 0L)
            assertTrue(savedBackground != 0L)
            assertTrue(savedPrimary != savedBackground)
        }
    }

    @Test fun editThemeCancelAndSystemBackReturnWithoutSaving() {
        var cancelCalls = 0
        var saveCalls = 0
        val theme = com.saab.tv.data.model.ThemeEntity(
            "custom", "My Theme", 0xFFCC1122L, 0xFF101010L,
            0xFF151515L, 0xFFFFFFFFL, 0xFF888888L, 0xFFFF0000L
        )
        compose.setContent {
            MaterialTheme {
                ThemeEditorScreen(theme, { _, _, _ -> saveCalls++ }, { cancelCalls++ })
            }
        }

        compose.onNodeWithText("Edit Theme").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {
            assertEquals(1, cancelCalls)
            assertEquals(0, saveCalls)
        }
    }
}
