package com.wuming.tv.core.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch

class BoundedReferenceCacheTest {

    @Test
    fun evictsOnlyLeastRecentlyUsedEntry() {
        val cache = BoundedReferenceCache<String, String>(2)
        cache.put("first", "1")
        cache.put("second", "2")

        assertEquals("1", cache.get("first"))
        cache.put("third", "3")

        assertEquals("1", cache.get("first"))
        assertNull(cache.get("second"))
        assertEquals("3", cache.get("third"))
        assertEquals(2, cache.size())
    }

    @Test
    fun staysBoundedDuringConcurrentWrites() {
        val threads = 8
        val writesPerThread = 500
        val cache = BoundedReferenceCache<String, Int>(64)
        val start = CountDownLatch(1)
        val workers = mutableListOf<Thread>()
        for (thread in 0 until threads) {
            val worker = thread
            val item = Thread {
                try {
                    start.await()
                    for (index in 0 until writesPerThread) {
                        val key = "$worker:$index"
                        cache.put(key, index)
                        cache.get(key)
                    }
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            workers.add(item)
            item.start()
        }

        start.countDown()
        for (worker in workers) worker.join()

        assertEquals(64, cache.size())
    }

    @Test
    fun supportsTargetedAndConditionalRemoval() {
        val cache = BoundedReferenceCache<String, String>(4)
        cache.put("quark:one", "1")
        cache.put("quark:two", "2")
        cache.put("uc:one", "3")

        assertFalse(cache.remove("quark:one", "wrong"))
        assertTrue(cache.remove("quark:one", "1"))
        cache.removeKeysIf { key -> key.startsWith("quark:") }

        assertNull(cache.get("quark:one"))
        assertNull(cache.get("quark:two"))
        assertEquals("3", cache.get("uc:one"))
    }

    @Test
    fun sweepsMatchingEntriesAndReportsWhetherEmpty() {
        val cache = BoundedReferenceCache<String, Int>(4)
        cache.put("recent", 10)
        cache.put("expired", 1)

        assertFalse(cache.isEmpty())
        cache.removeEntriesIf { _, age -> age < 5 }

        assertEquals(1, cache.size())
        assertNull(cache.get("expired"))
        cache.removeEntriesIf { _, _ -> true }
        assertTrue(cache.isEmpty())
    }

    @Test
    fun removesOnlyWhileTheStoredValueStillMatches() {
        val cache = BoundedReferenceCache<String, Int>(4)
        val value = 500
        cache.put("boxed", value)

        assertFalse(cache.remove("boxed", 7))
        // An equal but distinct box matches too: the guard is identity-or-equality.
        assertTrue(cache.remove("boxed", Integer.valueOf(500)))
        assertNull(cache.get("boxed"))
    }

    @Test
    fun rejectsNonPositiveCapacity() {
        for (capacity in intArrayOf(0, -1)) {
            try {
                BoundedReferenceCache<String, String>(capacity)
                fail("capacity $capacity must be rejected")
            } catch (expected: IllegalArgumentException) {
                assertEquals("maxEntries must be positive", expected.message)
            }
        }
    }
}
