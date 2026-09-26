package com.saab.tv.data.player

import com.saab.tv.data.model.stremio.Stream
import java.util.Locale

/** Stable release metadata: signed URLs and changing seeder labels are not identities. */
internal object StreamIdentity {
    fun file(stream: Stream): String? = stream.behaviorHints?.filename
        ?.trim()?.takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT)

    fun episodeRelease(stream: Stream): String? {
        val filename = file(stream) ?: stream.title?.lineSequence()?.firstOrNull()
            ?.trim()?.lowercase(Locale.ROOT) ?: return null
        val marker = Regex("s(\\d{1,2})[ ._-]*e\\d{1,3}|(\\d{1,2})x\\d{1,3}")
        if (!marker.containsMatchIn(filename)) return null
        return marker.replace(filename) { match ->
            "s${match.groupValues[1].ifEmpty { match.groupValues[2] }.padStart(2, '0')}"
        }.replace(Regex("[ ._]+"), ".")
    }
}
