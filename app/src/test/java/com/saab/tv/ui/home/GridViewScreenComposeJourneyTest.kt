package com.saab.tv.ui.home

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.material3.MaterialTheme as Material3Theme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.schema.canonicalPresentationKey
import com.saab.tv.ui.components.ArtworkPrefetchDispatcher
import com.saab.tv.ui.components.ArtworkPrefetchRequest
import com.saab.tv.ui.components.ArtworkPrefetchRole
import com.saab.tv.ui.components.LocalTitleCardShape
import com.saab.tv.testing.FeatureFixture
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

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
        val focusedItemKeys = mutableListOf<String>()
        val movie = MetaItem("tt-grid-journey", "movie", "Grid Journey")
        compose.setContent {
            Material3Theme {
                MaterialTheme {
                    GridViewScreen(
                        title = "Browse",
                        items = listOf(movie),
                        lastFocusedIndex = null,
                        onFocusChange = { index, itemKey ->
                            focused += index
                            focusedItemKeys += itemKey
                        },
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
            assertEquals(listOf(movie.canonicalPresentationKey()), focusedItemKeys)
        }
    }

    @Test fun gridBackControlKeepsItsBoundsWhenFocused() {
        compose.setContent {
            Material3Theme {
                MaterialTheme {
                    GridViewScreen(
                        title = "Browse",
                        items = listOf(MetaItem("tt-grid-back", "movie", "Grid Back")),
                        lastFocusedIndex = null,
                        onFocusChange = { _, _ -> },
                        onMovieClick = {},
                        onBack = {},
                        allowTrailerAutoplay = false,
                        previewViewModel = previewViewModel
                    )
                }
            }
        }

        val back = compose.onNodeWithTag("grid-back-control")
        compose.onNodeWithContentDescription("Back").assertExists()
        val boundsBeforeFocus = back.fetchSemanticsNode().boundsInRoot
        back.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle { assertEquals(boundsBeforeFocus, back.fetchSemanticsNode().boundsInRoot) }
    }

    @Test fun viewAllGridFiltersMoviesAndSeriesWithoutLosingTheOtherSourceItems() {
        val movie = MetaItem("tt-hub-movie", "movie", "Hub Movie")
        val series = MetaItem("tt-hub-series", "series", "Hub Series")
        compose.setContent {
            Material3Theme {
                MaterialTheme {
                    GridViewScreen(
                        title = "Featured Catalog",
                        gridStateKey = "featured-catalog",
                        items = listOf(movie, series),
                        lastFocusedIndex = null,
                        onFocusChange = { _, _ -> },
                        onMovieClick = {},
                        onBack = {},
                        allowTrailerAutoplay = false,
                        previewViewModel = previewViewModel
                    )
                }
            }
        }

        compose.onNodeWithTag("grid-filter-button").performClick()
        compose.onNodeWithText("Movies").performClick()
        compose.onNodeWithContentDescription("Hub Movie").assertExists()
        compose.onNodeWithContentDescription("Hub Series").assertDoesNotExist()
    }

    @Test fun emptyGridFailureExposesAnActionableRetry() {
        var retried = false
        compose.setContent {
            Material3Theme {
                MaterialTheme {
                    GridViewScreen(
                        title = "Featured Catalog",
                        items = emptyList(),
                        lastFocusedIndex = null,
                        onFocusChange = { _, _ -> },
                        onMovieClick = {},
                        onBack = {},
                        hasInitialLoadError = true,
                        onRetryInitialLoad = { retried = true },
                        allowTrailerAutoplay = false,
                        previewViewModel = previewViewModel
                    )
                }
            }
        }

        compose.onNodeWithTag("grid-back-control").performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("grid-initial-retry").assertIsFocused().performClick()
        compose.runOnIdle { assertTrue(retried) }
    }

    @Test fun emptyFilteredHubCanContinuePagingAndKeepsTheActionReachableByRemote() {
        var loadMoreCount = 0
        compose.setContent {
            Material3Theme {
                MaterialTheme {
                    GridViewScreen(
                        title = "Mixed Catalog",
                        items = listOf(MetaItem("tt-only-series", "series", "Only Series")),
                        initialFilter = CatalogMediaFilter.MOVIES,
                        lastFocusedIndex = null,
                        onFocusChange = { _, _ -> },
                        onMovieClick = {},
                        onBack = {},
                        onLoadMore = { loadMoreCount++ },
                        hasMoreItems = true,
                        allowTrailerAutoplay = false,
                        previewViewModel = previewViewModel
                    )
                }
            }
        }

        compose.onNodeWithTag("grid-back-control").performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("grid-empty-load-more").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, loadMoreCount) }
    }

    @Test fun movingUpToGridHeaderAndBackDownRestoresTheLastFocusedCard() {
        val items = (0 until 6).map { MetaItem("tt-grid-return-$it", "movie", "Grid Return $it") }
        compose.setContent {
            Material3Theme {
                MaterialTheme {
                    GridViewScreen(
                        title = "Browse",
                        items = items,
                        lastFocusedIndex = null,
                        onFocusChange = { _, _ -> },
                        onMovieClick = {},
                        onBack = {},
                        allowTrailerAutoplay = false,
                        previewViewModel = previewViewModel
                    )
                }
            }
        }

        val lastCard = compose.onNodeWithContentDescription("Grid Return 5")
        lastCard.performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("grid-back-control").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        lastCard.assertIsFocused()
    }

    @Test fun gridVisiblePosterUsesSharedArtworkPrefetch() {
        val requests = CopyOnWriteArrayList<ArtworkPrefetchRequest>()
        renderGrid(
            item = MetaItem("tt-grid-poster", "movie", "Grid Poster", poster = "grid-poster"),
            shape = "poster",
            requests = requests
        )

        compose.waitUntil(5_000) {
            requests.contains(ArtworkPrefetchRequest(
                ArtworkPrefetchRole.Poster, "grid-poster", posterSize = DpSize(180.dp, 270.dp)
            ))
        }
        compose.onNodeWithContentDescription("Grid Poster")
            .assertWidthIsEqualTo(180.dp)
            .assertHeightIsEqualTo(270.dp)
    }

    @Test fun gridVisibleLandscapeUsesBackdropAndLogoPrefetch() {
        val requests = CopyOnWriteArrayList<ArtworkPrefetchRequest>()
        renderGrid(
            item = MetaItem(
                "tt-grid-landscape", "series", "Grid Landscape",
                background = "grid-backdrop", logo = "grid-logo"
            ),
            shape = "landscape",
            requests = requests
        )

        compose.waitUntil(5_000) {
            requests.contains(ArtworkPrefetchRequest(
                ArtworkPrefetchRole.Landscape, "grid-backdrop",
                landscapeSize = DpSize(280.dp, 280.dp * (9f / 16f))
            )) &&
                requests.contains(ArtworkPrefetchRequest(ArtworkPrefetchRole.Logo, "grid-logo"))
        }
    }

    @Test fun gridKeepsFocusedTitleAcrossReorderAndFocusesFallbackWhenItIsRemoved() {
        val first = MetaItem("tt-grid-first", "movie", "Grid First")
        val second = MetaItem("tt-grid-second", "movie", "Grid Second")
        val items = mutableStateOf(listOf(first, second))
        val savedIndex = mutableStateOf<Int?>(null)
        val savedItemKey = mutableStateOf<String?>(null)

        compose.setContent {
            Material3Theme {
                MaterialTheme {
                    GridViewScreen(
                        title = "Browse",
                        items = items.value,
                        lastFocusedIndex = savedIndex.value,
                        lastFocusedItemKey = savedItemKey.value,
                        onFocusChange = { index, itemKey ->
                            savedIndex.value = index
                            savedItemKey.value = itemKey
                        },
                        onMovieClick = {},
                        onBack = {},
                        allowTrailerAutoplay = false,
                        previewViewModel = previewViewModel
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Grid First")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertEquals(second.canonicalPresentationKey(), savedItemKey.value) }

        items.value = listOf(second, first)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Grid Second").assertIsFocused()

        items.value = listOf(first)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Grid First").assertIsFocused()
    }

    @Test fun restoringVisibleFocusedCardPreservesSavedGridViewport() {
        val items = (0 until 48).map { MetaItem("tt-grid-restore-$it", "movie", "Grid Restore $it") }
        val scrollPositions = CopyOnWriteArrayList<Pair<Int, Int>>()
        compose.setContent {
            Material3Theme {
                MaterialTheme {
                    GridViewScreen(
                        title = "Browse restore",
                        items = items,
                        lastFocusedIndex = 20,
                        onFocusChange = { _, _ -> },
                        onMovieClick = {},
                        onBack = {},
                        initialScrollIndex = 18,
                        onScrollPositionChange = { index, offset -> scrollPositions += index to offset },
                        allowTrailerAutoplay = false,
                        previewViewModel = previewViewModel
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Grid Restore 20").assertIsFocused()
        compose.runOnIdle {
            // LazyVerticalGrid reports the first item in the visible row; item 18
            // is column 1 on the row beginning at 12 in this six-column layout.
            assertEquals(12, scrollPositions.last().first)
        }
    }

    @Test fun focusedGridItemOutsideSavedViewportIsScrolledIntoView() {
        val items = (0 until 48).map { MetaItem("tt-grid-offscreen-$it", "movie", "Grid Offscreen $it") }
        val scrollPositions = CopyOnWriteArrayList<Pair<Int, Int>>()
        compose.setContent {
            Material3Theme {
                MaterialTheme {
                    GridViewScreen(
                        title = "Browse offscreen restore",
                        items = items,
                        lastFocusedIndex = 30,
                        onFocusChange = { _, _ -> },
                        onMovieClick = {},
                        onBack = {},
                        initialScrollIndex = 0,
                        onScrollPositionChange = { index, offset -> scrollPositions += index to offset },
                        allowTrailerAutoplay = false,
                        previewViewModel = previewViewModel
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Grid Offscreen 30").assertIsFocused()
        compose.runOnIdle {
            // Keep the nearest preceding row visible while bringing item 30 into view.
            assertEquals(24, scrollPositions.last().first)
        }
    }

    private fun renderGrid(item: MetaItem, shape: String, requests: MutableList<ArtworkPrefetchRequest>) {
        val dispatcher = ArtworkPrefetchDispatcher(
            poster = { requests += ArtworkPrefetchRequest(ArtworkPrefetchRole.Poster, it) },
            landscape = { requests += ArtworkPrefetchRequest(ArtworkPrefetchRole.Landscape, it) },
            logo = { requests += ArtworkPrefetchRequest(ArtworkPrefetchRole.Logo, it) },
            posterSized = { url, size -> requests += ArtworkPrefetchRequest(ArtworkPrefetchRole.Poster, url, size) },
            landscapeSized = { url, size -> requests += ArtworkPrefetchRequest(ArtworkPrefetchRole.Landscape, url, landscapeSize = size) }
        )
        compose.setContent {
            Material3Theme {
                MaterialTheme {
                    CompositionLocalProvider(LocalTitleCardShape provides shape) {
                        GridViewScreen(
                            title = "Artwork Grid",
                            items = listOf(item),
                            lastFocusedIndex = null,
                            onFocusChange = { _, _ -> },
                            onMovieClick = {},
                            onBack = {},
                            allowTrailerAutoplay = false,
                            previewViewModel = previewViewModel,
                            artworkPrefetchDispatcher = dispatcher
                        )
                    }
                }
            }
        }
    }
}
