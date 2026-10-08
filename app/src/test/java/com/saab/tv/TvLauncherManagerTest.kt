package com.saab.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvLauncherManagerTest {
    @Test
    fun detectsFireTvUsingAmazonSystemFeatureName() {
        var requestedFeature: String? = null

        val isFireTv = TvLauncherManager.isFireTv { feature ->
            requestedFeature = feature
            feature == "amazon.hardware.fire_tv"
        }

        assertTrue(isFireTv)
        assertEquals("amazon.hardware.fire_tv", requestedFeature)
    }

    @Test
    fun nonFireDeviceKeepsStandardLauncher() {
        val requestedFeatures = mutableListOf<String>()

        val isFireTv = TvLauncherManager.isFireTv { feature ->
            requestedFeatures += feature
            false
        }

        assertFalse(isFireTv)
        assertEquals(
            listOf("amazon.hardware.fire_tv", "com.hardware.amazon.fire_tv"),
            requestedFeatures
        )
    }
}
