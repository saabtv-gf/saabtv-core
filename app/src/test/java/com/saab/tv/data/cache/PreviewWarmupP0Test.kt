package com.saab.tv.data.cache

import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*

class PreviewWarmupP0Test {
    @Test fun repeatedPrefetchSharesOneLoaderAndCompletedResult() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            var calls=0; val gate=CompletableDeferred<Unit>(); val cache=PreviewWarmupCache<Int>(scope,{0})
            repeat(10) { cache.prefetch("title") { calls++; gate.await(); 42 } }
            assertEquals(1,calls); gate.complete(Unit)
            assertEquals(42,cache.awaitIfPresent("title")); assertEquals(42,cache.awaitIfPresent("title"))
        } finally { scope.cancel() }
    }
    @Test fun expiryStartsOnCompletionRatherThanPrefetch() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            var time=0L; val gate=CompletableDeferred<Unit>(); val cache=PreviewWarmupCache<Int>(scope,{time},10)
            cache.prefetch("title") { gate.await(); 7 }; time=100; gate.complete(Unit)
            time=109; assertEquals(7,cache.awaitIfPresent("title"))
            time=110; assertNull(cache.awaitIfPresent("title"))
        } finally { scope.cancel() }
    }
    @Test fun capacityEvictsOldestAndInvalidateCancelsWork() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val gate=CompletableDeferred<Unit>(); val cache=PreviewWarmupCache<Int>(scope,{0},capacity=2)
            cache.prefetch("a") { gate.await(); 1 }; cache.prefetch("b") { 2 }; cache.prefetch("c") { 3 }
            assertNull(cache.awaitIfPresent("a")); assertEquals(2,cache.awaitIfPresent("b"))
            cache.invalidate("b"); assertNull(cache.awaitIfPresent("b")); assertEquals(3,cache.awaitIfPresent("c"))
        } finally { scope.cancel() }
    }
    @Test fun failedLoaderDoesNotPoisonRetry() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val cache=PreviewWarmupCache<Int>(scope,{0}); cache.prefetch("a") { error("provider failure") }
            assertNull(cache.awaitIfPresent("a")); cache.prefetch("a") { 8 }; assertEquals(8,cache.awaitIfPresent("a"))
        } finally { scope.cancel() }
    }
    @Test fun dismissingAwaitingOverlayDoesNotCancelPrefetch() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val gate=CompletableDeferred<Unit>(); val cache=PreviewWarmupCache<Int>(scope,{0})
            cache.prefetch("a") { gate.await(); 9 }
            val overlay=launch(start=CoroutineStart.UNDISPATCHED) { cache.awaitIfPresent("a") }
            overlay.cancelAndJoin(); gate.complete(Unit); assertEquals(9,cache.awaitIfPresent("a"))
        } finally { scope.cancel() }
    }
}
