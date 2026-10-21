package com.saab.tv.domain.schema

import java.security.MessageDigest
import java.util.UUID

/** Compact, exact runtime handoff; excludes signed URLs, provider DTOs and screen state. */
data class PlaybackIntent(
    val profilePortableId: String,
    val titleIdentityKey: String,
    val playableIdentityKey: String,
    val mediaKind: String,
    val exactPlaybackId: String,
    val resumePositionMs: Long,
    val sourceFingerprint: String?,
    val configurationGeneration: String,
    val sessionId: String = UUID.randomUUID().toString()
) {
    init {
        require(profilePortableId.isNotBlank())
        require(titleIdentityKey.isNotBlank() && playableIdentityKey.isNotBlank())
        require(mediaKind == "movie" || mediaKind == "series")
        require(exactPlaybackId.isNotBlank() && resumePositionMs >= 0L)
        require(configurationGeneration.isNotBlank())
    }

    companion object {
        fun create(
            profilePortableId: String,
            titleId: String,
            playableId: String,
            type: String,
            resumePositionMs: Long,
            selectedSourceId: String?,
            configurationGeneration: String
        ): PlaybackIntent {
            val kind = ContentKind.fromProvider(type)?.takeIf {
                it == ContentKind.MOVIE || it == ContentKind.SERIES
            } ?: throw IllegalArgumentException("Unsupported playback type")
            return PlaybackIntent(
                profilePortableId = profilePortableId,
                titleIdentityKey = CanonicalEntityKey.title(titleId, kind.keyPart),
                playableIdentityKey = CanonicalEntityKey.playable(playableId, kind.keyPart),
                mediaKind = kind.keyPart,
                exactPlaybackId = playableId,
                resumePositionMs = resumePositionMs.coerceAtLeast(0L),
                sourceFingerprint = selectedSourceId?.takeIf(String::isNotBlank)?.let(::fingerprint),
                configurationGeneration = configurationGeneration
            )
        }

        private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).take(16)
            .joinToString("") { "%02x".format(it) }
    }
}
