package com.saab.tv.data.cache

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.sync.Semaphore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SingleFlightTest {
    @Test fun nullableMissRunsItsLoaderExactlyOnce() = runBlocking {
        val sharedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val singleFlight = SingleFlight<String, String?>(sharedScope)
            var loads = 0

            assertNull(singleFlight.await("not-found") { loads++; null })
            assertEquals(1, loads)
        } finally {
            sharedScope.cancel()
        }
    }

    @Test fun identicalCallersShareWorkAndOneCancellationDoesNotCancelTheOther() = runBlocking {
        val sharedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val singleFlight = SingleFlight<String, Int>(sharedScope)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Int>()
            val loads = AtomicInteger()
            val loader: suspend () -> Int = {
                loads.incrementAndGet()
                started.complete(Unit)
                release.await()
            }

            val cancelledCaller = async(start = CoroutineStart.UNDISPATCHED) {
                singleFlight.await("title/en", loader)
            }
            started.await()
            val survivingCaller = async(start = CoroutineStart.UNDISPATCHED) {
                singleFlight.await("title/en", loader)
            }

            cancelledCaller.cancel()
            cancelledCaller.join()
            assertTrue("shared load should continue for the surviving waiter", !release.isCompleted)
            release.complete(42)

            assertEquals(42, survivingCaller.await())
            assertEquals("identical consumers should issue one load", 1, loads.get())
        } finally {
            sharedScope.cancel()
        }
    }

    @Test fun completedAndFailedLoadsAreRemovedSoLaterCallCanRetry() = runBlocking {
        val sharedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val singleFlight = SingleFlight<String, Int>(sharedScope)
            var attempts = 0
            try {
                singleFlight.await("retry") {
                    attempts++
                    error("temporary failure")
                }
            } catch (_: IllegalStateException) { }

            assertEquals(7, singleFlight.await("retry") {
                attempts++
                7
            })
            assertEquals(2, attempts)
        } finally {
            sharedScope.cancel()
        }
    }

    @Test fun separateFlightGroupsCanShareOneGlobalConcurrencyLimit() = runBlocking {
        val sharedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val permits = Semaphore(2)
            val first = SingleFlight<String, Int>(sharedScope, sharedPermits = permits)
            val second = SingleFlight<String, Int>(sharedScope, sharedPermits = permits)
            val active = AtomicInteger()
            val peak = AtomicInteger()
            val jobs = (0 until 8).map { index ->
                async(Dispatchers.Default) {
                    val flight = if (index % 2 == 0) first else second
                    flight.await("key-$index") {
                        val running = active.incrementAndGet()
                        peak.updateAndGet { maxOf(it, running) }
                        try {
                            delay(10)
                            index
                        } finally {
                            active.decrementAndGet()
                        }
                    }
                }
            }

            assertEquals((0 until 8).toSet(), jobs.awaitAll().toSet())
            assertTrue("all request groups must obey the shared cap", peak.get() <= 2)
            assertTrue("at least one request should run", peak.get() > 0)
        } finally {
            sharedScope.cancel()
        }
    }

    @Test fun capacityOverflowStillUsesTheSharedConcurrencyPermit() = runBlocking {
        val sharedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val permits = Semaphore(1)
            val flights = SingleFlight<String, Int>(sharedScope, capacity = 1, sharedPermits = permits)
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val active = AtomicInteger()
            val peak = AtomicInteger()
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                flights.await("retained") {
                    val running = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, running) }
                    try {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                        1
                    } finally { active.decrementAndGet() }
                }
            }
            firstStarted.await()
            // Start inline so await() observes the full registry before the first
            // permit is released; the overflow branch then suspends at the semaphore.
            val overflow = async(start = CoroutineStart.UNDISPATCHED) {
                flights.await("overflow") {
                    val running = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, running) }
                    try { 2 } finally { active.decrementAndGet() }
                }
            }
            releaseFirst.complete(Unit)

            assertEquals(1, first.await())
            assertEquals(2, overflow.await())
            assertEquals("capacity overflow must not bypass the shared permit", 1, peak.get())
        } finally {
            sharedScope.cancel()
        }
    }
}
