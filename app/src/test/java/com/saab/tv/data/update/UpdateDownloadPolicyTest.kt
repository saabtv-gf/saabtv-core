package com.saab.tv.data.update

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class UpdateDownloadPolicyTest {
    private val apk = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 1, 2, 3, 4)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun copy(bytes: ByteArray = apk, length: Long = bytes.size.toLong(), digest: String = hash(bytes), max: Long = 100): ByteArray {
        val output = ByteArrayOutputStream()
        UpdateDownloadPolicy.copyVerified(ByteArrayInputStream(bytes), output, digest, length, max)
        return output.toByteArray()
    }
    @Test fun acceptsVerifiedKnownAndUnknownLengthDownloads() {
        assertArrayEquals(apk, copy())
        assertArrayEquals(apk, copy(length = -1))
    }
    @Test(expected = java.io.IOException::class) fun rejectsTruncatedDownload() { copy(length = 20) }
    @Test(expected = SecurityException::class) fun rejectsChecksumMismatch() { copy(digest = "0".repeat(64)) }
    @Test(expected = SecurityException::class) fun rejectsHtmlEvenWithMatchingHash() { copy("<html>error</html>".toByteArray()) }
    @Test(expected = java.io.IOException::class) fun boundsUnknownLengthDownload() { copy(length = -1, max = 6) }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidChecksum() { copy(digest = "invalid") }
    @Test fun permitsRealGitHubAssetRedirectsButNotLookalikeHosts() {
        assertTrue(UpdateDownloadPolicy.trustedUrl("https://release-assets.githubusercontent.com/asset?token=test"))
        assertTrue(UpdateDownloadPolicy.trustedUrl("https://objects.githubusercontent.com/asset"))
        assertFalse(UpdateDownloadPolicy.trustedUrl("https://github.com.attacker.test/asset"))
        assertFalse(UpdateDownloadPolicy.trustedUrl("https://evil.github.com/asset"))
        assertFalse(UpdateDownloadPolicy.trustedUrl("http://github.com/asset"))
        assertFalse(UpdateDownloadPolicy.trustedUrl("https://user:pass@github.com/asset"))
        assertFalse(UpdateDownloadPolicy.trustedUrl("https://github.com:8443/asset"))
    }
    @Test fun initialDownloadMustBelongToConfiguredReleaseRepo() {
        assertTrue(UpdateDownloadPolicy.releaseUrl("https://github.com/saabtv-gf/saabtv-core/releases/download/v1/app.apk", "saabtv-gf", "saabtv-core"))
        assertFalse(UpdateDownloadPolicy.releaseUrl("https://github.com/other/repo/releases/download/v1/app.apk", "saabtv-gf", "saabtv-core"))
        assertFalse(UpdateDownloadPolicy.releaseUrl("https://github.com/saabtv-gf/saabtv-core/releases/download/../../../other/app.apk", "saabtv-gf", "saabtv-core"))
    }
    @Test(expected = java.util.concurrent.CancellationException::class) fun respectsCancellationBeforeRead() {
        UpdateDownloadPolicy.copyVerified(ByteArrayInputStream(apk), ByteArrayOutputStream(), hash(apk), -1,
            checkCancellation = { throw java.util.concurrent.CancellationException() })
    }
}
