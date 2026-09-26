package com.saab.tv.data.cache

import java.net.URI

/**
 * Native Android equivalent of the TorBox SDK's download-link handling needed
 * by the thumbnail worker. Renewable requestdl links are kept as permalinks so
 * every new extractor can receive a fresh CDN redirect.
 */
object TorBoxStreamUrlPolicy {
    fun isRemoteHttpStream(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase().orEmpty()
        return (scheme == "http" || scheme == "https") &&
            host.isNotBlank() &&
            host != "localhost" &&
            host != "127.0.0.1" &&
            host != "::1"
    }

    fun forThumbnailWorker(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed
        val hasRedirect = uri.rawQuery.orEmpty().split('&').any { parameter ->
            parameter.substringBefore('=').equals("redirect", ignoreCase = true)
        }
        if (!isTorBoxRequestDownloadLink(uri) || hasRedirect) {
            return trimmed
        }
        val separator = if (uri.rawQuery.isNullOrBlank()) "?" else "&"
        return "$trimmed${separator}redirect=true"
    }

    fun isTorBoxUrl(rawUrl: String): Boolean {
        val host = runCatching { URI(rawUrl.trim()).host?.lowercase() }.getOrNull()
            ?: return false
        return host == "torbox.app" || host.endsWith(".torbox.app")
    }

    private fun isTorBoxRequestDownloadLink(uri: URI): Boolean {
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path?.lowercase().orEmpty()
        return (host == "api.torbox.app" || host.endsWith(".api.torbox.app")) &&
            path.contains("/torrents/requestdl")
    }
}
