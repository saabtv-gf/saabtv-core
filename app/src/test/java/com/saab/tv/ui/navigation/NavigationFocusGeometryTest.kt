package com.saab.tv.ui.navigation

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.saab.tv.data.model.ProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
class NavigationFocusGeometryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sidebarItemRetainsBoundsOnFocus() {
        val requester = FocusRequester()
        compose.setContent {
            MaterialTheme {
                SidebarItem(
                    screen = NavDestination.Movies,
                    isSelected = false,
                    isMenuExpanded = true,
                    isDrawerActive = true,
                    onNavigate = {},
                    modifier = Modifier.focusRequester(requester)
                )
            }
        }
        val item = compose.onNode(hasContentDescription("Movies") and hasClickAction())
        val initialBounds = item.fetchSemanticsNode().boundsInRoot

        compose.runOnIdle { requester.requestFocus() }
        item.assertIsFocused()
        assertEquals(initialBounds, item.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun drawerProfileItemRetainsBoundsOnFocus() {
        val requester = FocusRequester()
        compose.setContent {
            MaterialTheme {
                ProfileAvatarItem(
                    profile = ProfileEntity(id = 1, name = "Viewer"),
                    isMenuExpanded = true,
                    onNavigate = {},
                    modifier = Modifier.focusRequester(requester)
                )
            }
        }
        val item = compose.onNode(hasText("Viewer") and hasClickAction())
        val initialBounds = item.fetchSemanticsNode().boundsInRoot

        compose.runOnIdle { requester.requestFocus() }
        item.assertIsFocused()
        assertEquals(initialBounds, item.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun topNavigationProfileRetainsBoundsOnFocus() {
        val requester = FocusRequester()
        compose.setContent {
            MaterialTheme {
                TopNavProfileAvatar(
                    profile = ProfileEntity(id = 1, name = "Viewer"),
                    onNavigate = {},
                    modifier = Modifier.focusRequester(requester)
                )
            }
        }
        val item = compose.onNode(hasText("Viewer") and hasClickAction())
        val initialBounds = item.fetchSemanticsNode().boundsInRoot

        compose.runOnIdle { requester.requestFocus() }
        item.assertIsFocused()
        assertEquals(initialBounds, item.fetchSemanticsNode().boundsInRoot)
    }
}
