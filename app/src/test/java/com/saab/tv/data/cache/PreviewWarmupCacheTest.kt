package com.saab.tv.data.cache

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PreviewWarmupCacheTest {
    @Test fun startWatchingReusesInFlightTrailerLookup() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val cache = PreviewWarmupCache<String>(scope, { 0L })
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            cache.prefetch("profile1:movie:tt1") { calls++; gate.await(); "ready" }
            cache.prefetch("profile1:movie:tt1") { calls++; "duplicate" }
            gate.complete(Unit)
            assertEquals("ready", cache.awaitIfPresent("profile1:movie:tt1"))
            assertEquals("ready", cache.awaitIfPresent("profile1:movie:tt1"))
            assertEquals(1, calls)
            assertNull(cache.awaitIfPresent("profile2:movie:tt1"))
        } finally { scope.cancel() }
    }

    @Test fun expiredAndFailedWorkDoesNotBlockFreshLookup() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var now = 0L
            val cache = PreviewWarmupCache<String>(scope, { now }, ttlMs = 100)
            cache.prefetch("old") { "old URL" }
            now = 100
            assertNull(cache.awaitIfPresent("old"))
            cache.prefetch("failed") { error("unavailable") }
            assertNull(cache.awaitIfPresent("failed"))
            cache.prefetch("failed") { "fresh" }
            assertEquals("fresh", cache.awaitIfPresent("failed"))
        } finally { scope.cancel() }
    }

    @Test fun boundedWarmupCancelsOldWork() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val cache = PreviewWarmupCache<String>(scope, { 0L }, capacity = 2)
            val gate = CompletableDeferred<Unit>()
            cache.prefetch("one") { gate.await(); "one" }
            cache.prefetch("two") { "two" }
            cache.prefetch("three") { "three" }
            assertNull(cache.awaitIfPresent("one"))
            assertEquals("two", cache.awaitIfPresent("two"))
            assertEquals("three", cache.awaitIfPresent("three"))
        } finally { scope.cancel() }
    }
}
