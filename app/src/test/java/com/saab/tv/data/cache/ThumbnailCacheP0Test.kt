package com.saab.tv.data.cache

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ThumbnailCacheP0Test {
    private lateinit var cache: SeekThumbnailCache
    @Before fun setup() { cache = SeekThumbnailCache(RuntimeEnvironment.getApplication()) }
    private fun bitmap() = Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
    @Test fun firstFiveMinuteGridStoresAndReportsEveryInterval() = runBlocking {
        for(interval in listOf(10,20,30)) {
            val id = "tt$interval"
            val targets = SeekThumbnailCache.storyboardTargets(300_000,interval)
            targets.forEach { assertTrue(cache.store(1,id,it,interval,bitmap())) }
            val p = cache.progress(1,id,300_000,interval)
            assertEquals(targets.size,p.cachedFrames); assertEquals(100,p.percent)
        }
    }
    @Test fun duplicateFrameDoesNotInflateProgressAndInputBitmapIsRecycled() = runBlocking {
        val first = bitmap(); assertTrue(cache.store(1,"tt1",0,30,first)); assertTrue(first.isRecycled)
        val duplicate = bitmap(); assertFalse(cache.store(1,"tt1",0,30,duplicate)); assertTrue(duplicate.isRecycled)
        assertEquals(1,cache.progress(1,"tt1",90_000,30).cachedFrames)
    }
    @Test fun missingSeekPositionNeverShowsAnUnrelatedEarlierFrame() = runBlocking {
        cache.store(1,"tt1",0,30,bitmap())
        assertNull(cache.loadNearest(1,"tt1",60_000,30))
        val present = cache.loadNearest(1,"tt1",1_000,30)
        assertNotNull(present); present?.recycle()
        Unit
    }
    @Test fun clearContentAndClearProfileRespectOtherProfiles() = runBlocking {
        listOf(1,2).forEach { p -> listOf("tt1","tt2").forEach { cache.store(p,it,0,30,bitmap()) } }
        cache.clearContent(1,"tt1"); assertFalse(cache.contains(1,"tt1",0,30)); assertTrue(cache.contains(1,"tt2",0,30))
        cache.clearProfile(1); assertFalse(cache.contains(1,"tt2",0,30)); assertTrue(cache.contains(2,"tt1",0,30))
    }
    @Test fun invalidIdentityIsRejectedAndOwnedBitmapStillRecycled() = runBlocking {
        val input = bitmap(); assertFalse(cache.store(0,"",0,30,input)); assertTrue(input.isRecycled)
        assertNull(cache.loadNearest(0,"",0,30)); assertEquals(0,cache.progress(1,"tt1",0,30).percent)
    }
    @Test fun decodedFramePreservesDimensionsAndRedBlueChannels() = runBlocking {
        cache.store(1,"tt1",0,30,bitmap())
        val result = cache.loadNearest(1,"tt1",0,30)!!
        assertEquals(320,result.width); assertEquals(180,result.height)
        val color = result.getPixel(160,90)
        assertTrue(Color.red(color)>Color.blue(color)); result.recycle()
    }
}
