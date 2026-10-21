package com.saab.tv.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import com.saab.tv.domain.HubGroupRow
import com.saab.tv.domain.HubItem
import com.saab.tv.domain.HubShape
import com.saab.tv.testfixture.ComposeTestActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Android-TV D-pad journey for the Home Movies/Series hub shelf. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class HubRowTvJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestActivity>()

    @Test fun dpadMovesFocusAndSelectsTheExactHubOnAShortRail() {
        val hubs = listOf(
            HubItem("movies", "Movies", "movies-category"),
            HubItem("series", "Series", "series-category")
        )
        val entryRequester = FocusRequester()
        var selected: HubItem? = null

        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(1280.dp, 720.dp)) {
                    HubRow(
                        hubGroup = HubGroupRow("browse", "Browse", 0, hubs, HubShape.HORIZONTAL),
                        startPadding = 48.dp,
                        onHubClick = { selected = it },
                        entryRequester = entryRequester,
                        drawerRequester = FocusRequester(),
                        locallyFocusedItemId = null,
                        isTopNav = false,
                        rowIndex = 0,
                        isFirstRow = true,
                        repeatGate = DpadRepeatGate()
                    )
                }
            }
        }

        val movies = compose.onNode(hasText("Movies") and hasClickAction())
        val series = compose.onNode(hasText("Series") and hasClickAction())
        val focusFrame = compose.onNodeWithTag("hub-row-focus-indicator")
        movies.performSemanticsAction(SemanticsActions.RequestFocus)
        movies.assertIsFocused()

        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitUntil(3_000) {
            series.fetchSemanticsNode().config.contains(SemanticsProperties.Focused) &&
                series.fetchSemanticsNode().config[SemanticsProperties.Focused]
        }
        compose.waitForIdle()
        series.assertIsFocused()
        assertEquals(
            "short, non-scrollable rails place the shared focus frame on the selected card",
            series.fetchSemanticsNode().boundsInRoot.left,
            focusFrame.fetchSemanticsNode().boundsInRoot.left,
            1f
        )

        series.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("series-category", selected?.categoryId) }
    }
}
