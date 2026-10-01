package com.saab.tv.data.trailer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in public-video network QA. Normal CI never depends on YouTube being up. */
class MaintainedYoutubeResolverSmokeTest {
    @Test fun reportedTrailerHasReadableNativeMedia() = runBlocking {
        assumeTrue(System.getenv("SAABTV_TRAILER_SMOKE") == "1")
        val variants = MaintainedYoutubeResolver.resolve("QftAW9TTmuQ")
        println("maintainedVariants=${variants.size}")
        assertTrue("Maintained extractor returned no streams", variants.isNotEmpty())
        val readable = TrailerUrlVerifier.playable(TrailerPolicy.rank(variants).take(16))
        println("readableVariants=${readable.size} heights=${readable.map { it.height }.distinct()}")
        assertTrue("All extracted CDN streams were rejected", readable.isNotEmpty())
    }
}
