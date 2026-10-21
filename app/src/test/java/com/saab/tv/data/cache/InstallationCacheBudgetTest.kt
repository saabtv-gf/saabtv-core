package com.saab.tv.data.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class InstallationCacheBudgetTest {
    @Test
    fun lowRamDevicesUseTheSmallestCacheCapsEvenWithLargeReportedMemoryClass() {
        val budget = InstallationCacheBudget.forDevice(memoryClassMiB = 512, isLowRamDevice = true)

        assertEquals(20L * MIB, budget.artworkMemoryBytes)
        assertEquals(60L * MIB, budget.artworkDiskBytes)
        assertEquals(15L * MIB, budget.metadataHttpBytes)
        assertEquals(150L * MIB, budget.seekThumbnailBytes)
    }

    @Test
    fun memoryClassSelectsBoundedConstrainedAndStandardProfiles() {
        val fallback = InstallationCacheBudget.forDevice(memoryClassMiB = 128, isLowRamDevice = false)
        val constrained = InstallationCacheBudget.forDevice(memoryClassMiB = 192, isLowRamDevice = false)
        val standard = InstallationCacheBudget.forDevice(memoryClassMiB = 384, isLowRamDevice = false)

        assertEquals(150L * MIB, fallback.seekThumbnailBytes)
        assertEquals(200L * MIB, constrained.seekThumbnailBytes)
        assertEquals(250L * MIB, standard.seekThumbnailBytes)
        assertTrue(fallback.managedDiskBytes < constrained.managedDiskBytes)
        assertTrue(constrained.managedDiskBytes < standard.managedDiskBytes)
    }

    @Test
    fun managedRegenerableDiskBudgetRemainsWithinTheProposedInstallationRange() {
        val constrained = InstallationCacheBudget.forDevice(memoryClassMiB = 192, isLowRamDevice = false)
        val standard = InstallationCacheBudget.forDevice(memoryClassMiB = 384, isLowRamDevice = false)

        assertTrue(constrained.managedDiskBytes <= 350L * MIB)
        assertTrue(standard.managedDiskBytes <= 450L * MIB)
    }

    @Test
    fun inactiveAccountCacheScopesAreEvictedBeforeTheActiveScope() {
        val root = Files.createTempDirectory("saab-cache-budget").toFile()
        try {
            val active = root.resolve("active").apply { mkdirs() }
            val inactive = root.resolve("inactive").apply { mkdirs() }
            active.resolve("keep.bin").writeBytes(ByteArray(4))
            inactive.resolve("evict.bin").writeBytes(ByteArray(8))

            InstallationCacheBudget.pruneInactiveScopes(root, activeScope = "active", maxBytes = 5)

            assertTrue(active.resolve("keep.bin").exists())
            assertTrue(!inactive.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val MIB = 1_048_576L
    }
}
