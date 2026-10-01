package com.saab.tv.data.trailer

import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TrailerUrlVerifierTest {
    @Test fun rejectedFourKCannotOutrankReadableVideoAndSharedAudioIsProbedOnce() = runBlocking {
        val audioRequests = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("bytes=0-1023", request.getHeader("Range"))
                if (request.path == "/audio") audioRequests.incrementAndGet()
                return MockResponse().setResponseCode(if (request.path == "/4k") 403 else 206).setBody("data")
            }
        }
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val variants = listOf(TrailerPlaybackVariant("$base/4k", "$base/audio", height = 2160),
                TrailerPlaybackVariant("$base/1080", "$base/audio", height = 1080),
                TrailerPlaybackVariant("$base/720", "$base/audio", height = 720))
            val result = TrailerUrlVerifier.playable(variants)
            assertEquals(listOf(1080, 720), result.map { it.height })
            assertEquals(1, audioRequests.get())
        } finally { server.shutdown() }
    }

    @Test fun readableVideoWithRejectedAudioCannotBeSelected() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(if (request.path == "/audio") 403 else 206).setBody("data")
        }
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            assertTrue(TrailerUrlVerifier.playable(listOf(TrailerPlaybackVariant("$base/video", "$base/audio"))).isEmpty())
        } finally { server.shutdown() }
    }
}
