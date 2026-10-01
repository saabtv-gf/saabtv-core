package com.saab.tv.data.trailer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

@UnstableApi
class YoutubeChunkedDataSourceFactory(
    private val chunkSizeBytes: Long = CHUNK_SIZE,
    private val requestHeaders: Map<String, String> = emptyMap()
) : DataSource.Factory {

    companion object {
        private const val CHUNK_SIZE = 10L * 1024 * 1024
    }

    override fun createDataSource(): DataSource {
        val upstream = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(requestHeaders)
            .setUserAgent(
                requestHeaders["User-Agent"] ?: ("com.google.android.youtube/20.10.35 " +
                    "(Linux; U; Android 14; en_US) gzip")
            )
            .createDataSource()
        return YoutubeChunkedDataSource(upstream, chunkSizeBytes)
    }

    private class YoutubeChunkedDataSource(
        private val upstream: DefaultHttpDataSource,
        private val chunkSize: Long
    ) : DataSource {

        private var isYouTubeStream = false
        private var totalContentLength = C.LENGTH_UNSET.toLong()
        private var currentChunkStart = 0L
        private var currentChunkEnd = 0L
        private var bytesReadInChunk = 0L
        private var originalDataSpec: DataSpec? = null

        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val host = dataSpec.uri.host.orEmpty()
            isYouTubeStream = (host == "googlevideo.com" || host.endsWith(".googlevideo.com")) &&
                dataSpec.uri.path == "/videoplayback" &&
                dataSpec.uri.getQueryParameter("sq") == null

            if (!isYouTubeStream) return upstream.open(dataSpec)

            originalDataSpec = dataSpec
            currentChunkStart = dataSpec.position
            totalContentLength = dataSpec.length
            return openNextChunk()
        }

        private fun openNextChunk(): Long {
            val spec = originalDataSpec ?: throw IllegalStateException("No DataSpec")
            val end = if (totalContentLength != C.LENGTH_UNSET.toLong()) {
                minOf(currentChunkStart + chunkSize - 1, currentChunkStart + totalContentLength - 1)
            } else {
                currentChunkStart + chunkSize - 1
            }
            currentChunkEnd = end

            // Keep the signed URL untouched. A Range header is the normal CDN
            // request and avoids duplicate/signed query parameters being rejected.
            val chunkedSpec = spec.buildUpon()
                .setPosition(currentChunkStart)
                .setLength(currentChunkEnd - currentChunkStart + 1)
                .build()

            bytesReadInChunk = 0
            try {
                upstream.open(chunkedSpec)
            } catch (error: HttpDataSource.InvalidResponseCodeException) {
                if (error.responseCode != 400 && error.responseCode != 403) throw error

                // Compatibility fallback for edges requiring query ranges. Replace,
                // rather than append, any pre-existing range parameter.
                runCatching { upstream.close() }
                val uri = spec.uri.buildUpon().encodedQuery(spec.uri.encodedQuery.orEmpty()
                    .split('&').filterNot { it.substringBefore('=') == "range" }.joinToString("&")).apply {
                    appendQueryParameter("range", "$currentChunkStart-$currentChunkEnd")
                }.build()
                val queryRangeSpec = spec.buildUpon()
                    .setUri(uri)
                    .setPosition(0)
                    .setLength(C.LENGTH_UNSET.toLong())
                    .build()
                upstream.open(queryRangeSpec)
            }
            return if (totalContentLength != C.LENGTH_UNSET.toLong()) totalContentLength else C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!isYouTubeStream) return upstream.read(buffer, offset, length)

            val bytesRead = upstream.read(buffer, offset, length)
            if (bytesRead == C.RESULT_END_OF_INPUT) {
                val chunkBytesReceived = bytesReadInChunk
                upstream.close()

                if (chunkBytesReceived < (currentChunkEnd - currentChunkStart + 1)) {
                    return C.RESULT_END_OF_INPUT
                }

                currentChunkStart += chunkBytesReceived
                if (totalContentLength != C.LENGTH_UNSET.toLong()) {
                    totalContentLength -= chunkBytesReceived
                    if (totalContentLength <= 0) return C.RESULT_END_OF_INPUT
                }

                // Count each chunk's first read; never hide network failures as EOF.
                openNextChunk()
                return read(buffer, offset, length)
            }

            bytesReadInChunk += bytesRead
            return bytesRead
        }

        override fun getUri(): Uri? = upstream.uri

        override fun close() {
            upstream.close()
            originalDataSpec = null
        }
    }
}
