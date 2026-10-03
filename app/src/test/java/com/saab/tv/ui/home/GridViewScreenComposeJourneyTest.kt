package com.saab.tv.ui.home

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.material3.MaterialTheme as Material3Theme
import androidx.tv.material3.MaterialTheme
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.testing.FeatureFixture
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class GridViewScreenComposeJourneyTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: FeatureFixture
    private lateinit var previewViewModel: HomeViewModel

    @Before fun setUp() {
        fixture = FeatureFixture(RuntimeEnvironment.getApplication())
        previewViewModel = fixture.home()
    }

    @After fun tearDown() {
        previewViewModel.viewModelScope.cancel()
        fixture.close()
    }

    @Test fun gridCardActivatesTheSelectedTitleAndReportsFocusIndex() {
        val selected = mutableListOf<String>()
        val focused = mutableListOf<Int>()
        val movie = MetaItem("tt-grid-journey", "movie", "Grid Journey")
        compose.setContent {
            Material3Theme {
                MaterialTheme {
                    GridViewScreen(
                        title = "Browse",
                        items = listOf(movie),
                        lastFocusedIndex = null,
                        onFocusChange = { focused += it },
                        onMovieClick = { selected += it.id },
                        onBack = {},
                        allowTrailerAutoplay = false,
                        previewViewModel = previewViewModel
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Grid Journey").assertExists()
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle {
            assertEquals(listOf("tt-grid-journey"), selected)
            assertEquals(listOf(0), focused)
        }
    }
}
