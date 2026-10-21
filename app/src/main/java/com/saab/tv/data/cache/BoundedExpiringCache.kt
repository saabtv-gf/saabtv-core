package com.saab.tv.data.cache

/** Small synchronized LRU with TTL; suitable for metadata observations, not durable user state. */
internal class BoundedExpiringCache<K : Any, V : Any>(
    private val capacity: Int,
    private val ttlMillis: Long,
    private val nowMillis: () -> Long
) {
    private data class Entry<V>(val value: V, val storedAtMillis: Long)

    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(ttlMillis > 0L) { "ttlMillis must be positive" }
    }

    @Synchronized
    operator fun get(key: K): V? {
        pruneExpired()
        return entries[key]?.value
    }

    @Synchronized
    operator fun set(key: K, value: V) {
        pruneExpired()
        entries[key] = Entry(value, nowMillis())
        while (entries.size > capacity) {
            val eldest = entries.entries.iterator()
            eldest.next()
            eldest.remove()
        }
    }

    @Synchronized
    fun remove(key: K): V? {
        pruneExpired()
        return entries.remove(key)?.value
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun size(): Int {
        pruneExpired()
        return entries.size
    }

    private fun pruneExpired() {
        val now = nowMillis()
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            val age = now - iterator.next().storedAtMillis
            if (age >= ttlMillis) iterator.remove()
        }
    }
}
