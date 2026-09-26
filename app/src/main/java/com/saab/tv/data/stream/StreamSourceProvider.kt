package com.saab.tv.data.stream

import com.saab.tv.data.model.stremio.Stream
import java.util.Locale

enum class StreamSourceProvider(val label: String, val selectionRank: Int) {
    TORRENTIO("Torrentio", 0),
    MEDIA_FUSION("MediaFusion", 1)
}

object StreamSourceProviderResolver {
    fun detect(stream: Stream): StreamSourceProvider? {
        val providerText = listOfNotNull(
            stream.name,
            stream.title,
            stream.description,
            stream.addonTransportUrl,
            stream.url
        )
            .joinToString(" ")
            .lowercase(Locale.ROOT)

        return when {
            "torrentio" in providerText -> StreamSourceProvider.TORRENTIO
            "mediafusion" in providerText || "media fusion" in providerText -> {
                StreamSourceProvider.MEDIA_FUSION
            }
            else -> null
        }
    }

    fun selectionRank(stream: Stream): Int = detect(stream)?.selectionRank ?: Int.MAX_VALUE

    /**
     * Debrid addons often return an HTTPS URL without an info hash. Such a URL
     * is still torrent-derived and must not receive direct-stream availability
     * credit when its seeder metadata is missing.
     */
    fun requiresSeederMetadata(stream: Stream): Boolean =
        detect(stream) != null || StreamSortingService.isTorrent(stream)
}
