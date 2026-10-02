package com.saab.tv.data.trailer

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI

/** Keep only directly playable HTTPS renditions; reject fragments, DRM and arbitrary hosts. */
internal object YtDlpTrailerFormats {
    fun parse(json: String): List<TrailerPlaybackVariant> {
        val root = JsonParser.parseString(json).asJsonObject
        val formats = root.getAsJsonArray("formats")?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }.orEmpty()
            .filter { it.get("has_drm")?.let { value -> !value.isJsonNull && value.asBoolean } != true &&
                it.text("protocol") == "https" && it.get("fragments") == null && safeUrl(it.text("url")) }
        val audio = formats.filter { it.text("vcodec") == "none" && it.text("acodec") !in listOf("", "none") }
            .sortedWith(compareByDescending<JsonObject> { it.text("language").startsWith("en") }
                .thenByDescending { it.get("abr")?.takeIf { n -> !n.isJsonNull }?.asDouble ?: 0.0 }).firstOrNull()
        return TrailerPolicy.rank(formats.mapNotNull { video ->
            val height = video.get("height")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            if (height <= 0 || video.text("vcodec") in listOf("", "none")) return@mapNotNull null
            val audioUrl = if (video.text("acodec") in listOf("", "none")) audio?.text("url") ?: return@mapNotNull null else null
            // Do not import cookies, credentials or arbitrary extractor headers.
            TrailerPlaybackVariant(video.text("url"), audioUrl, "${height}p", height,
                mapOf("User-Agent" to TrailerHttpTransport.USER_AGENT))
        })
    }

    private fun JsonObject.text(name: String): String = get(name)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
    private fun safeUrl(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        uri.scheme == "https" && uri.userInfo == null &&
            (uri.host == "googlevideo.com" || uri.host?.endsWith(".googlevideo.com") == true)
    }.getOrDefault(false)
}
