package com.wuming.tv.core.cache

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
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

    @Test fun clearReleasesWaitersAndOldFailureCannotRemoveReplacementWork() {
        val cache = MemoryResultCache<String, String>(2, Duration.ofMinutes(1))
        val oldStarted = CountDownLatch(1)
        val oldRelease = CountDownLatch(1)
        val newStarted = CountDownLatch(1)
        val newRelease = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(3)
        val waiterThread = AtomicReference<Thread>()
        try {
            val old = pool.submit<String> { cache.get("key") {
                oldStarted.countDown()
                check(oldRelease.await(5, TimeUnit.SECONDS))
                error("obsolete failure")
            } }
            assertTrue(oldStarted.await(5, TimeUnit.SECONDS))
            val waiter = pool.submit<String> {
                waiterThread.set(Thread.currentThread())
                cache.get("key") { error("must share old request") }
            }
            awaitWaiting(waiterThread)
            cache.clear()
            assertTrue(assertThrows(ExecutionException::class.java) {
                waiter.get(5, TimeUnit.SECONDS)
            }.cause is CancellationException)

            val replacement = pool.submit<String> { cache.get("key") {
                newStarted.countDown()
                check(newRelease.await(5, TimeUnit.SECONDS))
                "new"
            } }
            assertTrue(newStarted.await(5, TimeUnit.SECONDS))
            oldRelease.countDown()
            assertEquals("obsolete failure", assertThrows(ExecutionException::class.java) {
                old.get(5, TimeUnit.SECONDS)
            }.cause?.message)

            waiterThread.set(null)
            val newWaiter = pool.submit<String> {
                waiterThread.set(Thread.currentThread())
                cache.get("key") { error("old cleanup removed replacement") }
            }
            awaitWaiting(waiterThread)
            newRelease.countDown()
            assertEquals("new", replacement.get(5, TimeUnit.SECONDS))
            assertEquals("new", newWaiter.get(5, TimeUnit.SECONDS))
        } finally {
            oldRelease.countDown()
            newRelease.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun failuresReachWaitersWithoutWrappingAndCanBeRetried() {
        val cache = MemoryResultCache<String, String>(2, Duration.ofMinutes(1))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = IllegalArgumentException("provider failed")
        val waiterThread = AtomicReference<Thread>()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val owner = pool.submit<String> { cache.get("key") {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                throw failure
            } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val waiter = pool.submit<String> {
                waiterThread.set(Thread.currentThread())
                cache.get("key") { error("duplicate request") }
            }
            awaitWaiting(waiterThread)
            release.countDown()
            for (result in listOf(owner, waiter)) {
                assertSame(failure, assertThrows(ExecutionException::class.java) {
                    result.get(5, TimeUnit.SECONDS)
                }.cause)
            }
            assertEquals("recovered", cache.get("key") { "recovered" })
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun differentKeysDoNotBlockAndExpiryStartsWhenComputationFinishes() {
        val clock = MutableCacheClock()
        val cache = MemoryResultCache<String, String>(2, Duration.ofSeconds(10), clock)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val slow = pool.submit<String> { cache.get("slow") {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                "finished"
            } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertEquals("independent", cache.get("other") { "independent" })
            clock.millisNow += 60_000
            release.countDown()
            assertEquals("finished", slow.get(5, TimeUnit.SECONDS))
            clock.millisNow += 9_999
            assertEquals("finished", cache.get("slow") { error("expired too early") })
            clock.millisNow++
            assertEquals("refreshed", cache.get("slow") { "refreshed" })
            clock.millisNow--
            assertEquals("clock reset", cache.get("slow") { "clock reset" })
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun nullableResultsAreCachedAndClearInvalidatesCompletedEntries() {
        val cache = MemoryResultCache<String, String?>(2, Duration.ofMinutes(1))
        assertNull(cache.get("key") { null })
        assertNull(cache.get("key") { error("null was not cached") })
        cache.clear()
        assertEquals("fresh", cache.get("key") { "fresh" })
    }

    private fun awaitWaiting(thread: AtomicReference<Thread>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (thread.get()?.state == Thread.State.WAITING) return
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
        }
        fail("Cache caller did not join the in-flight request")
    }
}

private class MutableCacheClock(var millisNow: Long = 1_800_000_000_000) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(millisNow)
}
