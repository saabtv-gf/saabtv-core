package com.saab.tv.domain.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackIntentTest {
    @Test fun intentPreservesExactEpisodeAndResumeWithoutEmbeddingTheSourceUrl() {
        val intent = PlaybackIntent.create(
            profilePortableId = "profile-portable",
            titleId = "tt1234567:2:5",
            playableId = "tt1234567:2:5",
            type = "series",
            resumePositionMs = 912_345,
            selectedSourceId = "https://signed.example/file?token=secret",
            configurationGeneration = "config-v3"
        )

        assertEquals("tt1234567:2:5", intent.exactPlaybackId)
        assertEquals(912_345L, intent.resumePositionMs)
        assertTrue(intent.titleIdentityKey.contains("tt1234567"))
        assertTrue(intent.playableIdentityKey.contains("2%3A5"))
        assertNotEquals("https://signed.example/file?token=secret", intent.sourceFingerprint)
        assertTrue("https://signed.example/file?token=secret" !in intent.toString())
    }

    @Test fun sourceFingerprintsAreStableButDoNotExposeSourceIds() {
        val first = PlaybackIntent.create("p", "tt1234567", "tt1234567", "movie", 0,
            "source-1", "config")
        val same = PlaybackIntent.create("p", "tt1234567", "tt1234567", "movie", 0,
            "source-1", "config")
        val other = PlaybackIntent.create("p", "tt1234567", "tt1234567", "movie", 0,
            "source-2", "config")
        assertEquals(first.sourceFingerprint, same.sourceFingerprint)
        assertNotEquals(first.sourceFingerprint, other.sourceFingerprint)
        assertNotEquals("source-1", first.sourceFingerprint)
    }

    @Test fun unsupportedMediaKindsAreRejectedBeforeAnIntentIsCreated() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackIntent.create("p", "id", "id", "person", 0, null, "config")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackIntent.create("p", "id", "id", "unknown", 0, null, "config")
        }
    }
}
