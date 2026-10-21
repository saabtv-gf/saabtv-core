package com.saab.tv.ui.home

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.saab.tv.domain.HubShape
import org.junit.Assert.assertEquals
import org.junit.Test

class HubCardArtworkTest {
    @Test
    fun customArtDecodeSizeMatchesEachRenderedHubCardShape() {
        val density = Density(1f)

        assertEquals(IntSize(190, 107), hubCardArtworkSizePx(HubShape.HORIZONTAL, density))
        assertEquals(IntSize(120, 180), hubCardArtworkSizePx(HubShape.VERTICAL, density))
        assertEquals(IntSize(140, 140), hubCardArtworkSizePx(HubShape.SQUARE, density))
    }
}
