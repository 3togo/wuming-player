package com.wuming.tv.desktop.cache

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class MemoryResultCacheTest {
    @Test fun expiryLruAndFailures() {
        val clock = MutableCacheClock()
        val cache = MemoryResultCache<String, String>(2, Duration.ofSeconds(10), clock)
        var calls = 0
        fun get(key: String) = cache.get(key) { calls++; key }
        get("a"); get("b"); get("a"); get("c"); get("b")
        assertEquals(4, calls)
        clock.millisNow += 10_000
        get("b")
        assertEquals(5, calls)
        assertThrows(IllegalStateException::class.java) { cache.get("error") { error("failed") } }
        assertEquals("ok", cache.get("error") { "ok" })
    }

    @Test fun concurrentCallersComputeOnlyOnce() {
        val cache = MemoryResultCache<String, String>(2, Duration.ofMinutes(1))
        val started = CountDownLatch(1)
        val joined = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<String> { cache.get("a") {
                calls.incrementAndGet(); started.countDown(); check(release.await(5, TimeUnit.SECONDS)); "shared"
            } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val second = pool.submit<String> { joined.countDown(); cache.get("a") { calls.incrementAndGet(); "duplicate" } }
            assertTrue(joined.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertEquals("shared", first.get(5, TimeUnit.SECONDS))
            assertEquals("shared", second.get(5, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun clearFencesOldWriter() {
        val cache = MemoryResultCache<String, String>(2, Duration.ofMinutes(1))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<String> { cache.get("a") { started.countDown(); check(release.await(5, TimeUnit.SECONDS)); "old" } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            cache.clear()
            assertEquals("new", cache.get("a") { "new" })
            release.countDown()
            assertEquals("old", first.get(5, TimeUnit.SECONDS))
            assertEquals("new", cache.get("a") { error("old writer replaced new result") })
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
