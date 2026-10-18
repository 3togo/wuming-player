package com.wuming.tv.core.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import java.util.function.ToLongFunction

class ExpiringLruCacheTest {
    @Test fun consumeRemovesLiveEntriesAndRejectsExpiredOnes() {
        val clock = AtomicLong()
        val cache = ExpiringLruCache<String, String>(2, 100, clock::get)
        cache.put("live", "value")
        assertEquals("value", cache.remove("live"))
        assertNull(cache.remove("live"))
        cache.put("expired", "old")
        clock.set(100)
        assertNull(cache.remove("expired"))
    }

    @Test fun readsUpdateRecencyButDoNotExtendExpiry() {
        val clock = AtomicLong()
        val cache = ExpiringLruCache<String, String>(2, 100, clock::get)
        cache.put("a", "A")
        cache.put("b", "B")
        clock.set(50)
        assertEquals("A", cache.get("a"))
        cache.put("c", "C")
        assertNull(cache.get("b"))
        clock.set(100)
        assertNull(cache.get("a"))
        assertEquals("C", cache.get("c"))
    }

    @Test fun expiredRecentlyReadEntriesAreRemovedBeforeEvictingLiveOnes() {
        val clock = AtomicLong()
        val cache = ExpiringLruCache<String, String>(2, 100, clock::get)
        cache.put("old", "old")
        clock.set(50)
        cache.put("live", "live")
        cache.get("old")
        clock.set(100)
        cache.put("new", "new")
        assertEquals("live", cache.get("live"))
        assertNull(cache.get("old"))
        cache.clear()
        assertNull(cache.get("new"))
    }

    @Test fun replacementStartsANewExpiryWindow() {
        val clock = AtomicLong()
        val cache = ExpiringLruCache<String, String>(1, 100, clock::get)
        cache.put("key", "old")
        clock.set(80)
        cache.put("key", "new")
        clock.set(100)
        cache.trim()
        assertEquals("new", cache.get("key"))
        clock.set(180)
        cache.trim()
        assertNull(cache.get("key"))
    }

    @Test fun weightBudgetEvictsEldestEntries() {
        val clock = AtomicLong()
        val cache = ExpiringLruCache<String, String>(
            10, 100, clock::get, ToLongFunction { it.length.toLong() }, 10)
        cache.put("a", "abcd")
        cache.put("b", "abcd")
        assertEquals(8L, cache.totalWeight())
        clock.set(10)
        cache.put("c", "abcd")
        assertNull(cache.get("a"))
        assertEquals("abcd", cache.get("b"))
        assertEquals("abcd", cache.get("c"))
        assertEquals(8L, cache.totalWeight())
    }

    @Test fun oversizedValueIsNotRetained() {
        val clock = AtomicLong()
        val cache = ExpiringLruCache<String, String>(
            10, 100, clock::get, ToLongFunction { it.length.toLong() }, 4)
        cache.put("big", "abcdefgh")
        assertNull(cache.get("big"))
        assertEquals(0, cache.size())
        assertEquals(0L, cache.totalWeight())
        cache.put("kept", "abc")
        cache.put("big", "abcdefgh")
        assertEquals("abc", cache.get("kept"))
        assertEquals(1, cache.size())
        assertEquals(3L, cache.totalWeight())
    }

    @Test fun trimmingExpiredEntriesClearsTheirWeight() {
        val clock = AtomicLong()
        val cache = ExpiringLruCache<String, String>(
            10, 100, clock::get, ToLongFunction { it.length.toLong() }, 10)
        cache.put("a", "abc")
        clock.set(100)
        cache.trim()
        assertEquals(0L, cache.totalWeight())
        cache.put("b", "def")
        assertEquals("def", cache.get("b"))
    }

    @Test fun replacementDoesNotDoubleCountWeight() {
        val clock = AtomicLong()
        val cache = ExpiringLruCache<String, String>(
            10, 100, clock::get, ToLongFunction { it.length.toLong() }, 10)
        cache.put("a", "abc")
        cache.put("a", "ab")
        assertEquals(1, cache.size())
        assertEquals(2L, cache.totalWeight())
    }

    @Test fun clockRollbackExpiresEntriesInsteadOfKeepingThemIndefinitely() {
        val clock = AtomicLong(100)
        val cache = ExpiringLruCache<String, String>(1, 100, clock::get)
        cache.put("key", "value")
        clock.set(99)
        assertNull(cache.get("key"))
    }

    @Test fun negativeWeightsAreRejectedWithoutCorruptingExistingAccounting() {
        val clock = AtomicLong()
        val cache = ExpiringLruCache<String, String>(
            2, 100, clock::get, ToLongFunction { value -> if (value == "invalid") -1L else value.length.toLong() }, 10)
        cache.put("key", "old")

        assertThrows(IllegalArgumentException::class.java) { cache.put("key", "invalid") }

        assertEquals("old", cache.get("key"))
        assertEquals(3L, cache.totalWeight())
    }

    @Test fun weightAccountingCannotOverflow() {
        val clock = AtomicLong()
        val cache = ExpiringLruCache<String, String>(
            2, 100, clock::get, ToLongFunction { value -> if (value == "huge") Long.MAX_VALUE - 1L else 2L },
            Long.MAX_VALUE)
        cache.put("first", "huge")
        cache.put("second", "small")

        assertNull(cache.get("first"))
        assertEquals("small", cache.get("second"))
        assertEquals(2L, cache.totalWeight())
    }
}
