package com.saab.tv.data.stream

import com.saab.tv.data.model.stremio.Stream

/** Counts only; safe to include in exported diagnostics without identifying content or credentials. */
object TorBoxDiagnosticSummary {
    fun sources(streams: List<Stream>): String {
        val torrentSources = streams.filter(StreamSourceProviderResolver::requiresSeederMetadata)
        return "sources=${streams.size} torrentSources=${torrentSources.size} " +
            "notChecked=${torrentSources.count { !it.torBoxChecked }} " +
            "checked=${torrentSources.count { it.torBoxChecked }} " +
            "cacheUnknown=${torrentSources.count { it.torBoxChecked && it.torBoxCached == null }} " +
            "torBoxSeeders=${torrentSources.count { it.torBoxSeeders != null }} " +
            "providerFallback=${torrentSources.count { it.torBoxSeeders == null && StreamParser.parse(it).seeds != null }}"
    }
}
