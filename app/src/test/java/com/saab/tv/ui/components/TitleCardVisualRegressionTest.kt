package com.saab.tv.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.ui.home.ViewMoreCard
import org.junit.Assert.assertEquals
import android.app.Application
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TitleCardVisualRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun missingPosterUsesTitleInitialsAndShowsWatchlistMarker() {
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                CompositionLocalProvider(LocalWatchlistIds provides setOf("tt-title")) {
                    SaabTvCard(
                        title = "North Shore", posterUrl = null,
                        previewItem = MetaItem(id = "tt-title", type = "movie", name = "North Shore"),
                        onClick = {}
                    )
                }
            }
        }

        compose.onNodeWithText("NS").assertExists()
        compose.onNodeWithContentDescription("Added to My Library").assertExists()
    }

    @Test fun landscapePreferenceKeepsSeriesStackCardAndWatchlistMarker() {
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                CompositionLocalProvider(
                    LocalTitleCardShape provides "landscape",
                    LocalWatchlistIds provides setOf("series-id")
                ) {
                    SaabTvCard(
                        title = "North Shore", posterUrl = null,
                        previewItem = MetaItem(id = "series-id", type = "series", name = "North Shore"),
                        onClick = {}
                    )
                }
            }
        }

        compose.onNodeWithText("NS").assertExists()
        compose.onNodeWithContentDescription("Added to My Library").assertExists()
    }

    @Test fun landscapeRecommendationFallsBackToInitialsWhenOnlyPortraitPosterExists() {
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                CompositionLocalProvider(LocalTitleCardShape provides "landscape") {
                    SaabTvCard(
                        title = "North Shore", posterUrl = "https://example.test/poster.jpg",
                        previewItem = MetaItem(
                            id = "tmdb:123", type = "movie", name = "North Shore",
                            poster = "https://example.test/poster.jpg", background = null
                        ),
                        onClick = {}
                    )
                }
            }
        }

        compose.onNodeWithText("NS").assertExists()
    }

    @Test fun watchedTmdbCardRendersTheWatchedTickFromItsResolvedAlias() {
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                CompositionLocalProvider(LocalWatchedIds provides setOf("tmdb:987")) {
                    SaabTvCard(
                        title = "Alias Target", posterUrl = null,
                        previewItem = MetaItem(id = "tmdb:987", type = "movie", name = "Alias Target"),
                        onClick = {}
                    )
                }
            }
        }

        compose.onNodeWithText("✓").assertExists()
    }

    @Test fun viewMoreCardUsesLandscapeAspectRatioForLandscapeRails() {
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                ViewMoreCard(
                    onClick = {}, cardWidth = 220.dp, isLandscape = true,
                    modifier = Modifier.testTag("landscape-view-more")
                )
            }
        }

        val bounds = compose.onNodeWithTag("landscape-view-more").fetchSemanticsNode().boundsInRoot
        assertEquals(16f / 9f, bounds.width / bounds.height, 0.02f)
    }
}
