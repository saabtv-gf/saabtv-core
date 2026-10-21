package com.saab.tv.data.cache

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Coalesces identical active loads while bounding retained keys and concurrent work. */
internal class SingleFlight<K : Any, V>(
    private val scope: CoroutineScope,
    private val capacity: Int = 96,
    maxConcurrent: Int = 4,
    sharedPermits: Semaphore? = null
) {
    private val lock = Any()
    private val flights = LinkedHashMap<K, Deferred<V>>()
    private val permits = sharedPermits ?: Semaphore(maxConcurrent.coerceAtLeast(1))

    suspend fun await(key: K, load: suspend () -> V): V {
        val flight = synchronized(lock) {
            val current = flights[key]
            if (current != null && !current.isCompleted) {
                current
            } else {
                // Completion callbacks run after the deferred enters the completed state.
                // A caller can arrive in that small window and otherwise reuse a stale
                // null/failure result instead of retrying the load.
                if (current != null) flights.remove(key)
                if (flights.size < capacity.coerceAtLeast(1)) {
                    scope.async(start = CoroutineStart.LAZY) { permits.withPermit { load() } }
                        .also { deferred ->
                            flights[key] = deferred
                            deferred.invokeOnCompletion {
                                synchronized(lock) {
                                    if (flights[key] === deferred) flights.remove(key)
                                }
                            }
                            deferred.start()
                        }
                } else null
            }
        }
        // Under saturation, keep the registry bounded and still respect the same concurrency
        // limit. Normal identical keys are coalesced; overflow callers don't grow retained state.
        // Do not use Elvis here: V itself may be nullable, and a shared `null` result
        // must not fall through and issue a second network request.
        return if (flight == null) permits.withPermit { load() } else flight.await()
    }
}

/** Process-owned scope for shared reads that must outlive an individual screen collector. */
internal fun metadataRequestScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)
