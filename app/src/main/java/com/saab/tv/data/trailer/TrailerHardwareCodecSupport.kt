package com.saab.tv.data.trailer

import android.media.MediaFormat
import android.media.MediaCodecInfo.CodecProfileLevel
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/** Local capability checks only: no network work, decoder creation or software VP9. */
@androidx.annotation.OptIn(UnstableApi::class)
internal object TrailerHardwareCodecSupport {
    fun prepare(variants: List<TrailerPlaybackVariant>): List<TrailerPlaybackVariant> =
        TrailerPolicy.hardwareVp9Candidates(variants) { variant ->
            val decoder = supportedDecoder(variant)
            com.saab.tv.AppDiagnostics.event("Trailer", "VP9 Hardware Eligibility",
                "formatId=${variant.formatId} width=${variant.width} height=${variant.height} fps=${variant.fps} eligible=${decoder != null} decoder=${decoder.orEmpty()}")
            decoder
        }

    private fun supportedDecoder(variant: TrailerPlaybackVariant): String? {
        if (variant.width <= 0 || variant.height <= 0) return null
        return runCatching {
            val profileLevel = MediaCodecUtil.getCodecProfileAndLevel(Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_VP9).setCodecs(variant.codec).build())
            val format = MediaFormat.createVideoFormat(MimeTypes.VIDEO_VP9, variant.width, variant.height).apply {
                setInteger(MediaFormat.KEY_PROFILE, profileLevel?.first ?: CodecProfileLevel.VP9Profile0)
                profileLevel?.second?.let { setInteger(MediaFormat.KEY_LEVEL, it) }
                if (variant.fps > 0) setInteger(MediaFormat.KEY_FRAME_RATE, variant.fps)
                if (variant.bitrate > 0) setInteger(MediaFormat.KEY_BIT_RATE, variant.bitrate)
            }
            MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_VP9, false, false).firstOrNull { decoder ->
                decoder.hardwareAccelerated && !decoder.softwareOnly &&
                    runCatching { decoder.capabilities?.isFormatSupported(format) == true }.getOrDefault(false)
            }?.name
        }.getOrElse { failure ->
            com.saab.tv.AppDiagnostics.failure("Trailer", "VP9 Capability Check Failed", failure)
            null
        }
    }

    /** If the advertised hardware decoder fails, advance to AVC rather than CPU VP9. */
    val selector = MediaCodecSelector { mime, secure, tunneling ->
        MediaCodecUtil.getDecoderInfos(mime, secure, tunneling).filter {
            mime != MimeTypes.VIDEO_VP9 || (it.hardwareAccelerated && !it.softwareOnly)
        }
    }
}
