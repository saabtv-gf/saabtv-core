package com.saab.tv.data.update

import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest

internal object UpdateDownloadPolicy {
    const val MAX_APK_BYTES = 256L * 1024 * 1024
    private val hosts = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

    fun trustedUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals("https", true) && uri.host?.lowercase() in hosts && uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443)
    }.getOrDefault(false)

    fun releaseUrl(url: String, owner: String, repo: String): Boolean = trustedUrl(url) && runCatching {
        val uri = URI(url)
        uri.host.equals("github.com", true) && uri.normalize().path.startsWith("/$owner/$repo/releases/download/") &&
            !uri.path.split('/').any { it == ".." || it == "." }
    }.getOrDefault(false)

    fun validHash(hash: String): Boolean = hash.matches(Regex("(?i)[a-f0-9]{64}"))

    /** Bound storage, detect truncated/HTML responses and verify before exposing an APK. */
    fun copyVerified(
        input: InputStream, output: OutputStream, expectedHash: String, contentLength: Long,
        maxBytes: Long = MAX_APK_BYTES,
        checkCancellation: () -> Unit = {},
        progress: (Long, Long) -> Unit = { _, _ -> }
    ): Long {
        require(validHash(expectedHash)) { "Update checksum is invalid." }
        if (contentLength == 0L || contentLength > maxBytes) throw IOException("Update download size is invalid.")
        val digest = MessageDigest.getInstance("SHA-256")
        val header = ByteArray(4)
        var headerCount = 0
        var count = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkCancellation()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            if (count + read > maxBytes) throw IOException("Update exceeds the download size limit.")
            val headerBytes = minOf(read, 4 - headerCount)
            buffer.copyInto(header, headerCount, 0, headerBytes)
            headerCount += headerBytes
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            count += read
            progress(count, contentLength)
        }
        checkCancellation()
        if (count < 4 || (contentLength >= 0 && count != contentLength)) throw IOException("Update download was incomplete. Please retry.")
        if (!header.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))) throw SecurityException("Server did not return an APK file.")
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (!hash.equals(expectedHash, true)) throw SecurityException("APK checksum mismatch. Download rejected.")
        return count
    }
}
