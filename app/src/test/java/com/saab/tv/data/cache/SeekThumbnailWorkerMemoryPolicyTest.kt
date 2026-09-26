package com.saab.tv.data.cache

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekThumbnailWorkerMemoryPolicyTest {
    @Test
    fun allowsWorkerOnlyWithSystemAndProcessHeadroom() {
        assertTrue(
            SeekThumbnailWorkerMemoryPolicy.hasHeadroom(
                systemLowMemory = false,
                availableSystemBytes = 512L * 1024L * 1024L,
                systemLowMemoryThresholdBytes = 96L * 1024L * 1024L,
                workerPssKb = 100 * 1024
            )
        )
        assertFalse(
            SeekThumbnailWorkerMemoryPolicy.hasHeadroom(
                systemLowMemory = true,
                availableSystemBytes = 512L * 1024L * 1024L,
                systemLowMemoryThresholdBytes = 96L * 1024L * 1024L,
                workerPssKb = 100 * 1024
            )
        )
        assertFalse(
            SeekThumbnailWorkerMemoryPolicy.hasHeadroom(
                systemLowMemory = false,
                availableSystemBytes = 512L * 1024L * 1024L,
                systemLowMemoryThresholdBytes = 96L * 1024L * 1024L,
                workerPssKb = 257 * 1024
            )
        )
        assertTrue(
            SeekThumbnailWorkerMemoryPolicy.hasHeadroom(
                systemLowMemory = false,
                availableSystemBytes = 512L * 1024L * 1024L,
                systemLowMemoryThresholdBytes = 96L * 1024L * 1024L,
                workerPssKb = 240 * 1024
            )
        )
    }

    @Test
    fun reservesLowMemoryThresholdPlusSafetyMargin() {
        assertFalse(
            SeekThumbnailWorkerMemoryPolicy.hasHeadroom(
                systemLowMemory = false,
                availableSystemBytes = 191L * 1024L * 1024L,
                systemLowMemoryThresholdBytes = 128L * 1024L * 1024L,
                workerPssKb = 80 * 1024
            )
        )
        assertTrue(
            SeekThumbnailWorkerMemoryPolicy.hasHeadroom(
                systemLowMemory = false,
                availableSystemBytes = 256L * 1024L * 1024L,
                systemLowMemoryThresholdBytes = 128L * 1024L * 1024L,
                workerPssKb = 80 * 1024
            )
        )
    }
}
