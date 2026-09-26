package com.saab.tv.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateReleasePolicyTest {
    private val arm64 = ReleaseAsset("Saab-TV-arm64-v8a-release.apk", "https://example/64")
    private val armv7 = ReleaseAsset("Saab-TV-armeabi-v7a-release.apk", "https://example/32")

    @Test
    fun selectsApkMatchingDeviceAbiInsteadOfFirstAsset() {
        assertEquals(
            armv7,
            UpdateReleasePolicy.selectApk(listOf(arm64, armv7), listOf("armeabi-v7a"))
        )
    }

    @Test
    fun refusesAmbiguousWrongArchitectureRelease() {
        assertNull(UpdateReleasePolicy.selectApk(listOf(arm64, armv7), listOf("x86_64")))
    }

    @Test
    fun extractsChecksumForSelectedAssetFromMultiApkRelease() {
        val hash64 = "a".repeat(64)
        val hash32 = "b".repeat(64)
        val body = "${arm64.name} SHA-256: $hash64\n${armv7.name} SHA-256: $hash32"

        assertEquals(hash32, UpdateReleasePolicy.expectedSha256(armv7, body, 2))
    }

    @Test
    fun refusesGenericChecksumWhenMultipleApksExist() {
        assertNull(UpdateReleasePolicy.expectedSha256(armv7, "SHA-256: ${"c".repeat(64)}", 2))
    }

    @Test
    fun acceptsGitHubDigestWhenPresent() {
        val expected = "d".repeat(64)
        assertEquals(
            expected,
            UpdateReleasePolicy.expectedSha256(arm64.copy(digest = "sha256:$expected"), null, 2)
        )
    }
}
