package com.saab.tv.data.stream

import com.google.gson.JsonObject
import com.saab.tv.data.model.stremio.Stream
import java.net.URI
import java.net.URLDecoder

object TorBoxAvailabilityPolicy {
    private val hashPattern = Regex("[a-fA-F0-9]{40}")
    fun hash(stream: Stream): String? {
        stream.infoHash?.takeIf { hashPattern.matches(it) }?.let { return it.lowercase() }
        val url = stream.url ?: return null
        if (url.startsWith("magnet:?")) {
            val xt = url.substringAfter('?').split('&').firstOrNull { it.startsWith("xt=") }
                ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }
            return xt?.removePrefix("urn:btih:")?.takeIf { hashPattern.matches(it) }?.lowercase()
        }
        // Debrid add-ons commonly retain the infohash as an exact URL path segment.
        val provider = stream.addonTransportUrl.orEmpty().lowercase()
        if (!provider.contains("torrentio") && !provider.contains("mediafusion")) return null
        return runCatching { URI(url).path.split('/').firstOrNull { hashPattern.matches(it) }?.lowercase() }.getOrNull()
    }
    fun cached(response: JsonObject?, expectedHash: String): Boolean? = runCatching {
        if (response?.get("success")?.asBoolean != true) return@runCatching null
        val data = response.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: return@runCatching null
        if (data.keySet().any { !hashPattern.matches(it) }) return@runCatching null
        val item = data.entrySet().firstOrNull { it.key.equals(expectedHash, ignoreCase = true) }?.value
            ?: return@runCatching false
        if (!item.isJsonObject || item.asJsonObject.get("hash")?.asString?.lowercase() != expectedHash) null else true
    }.getOrNull()

}
