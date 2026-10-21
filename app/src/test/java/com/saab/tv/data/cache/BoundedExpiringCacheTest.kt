package com.saab.tv.data.cache

import org.junit.Assert.*
import org.junit.Test

class BoundedExpiringCacheTest {
    @Test
    fun readsRefreshLruOrderAndCapacityEvictsLeastRecentlyUsed() {
        var now = 0L
        val cache = BoundedExpiringCache<String, Int>(2, 100, { now })
        cache["a"] = 1
        cache["b"] = 2
        assertEquals(1, cache["a"])
        cache["c"] = 3

        assertNull(cache["b"])
        assertEquals(1, cache["a"])
        assertEquals(3, cache["c"])
        assertEquals(2, cache.size())
        now = 100
        assertNull(cache["a"])
        assertNull(cache["c"])
        assertEquals(0, cache.size())
    }

    @Test
    fun overwriteResetsTtlAndClockRollbackDoesNotExpireFreshEntries() {
        var now = 50L
        val cache = BoundedExpiringCache<String, String>(1, 10, { now })
        cache["key"] = "old"
        now = 55
        cache["key"] = "new"
        now = 54
        assertEquals("new", cache["key"])
        now = 65
        assertNull(cache["key"])
    }

    @Test
    fun removeClearAndEmptyReadsAreSafe() {
        var now = 0L
        val cache = BoundedExpiringCache<Int, String>(2, 10, { now })
        assertNull(cache[1])
        assertNull(cache.remove(1))
        cache[1] = "one"
        assertEquals("one", cache.remove(1))
        cache[2] = "two"
        cache.clear()
        assertEquals(0, cache.size())
        assertNull(cache[2])
        now = 20
        assertEquals(0, cache.size())
    }

    @Test
    fun invalidCapacityAndTtlAreRejected() {
        try {
            BoundedExpiringCache<String, String>(0, 1, { 0 })
            fail("zero capacity should be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        try {
            BoundedExpiringCache<String, String>(1, 0, { 0 })
            fail("zero TTL should be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
