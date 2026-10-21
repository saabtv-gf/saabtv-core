package com.saab.tv.ui.components

import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
class CenterCarouselRowComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun defaultAndSuppliedRowsCenterCardsWithinTheirViewport() {
        val suppliedState = LazyListState()
        val defaultSecondCard = FocusRequester()
        val suppliedSecondCard = FocusRequester()
        compose.setContent {
            Column(Modifier.fillMaxSize()) {
                CenterCarouselRow(itemWidth = 120.dp, itemSpacing = 16.dp) {
                    items(listOf("default-one", "default-two")) { label ->
                        Box(
                            Modifier
                                .then(if (label == "default-two") Modifier.focusRequester(defaultSecondCard) else Modifier)
                                .size(120.dp, 80.dp)
                                .focusable()
                                .testTag(label)
                        )
                    }
                }
                CenterCarouselRow(
                    itemWidth = 120.dp,
                    itemSpacing = 16.dp,
                    modifier = Modifier.testTag("supplied-carousel"),
                    state = suppliedState
                ) {
                    items(listOf("supplied-one", "supplied-two")) { label ->
                        Box(
                            Modifier
                                .then(if (label == "supplied-two") Modifier.focusRequester(suppliedSecondCard) else Modifier)
                                .size(120.dp, 80.dp)
                                .focusable()
                                .testTag(label)
                        )
                    }
                }
            }
        }

        compose.waitForIdle()
        compose.onNodeWithTag("default-one").assertExists()
        compose.onNodeWithTag("supplied-one").assertExists()
        compose.onNodeWithTag("default-one").assertLeftPositionInRootIsEqualTo(580.dp)
        compose.onNodeWithTag("supplied-one").assertLeftPositionInRootIsEqualTo(580.dp)

        compose.runOnIdle { defaultSecondCard.requestFocus() }
        compose.waitForIdle()
        compose.onNodeWithTag("default-two").assertLeftPositionInRootIsEqualTo(580.dp)

        compose.runOnIdle { suppliedSecondCard.requestFocus() }
        compose.waitForIdle()
        compose.onNodeWithTag("supplied-two").assertLeftPositionInRootIsEqualTo(580.dp)
    }
}
