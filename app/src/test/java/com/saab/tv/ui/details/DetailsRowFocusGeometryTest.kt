package com.saab.tv.ui.details

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.saab.tv.data.tmdb.TmdbCastInfo
import com.saab.tv.data.tmdb.TmdbCompanyInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
class DetailsRowFocusGeometryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun castCardKeepsHitBoundsStableWhenFocused() {
        val requester = FocusRequester()
        var focusObserved = false
        compose.setContent {
            MaterialTheme {
                CastCard(
                    member = TmdbCastInfo("Ada Actor", "Detective", null, 11),
                    textColor = Color.White,
                    modifier = Modifier.focusRequester(requester),
                    onFocused = { focusObserved = true }
                )
            }
        }
        val card = compose.onNode(hasText("Ada Actor") and hasClickAction())
        val avatar = compose.onNodeWithTag("details-cast-avatar", useUnmergedTree = true)
        val initialBounds = card.fetchSemanticsNode().boundsInRoot
        val avatarBounds = avatar.fetchSemanticsNode().boundsInRoot

        compose.runOnIdle { requester.requestFocus() }
        card.assertIsFocused()
        avatar.assertExists()
        assertEquals(72f, avatarBounds.width, 0.5f)
        assertEquals(72f, avatarBounds.height, 0.5f)
        assertEquals(initialBounds, card.fetchSemanticsNode().boundsInRoot)
        assertEquals(avatarBounds, avatar.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { assertTrue(focusObserved) }
    }

    @Test fun studioChipKeepsHitBoundsStableWhenFocused() {
        val requester = FocusRequester()
        var focusObserved = false
        compose.setContent {
            MaterialTheme {
                StudioChip(
                    studio = TmdbCompanyInfo("Studio", null, 22),
                    textColor = Color.White,
                    accentColor = Color.Cyan,
                    modifier = Modifier.focusRequester(requester),
                    onFocused = { focusObserved = true }
                )
            }
        }
        val chip = compose.onNode(hasText("Studio") and hasClickAction())
        val initialBounds = chip.fetchSemanticsNode().boundsInRoot

        compose.runOnIdle { requester.requestFocus() }
        chip.assertIsFocused()
        assertEquals(initialBounds, chip.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { assertTrue(focusObserved) }
    }
}
