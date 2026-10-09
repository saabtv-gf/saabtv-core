package com.saab.tv.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalFoundationApi::class)
class FocusPivotSpecTest {
    @Test fun focusedCardMovesToTheFixedShelfPivot() {
        val spec = FocusPivotSpec(customOffset = 60f)

        assertEquals(200f, spec.calculateScrollDistance(offset = 260f, size = 120f, containerSize = 1280f), 0f)
        assertEquals(-100f, spec.calculateScrollDistance(offset = -40f, size = 120f, containerSize = 1280f), 0f)
    }

    @Test fun restorationCanSkipInitialMotionThenPivotNormallyAfterNavigation() {
        var skip = true
        val spec = FocusPivotSpec(customOffset = 60f, skipScrollProvider = { skip })

        assertEquals(0f, spec.calculateScrollDistance(offset = 200f, size = 120f, containerSize = 1280f), 0f)
        skip = false
        assertEquals(140f, spec.calculateScrollDistance(offset = 200f, size = 120f, containerSize = 1280f), 0f)
    }

    @Test fun nearAlignedCardDoesNotMicroScroll() {
        val spec = FocusPivotSpec(customOffset = 60f)

        assertEquals(0f, spec.calculateScrollDistance(offset = 60.5f, size = 120f, containerSize = 1280f), 0f)
    }

    @Test fun focusFrameMatchesConstrainedPosterAndLandscapeCards() {
        assertEquals(FocusFrameSize(120.dp, 180.dp), focusFrameSize(120.dp, isLandscape = false))
        assertEquals(FocusFrameSize(190.dp, 106.875.dp), focusFrameSize(190.dp, isLandscape = true))
    }
}
