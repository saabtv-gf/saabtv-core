package com.saab.tv.ui.home

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
class CatalogQuickActionStripTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allCardDestinationsCanShareOneAlignedStripAndUnknownStateStaysDisabled() {
        var watchedCalls = 0
        var watchlistCalls = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(180.dp)) {
                    CatalogQuickActionStrip(
                        listOf(
                            CatalogQuickAction(Icons.Default.Videocam, "Watch Trailer", {}, enabled = true),
                            CatalogQuickAction(Icons.Default.DoneAll, "Mark As Watched", { watchedCalls++ }, enabled = false),
                            CatalogQuickAction(Icons.Default.BookmarkBorder, "Add To Watchlist", { watchlistCalls++ }, enabled = false)
                        )
                    )
                }
            }
        }

        val trailer = compose.onNodeWithContentDescription("Watch Trailer").fetchSemanticsNode().boundsInRoot
        val watched = compose.onNodeWithContentDescription("Mark As Watched")
        val watchlist = compose.onNodeWithContentDescription("Add To Watchlist")
        val watchedBounds = watched.fetchSemanticsNode().boundsInRoot
        val watchlistBounds = watchlist.fetchSemanticsNode().boundsInRoot

        assertEquals(trailer.center.y, watchedBounds.center.y, 1f)
        assertEquals(trailer.center.y, watchlistBounds.center.y, 1f)
        compose.onNodeWithContentDescription("Watch Trailer").assertWidthIsEqualTo(42.dp)
        compose.onNodeWithContentDescription("Watch Trailer").assertHeightIsEqualTo(42.dp)
        assert(trailer.left < watchedBounds.left && watchedBounds.left < watchlistBounds.left)
        watched.assertIsNotEnabled()
        watchlist.assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(0, watchedCalls)
            assertEquals(0, watchlistCalls)
        }
    }
}
