package com.saab.tv.data.cache

import kotlinx.coroutines.*

/** Bounded, short-lived single-flight work that survives a trailer being dismissed. */
internal class PreviewWarmupCache<T>(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val ttlMs: Long = 60_000,
    private val capacity: Int = 2
) {
    private data class Entry<T>(val created: Long, val work: Deferred<T>)
    private val entries = LinkedHashMap<String, Entry<T>>()

    @Synchronized
    fun prefetch(key: String, loader: suspend () -> T) {
        prune()
        if (entries.containsKey(key)) return
        while (entries.size >= capacity) {
            val oldest = entries.keys.first()
            entries.remove(oldest)?.work?.cancel()
        }
        val work = scope.async(start = CoroutineStart.LAZY) { loader() }
        entries[key] = Entry(now(), work)
        work.start()
    }

    suspend fun awaitIfPresent(key: String): T? {
        val work = synchronized(this) { prune(); entries[key]?.work } ?: return null
        return try { work.await() }
        catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            discard(key, work)
            null
        } catch (_: Exception) { discard(key, work); null }
    }

    @Synchronized
    private fun discard(key: String, work: Deferred<T>) {
        if (entries[key]?.work === work) entries.remove(key)
    }

    private fun prune() {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (now() - entry.created >= ttlMs || entry.work.isCancelled) {
                entry.work.cancel()
                iterator.remove()
            }
        }
    }
}
