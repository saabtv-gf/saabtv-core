package com.saab.tv.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.testing.FeatureFixture
import com.saab.tv.ui.home.HomeViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TitleCardUserStateLiveJourneyTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var fixture: FeatureFixture
    private lateinit var viewModel: HomeViewModel

    @Before fun setUp() = runBlocking {
        fixture = FeatureFixture(RuntimeEnvironment.getApplication())
        fixture.dao.insertProfile(ProfileEntity(id = 92, name = "Title State Journey"))
        viewModel = fixture.home()
    }

    @After fun tearDown() {
        viewModel.viewModelScope.cancel()
        fixture.close()
    }

    @Test fun watchlistAndWatchedCommandsUpdateEveryVisibleCardWithoutReload() = runBlocking {
        val item = MetaItem("tt-live-state", "movie", "Shared Movie")
        val watchlistIds = fixture.dao.getWatchlistBadgeKeysForProfile(92).map { it.toSet() }

        val surfaces = listOf(
            "Home", "Movies", "Series", "More Like This",
            "Collection", "Filmography", "My Library", "Search"
        )
        compose.setContent {
            val watched by viewModel.watchedIdsForProfile(92).collectAsState(emptySet())
            val saved by watchlistIds.collectAsState(emptySet())
            MaterialTheme {
                CompositionLocalProvider(
                    LocalWatchedIds provides watched,
                    LocalWatchlistIds provides saved
                ) {
                    Column {
                        surfaces.chunked(4).forEach { row ->
                            Row {
                                row.forEach { surface ->
                                    val title = "$surface movie poster"
                                    SaabTvCard(
                                        title = title, posterUrl = null,
                                        previewItem = item.copy(name = title), onClick = {}
                                    )
                                }
                            }
                            Row {
                                row.forEach { surface ->
                                    val title = "$surface movie landscape"
                                    SaabTvCard(
                                        title = title, posterUrl = null,
                                        previewItem = item.copy(name = title), forceLandscape = true, onClick = {}
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        fun assertEverySurface(expected: String?) = surfaces.forEach { surface ->
            assertState("$surface movie poster", expected)
            assertState("$surface movie landscape", expected)
        }
        assertEverySurface(null)

        viewModel.toggleWatchlist(92, item).join()
        awaitEverySurface(surfaces, "Added to My Library")

        viewModel.markTitleWatched(92, item).join()
        awaitEverySurface(surfaces, "Watched")
        assertEquals(false, fixture.dao.isInWatchlist(92, item.id))

        viewModel.unmarkTitleWatched(92, item).join()
        awaitEverySurface(surfaces, null)
    }

    private fun awaitEverySurface(surfaces: List<String>, expected: String?) {
        surfaces.forEach {
            awaitState("$it movie poster", expected)
            awaitState("$it movie landscape", expected)
        }
    }

    private fun awaitState(title: String, expected: String?) {
        compose.waitUntil(3_000) {
            runCatching { assertState(title, expected); true }.getOrDefault(false)
        }
    }

    private fun assertState(title: String, expected: String?) {
        val node = compose.onNodeWithContentDescription(title).fetchSemanticsNode()
        if (expected == null) {
            assertFalse(node.config.contains(SemanticsProperties.StateDescription))
        } else {
            assertEquals(expected, node.config[SemanticsProperties.StateDescription])
        }
    }
}
