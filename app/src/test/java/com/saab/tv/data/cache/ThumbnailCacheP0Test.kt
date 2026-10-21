package com.saab.tv.data.cache

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import java.io.File
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
            targets.forEach { assertEquals(ThumbnailStoreResult.STORED, cache.store(1,id,it,interval,bitmap())) }
            val p = cache.progress(1,id,300_000,interval)
            assertEquals(targets.size,p.cachedFrames); assertEquals(100,p.percent)
        }
    }
    @Test fun duplicateFrameDoesNotInflateProgressAndInputBitmapIsRecycled() = runBlocking {
        val first = bitmap(); assertEquals(ThumbnailStoreResult.STORED, cache.store(1,"tt1",0,30,first)); assertTrue(first.isRecycled)
        val duplicate = bitmap(); assertEquals(ThumbnailStoreResult.DUPLICATE, cache.store(1,"tt1",0,30,duplicate)); assertTrue(duplicate.isRecycled)
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
        val input = bitmap(); assertEquals(ThumbnailStoreResult.INVALID, cache.store(0,"",0,30,input)); assertTrue(input.isRecycled)
        assertNull(cache.loadNearest(0,"",0,30)); assertEquals(0,cache.progress(1,"tt1",0,30).percent)
    }

    @Test fun activeTimelineIsPinnedWhileOldOtherContentIsEvicted() = runBlocking {
        cache = SeekThumbnailCache(RuntimeEnvironment.getApplication(), 32_768, 16_384)
        val workingSet = cache.beginWorkingSet(1, "active-title")
        assertTrue(workingSet.admitted)

        assertEquals(ThumbnailStoreResult.STORED, cache.store(1, "active-title", 0, 30, bitmap()))
        assertEquals(ThumbnailStoreResult.STORED, cache.store(1, "active-title", 30_000, 30, bitmap()))
        repeat(48) { index ->
            assertEquals(
                ThumbnailStoreResult.STORED,
                cache.store(2, "other-$index", 0, 30, bitmap())
            )
        }

        assertTrue(cache.contains(1, "active-title", 0, 30))
        assertTrue(cache.contains(1, "active-title", 30_000, 30))
        val files = File(RuntimeEnvironment.getApplication().cacheDir, "seek_thumbnails_v5")
            .walkTopDown().filter { it.isFile && it.extension == "webp" }.toList()
        assertTrue(files.sumOf(File::length) <= 32_768)

        cache.endWorkingSet(1, "active-title")
        val afterRelease = File(RuntimeEnvironment.getApplication().cacheDir, "seek_thumbnails_v5")
            .walkTopDown().filter { it.isFile && it.extension == "webp" }.sumOf(File::length)
        assertTrue(afterRelease <= 32_768)
    }

    @Test fun activeTimelineRemainsPinnedAcrossIndependentCacheInstances() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val owner = SeekThumbnailCache(app, 32_768, 16_384)
        val trimmer = SeekThumbnailCache(app, 32_768, 16_384)
        assertTrue(owner.beginWorkingSet(1, "cross-process-active").admitted)
        assertEquals(ThumbnailStoreResult.STORED, owner.store(1, "cross-process-active", 0, 30, bitmap()))
        assertEquals(ThumbnailStoreResult.STORED, owner.store(1, "cross-process-active", 30_000, 30, bitmap()))
        assertFalse(trimmer.beginWorkingSet(1, "cross-process-active").admitted)

        repeat(50) { index ->
            assertEquals(
                ThumbnailStoreResult.STORED,
                trimmer.store(2, "cross-process-other-$index", 0, 30, bitmap())
            )
        }

        assertTrue(owner.contains(1, "cross-process-active", 0, 30))
        assertTrue(owner.contains(1, "cross-process-active", 30_000, 30))
        owner.endWorkingSet(1, "cross-process-active")
    }

    @Test fun nestedWorkingSetLeasesReleaseOnlyAfterTheLastOwnerEnds() = runBlocking {
        cache = SeekThumbnailCache(RuntimeEnvironment.getApplication(), 4_096, 1)
        assertTrue(cache.beginWorkingSet(1, "nested-active").admitted)
        assertTrue(cache.beginWorkingSet(1, "nested-active").admitted)

        cache.endWorkingSet(1, "nested-active")
        assertEquals(
            ThumbnailStoreResult.CAPACITY_EXCEEDED,
            cache.store(1, "nested-active", 0, 30, bitmap())
        )
        cache.endWorkingSet(1, "nested-active")
        assertEquals(ThumbnailStoreResult.STORED, cache.store(1, "nested-active", 0, 30, bitmap()))
    }

    @Test fun oversizedActiveTimelineReportsCapacityWithoutLeavingPartialFile() = runBlocking {
        cache = SeekThumbnailCache(RuntimeEnvironment.getApplication(), 4, 1)
        assertTrue(cache.beginWorkingSet(1, "large-title").admitted)
        val frame = bitmap()
        assertEquals(
            ThumbnailStoreResult.CAPACITY_EXCEEDED,
            cache.store(1, "large-title", 0, 30, frame)
        )
        assertTrue(frame.isRecycled)
        assertFalse(cache.contains(1, "large-title", 0, 30))
        val leftovers = File(RuntimeEnvironment.getApplication().cacheDir, "seek_thumbnails_v5")
            .walkTopDown().filter { it.isFile && it.extension in setOf("webp", "tmp") }.toList()
        assertTrue(leftovers.isEmpty())
        cache.endWorkingSet(1, "large-title")
    }

    @Test fun existingTimelineAboveWorkingSetLimitIsPreservedButNotAdmitted() = runBlocking {
        cache = SeekThumbnailCache(RuntimeEnvironment.getApplication(), 65_536, 1_024)
        assertEquals(ThumbnailStoreResult.STORED, cache.store(1, "preexisting", 0, 30, bitmap()))

        val status = cache.beginWorkingSet(1, "preexisting")

        assertFalse(status.admitted)
        assertTrue(status.currentBytes > status.limitBytes)
        assertTrue(cache.contains(1, "preexisting", 0, 30))
    }

    @Test fun invalidWorkingSetIdentityIsRejectedWithoutAdmittingAnyPath() = runBlocking {
        cache = SeekThumbnailCache(RuntimeEnvironment.getApplication(), 4_096, 1)

        val status = cache.beginWorkingSet(0, "")

        assertFalse(status.admitted)
        assertEquals(0L, status.currentBytes)
        assertEquals(1L, status.limitBytes)
    }

    @Test fun cancelledWorkerStillReleasesItsPinnedWorkingSet() = runBlocking {
        cache = SeekThumbnailCache(RuntimeEnvironment.getApplication(), 4_096, 1)
        assertTrue(cache.beginWorkingSet(1, "cancelled-title").admitted)

        val started = CompletableDeferred<Unit>()
        val worker = launch(Job()) {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                cache.endWorkingSet(1, "cancelled-title")
            }
        }
        started.await()
        worker.cancelAndJoin()

        assertEquals(
            ThumbnailStoreResult.STORED,
            cache.store(1, "cancelled-title", 0, 30, bitmap())
        )
    }

    @Test fun decodedFramePreservesDimensionsAndRedBlueChannels() = runBlocking {
        cache.store(1,"tt1",0,30,bitmap())
        val result = cache.loadNearest(1,"tt1",0,30)!!
        assertEquals(320,result.width); assertEquals(180,result.height)
        val color = result.getPixel(160,90)
        assertTrue(Color.red(color)>Color.blue(color)); result.recycle()
    }
}
