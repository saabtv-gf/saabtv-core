package com.saab.tv.ui.components

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.saab.tv.ui.utils.artworkDecodeSizePx
import com.saab.tv.ui.utils.artworkMemoryCacheKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ArtworkDecodeSizeTest {
    @Test fun posterAndLandscapeRequestsUsePhysicalDisplaySize() {
        val density = Density(2.5f)

        assertEquals(IntSize(350, 525), artworkDecodeSizePx(140.dp, 210.dp, density))
        assertEquals(IntSize(475, 267), artworkDecodeSizePx(190.dp, 190.dp * (9f / 16f), density))
        assertEquals(IntSize(375, 113), artworkDecodeSizePx(150.dp, 45.dp, density))
    }

    @Test fun decodeDimensionsNeverBecomeZeroForTinyCards() {
        assertEquals(IntSize(1, 1), artworkDecodeSizePx(0.dp, 0.dp, Density(0.5f)))
    }

    @Test fun memoryCacheKeysSeparateArtworkRolesAndDecodeSizes() {
        val small = artworkMemoryCacheKey("landscape", "backdrop", IntSize(380, 214))
        val large = artworkMemoryCacheKey("landscape", "backdrop", IntSize(475, 267))

        assertEquals("landscape:backdrop:380x214", small)
        assertNotEquals(small, large)
        assertNotEquals(small, artworkMemoryCacheKey("logo", "backdrop", IntSize(380, 214)))
    }
}
