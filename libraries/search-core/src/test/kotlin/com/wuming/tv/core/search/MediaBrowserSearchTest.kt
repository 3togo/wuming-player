package com.wuming.tv.core.search

import com.wuming.tv.core.cache.ExpiringLruCache
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongSupplier

class MediaBrowserSearchTest {
    private class Fixture(limit: Int = 50, timeout: Long = 1_000) : AutoCloseable {
        val workers = Executors.newFixedThreadPool(4)
        val timers = ScheduledThreadPoolExecutor(1)
        val now = AtomicLong()
        val calls = AtomicInteger()
        var strict = false
        var sources = listOf("one")
        var plan: (String) -> SearchQueryPlan = { SearchQueryPlanner.localOnly().plan(it) }
        var fetch: (String, SearchQueryPlan.Variant, Boolean) -> List<String> = { source, variant, _ -> listOf("$source:${variant.text()}") }
        var convert: (List<String>) -> List<String> = { it.sorted() }
        val cache = ExpiringLruCache<String, List<String>>(2, 100, LongSupplier { now.get() })
        val engine = MediaBrowserSearch(workers, timers, object : MediaBrowserSearch.Host<String, String, String> {
            override fun plan(query: String) = plan.invoke(query)
            override fun strict() = strict
            override fun sources() = sources
            override fun key(source: String) = source
            override fun search(source: String, plan: SearchQueryPlan, variant: SearchQueryPlan.Variant, strict: Boolean): List<String> {
                calls.incrementAndGet()
                return fetch(source, variant, strict)
            }
            override fun merge(items: List<String>) = items.distinct()
            override fun items(results: List<String>, query: String) = convert(results)
        }, cache, timeout, limit)
        override fun close() { engine.clear(); workers.shutdownNow(); timers.shutdownNow() }
    }

    @Test fun anAliasCancellationDoesNotEraseEarlierSuccessInAnActiveRequest() {
        for (failure in listOf(CancellationException("alias cancelled"), InterruptedException("alias interrupted"))) Fixture().use { f ->
            f.plan = { SearchQueryPlan(it, it, listOf(
                SearchQueryPlan.Variant("original", SearchQueryPlan.Kind.ORIGINAL),
                SearchQueryPlan.Variant("alias", SearchQueryPlan.Kind.KNOWN_ALIAS),
            ), null) }
            f.fetch = { _, variant, _ ->
                if (variant.text() == "alias") throw failure
                listOf("found")
            }
            assertEquals(listOf("found"), f.engine.search("query"))
            assertEquals(listOf("found"), f.engine.current())
        }
    }

    @Test fun abandonedProviderCannotStartAnotherAliasAfterClearingItsInterrupt() {
        Fixture(limit = 1).use { f ->
            f.sources = listOf("winner", "abandoned")
            f.plan = { SearchQueryPlan(it, it, listOf(
                SearchQueryPlan.Variant("original", SearchQueryPlan.Kind.ORIGINAL),
                SearchQueryPlan.Variant("alias", SearchQueryPlan.Kind.KNOWN_ALIAS),
            ), null) }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val extraCalls = AtomicInteger()
            f.fetch = { source, variant, _ ->
                if (source == "winner") {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    if (variant.text() == "original") listOf("winner") else emptyList()
                } else if (variant.text() == "original") {
                    entered.countDown()
                    while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                    listOf("late")
                } else {
                    extraCalls.incrementAndGet()
                    listOf("unwanted alias")
                }
            }
            try {
                assertEquals(listOf("winner"), f.engine.search("query"))
                release.countDown()
                f.workers.shutdown()
                assertTrue(f.workers.awaitTermination(5, TimeUnit.SECONDS))
                assertEquals("Abandoned work issued another provider request", 0, extraCalls.get())
                assertEquals(listOf("winner"), f.engine.current())
            } finally { release.countDown() }
        }
    }

    @Test fun cancellingTheReturnedBrowserTaskInvalidatesPublicationEvenIfConversionIgnoresInterrupt() {
        Fixture().use { f ->
            val executor = Executors.newSingleThreadExecutor()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            f.convert = { items ->
                entered.countDown()
                while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                items
            }
            try {
                val request = f.engine.begin()
                val work = f.engine.submit(request, executor) {
                    try { f.engine.search("query", request) } finally { finished.countDown() }
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertTrue(work.cancel(true))
                release.countDown()
                assertTrue(finished.await(5, TimeUnit.SECONDS))
                assertFalse("Cancelled task still owns the request", f.engine.isCurrent(request))
                assertTrue(f.engine.current().isEmpty())
                assertEquals(0, f.cache.size())
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }

    @Test fun cancelWithoutInterruptionDoesNotInterruptTheOwnedProvider() {
        Fixture().use { f ->
            val executor = Executors.newSingleThreadExecutor()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val returned = CountDownLatch(1)
            val interrupted = java.util.concurrent.atomic.AtomicBoolean()
            f.fetch = { _, _, _ ->
                entered.countDown()
                try {
                    while (release.count > 0) try { release.await() } catch (_: InterruptedException) { interrupted.set(true) }
                    listOf("late")
                } finally { returned.countDown() }
            }
            try {
                val request = f.engine.begin()
                val work = f.engine.submit(request, executor) { f.engine.search("query", request) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertTrue(work.cancel(false))
                release.countDown()
                assertTrue(returned.await(5, TimeUnit.SECONDS))
                assertFalse("cancel(false) interrupted a provider", interrupted.get())
                assertFalse(f.engine.isCurrent(request))
                assertTrue(f.engine.current().isEmpty())
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }

    @Test fun aCompletedTaskCannotRevokeItsPublishedRequestAndAnOldTaskCannotCancelItsReplacement() {
        Fixture().use { f ->
            val request = f.engine.begin()
            val completed = f.engine.submit(request, Executor { it.run() }) { f.engine.search("first", request) }
            assertTrue(completed.isDone)
            completed.get(5, TimeUnit.SECONDS)
            assertFalse(completed.cancel(true))
            assertTrue(f.engine.isCurrent(request))
            assertEquals(listOf("one:first"), f.engine.current())
            assertEquals(listOf("one:second"), f.engine.search("second"))
            assertFalse(completed.cancel(true))
            assertEquals(listOf("one:second"), f.engine.current())
        }
    }

    @Test fun laterProvidersFinishingFirstDoNotChangeSourceOrderAtTheCollectionCap() {
        Fixture(limit = 2).use { f ->
            f.sources = listOf("first", "later")
            val laterReturned = CountDownLatch(1)
            f.fetch = { source, _, _ ->
                if (source == "later") { laterReturned.countDown(); listOf("z") }
                else { assertTrue(laterReturned.await(5, TimeUnit.SECONDS)); listOf("b", "a") }
            }
            assertEquals(listOf("a", "b"), f.engine.search("query"))
            assertEquals(listOf("a", "b"), f.engine.search("query"))
            assertEquals(2, f.calls.get())
        }
    }

    @Test fun cacheAccessRetainsLruOrderButDoesNotExtendTheOriginalTtl() {
        Fixture().use { f ->
            f.engine.search("A")
            f.engine.search("B")
            f.engine.search("A")
            assertEquals(2, f.calls.get())
            f.engine.search("C")
            f.engine.search("A")
            assertEquals(3, f.calls.get())
            f.engine.search("B")
            assertEquals("Least-recently-used B should have been evicted", 4, f.calls.get())
            f.now.set(99)
            f.engine.search("A")
            assertEquals(4, f.calls.get())
            f.now.set(100)
            f.engine.search("A")
            assertEquals("TTL is measured from publication, not the latest hit", 5, f.calls.get())
        }
    }

    @Test fun allFailedSourcesKeepTheExistingEmptyResultCacheBehavior() {
        Fixture().use { f ->
            f.fetch = { _, _, _ -> throw IllegalStateException("offline") }
            assertTrue(f.engine.search("query").isEmpty())
            assertTrue(f.engine.search("query").isEmpty())
            assertEquals(1, f.calls.get())
            assertEquals(1, f.cache.size())
        }
    }

    @Test fun lateOldConversionCannotOverwriteOrClearANewerRequestsCache() {
        Fixture().use { f ->
            val executor = Executors.newSingleThreadExecutor()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.convert = { items ->
                if (items == listOf("one:old")) { entered.countDown(); release.await() }
                items
            }
            try {
                val old = executor.submit<List<String>> { f.engine.search("old") }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals(listOf("one:new"), f.engine.search("new"))
                release.countDown()
                assertTrue(old.get(5, TimeUnit.SECONDS).isEmpty())
                assertEquals(listOf("one:new"), f.engine.current())
                assertNull(f.cache.get("old|broad"))
                assertEquals(listOf("one:new"), f.cache.get("new|broad"))
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }

    @Test fun cacheKeepsCanonicalStrictIdentityExpiryAndExplicitClear() {
        Fixture().use { f ->
            f.plan = { query -> SearchQueryPlan(query, "canonical", listOf(SearchQueryPlan.Variant(query, SearchQueryPlan.Kind.ORIGINAL)), null) }
            f.fetch = { _, _, strict -> listOf(if (strict) "strict" else "broad") }
            assertEquals(listOf("broad"), f.engine.search("original"))
            assertEquals(listOf("broad"), f.engine.search("alias"))
            assertEquals(1, f.calls.get())
            f.strict = true
            assertEquals(listOf("strict"), f.engine.search("alias"))
            assertEquals(2, f.calls.get())
            f.now.set(101)
            f.engine.trim()
            assertEquals(listOf("strict"), f.engine.search("alias"))
            assertEquals(3, f.calls.get())
            f.engine.clear()
            assertTrue(f.engine.current().isEmpty())
            assertEquals(0, f.cache.size())
            f.engine.search("alias")
            assertEquals(4, f.calls.get())
        }
    }

    @Test fun collectionLimitIsAppliedInSourceOrderBeforeGlobalRankingAndTruncation() {
        Fixture(limit = 2).use { f ->
            f.sources = listOf("first", "later")
            val laterEntered = CountDownLatch(1)
            val laterStopped = CountDownLatch(1)
            f.fetch = { source, _, _ ->
                if (source == "first") {
                    assertTrue(laterEntered.await(5, TimeUnit.SECONDS))
                    listOf("b", "a")
                } else {
                    laterEntered.countDown()
                    try { CountDownLatch(1).await(); listOf("z") } finally { laterStopped.countDown() }
                }
            }
            assertEquals(listOf("a", "b"), f.engine.search("query"))
            assertTrue("Unconsumed provider must be cancelled", laterStopped.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("a", "b"), f.engine.current())
        }
    }

    @Test fun failedAliasOrProviderDoesNotDiscardSuccessfulEmptyOrPopulatedResponses() {
        Fixture().use { f ->
            f.sources = listOf("good", "empty", "bad")
            f.plan = { SearchQueryPlan(it, it, listOf(
                SearchQueryPlan.Variant("original", SearchQueryPlan.Kind.ORIGINAL),
                SearchQueryPlan.Variant("alias", SearchQueryPlan.Kind.KNOWN_ALIAS),
            ), null) }
            f.fetch = { source, variant, _ ->
                if (source == "bad" || variant.text() == "original") throw IllegalStateException("offline")
                if (source == "empty") emptyList() else listOf("found", "found")
            }
            assertEquals(listOf("found"), f.engine.search("query"))
            assertEquals(6, f.calls.get())
        }
    }

    @Test fun clearDuringPlanningOrConversionCannotRepopulateResultOrCache() {
        for (phase in listOf("plan", "convert")) Fixture().use { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val caller = Executors.newSingleThreadExecutor()
            fun block() { entered.countDown(); release.await() }
            if (phase == "plan") f.plan = { block(); SearchQueryPlanner.localOnly().plan(it) }
            else f.convert = { block(); it }
            try {
                val result = caller.submit<List<String>> { f.engine.search("query") }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                f.engine.clear()
                release.countDown()
                assertTrue(result.get(5, TimeUnit.SECONDS).isEmpty())
                assertTrue(f.engine.current().isEmpty())
                assertEquals(0, f.cache.size())
            } finally { release.countDown(); caller.shutdownNow() }
        }
    }

    @Test fun replacementInterruptsTrackedCallerAndLateOldWorkCannotOverwriteNewCache() {
        Fixture().use { f ->
            val caller = Executors.newSingleThreadExecutor()
            val entered = CountDownLatch(1)
            val stopped = CountDownLatch(1)
            f.fetch = { _, variant, _ ->
                if (variant.text() == "old") {
                    entered.countDown()
                    try { CountDownLatch(1).await() } finally { stopped.countDown() }
                }
                listOf(variant.text())
            }
            try {
                val old = f.engine.begin()
                val job = f.engine.submit(old, caller) { f.engine.search("old", old) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals(listOf("new"), f.engine.search("new"))
                assertTrue(stopped.await(5, TimeUnit.SECONDS))
                assertTrue(job.isCancelled)
                assertEquals(listOf("new"), f.engine.current())
                assertFalse(f.engine.isCurrent(old))
            } finally { caller.shutdownNow() }
        }
    }

    @Test fun mappingStillCapsThePublishedSnapshotAndCannotMutateTheCache() {
        Fixture(limit = 2).use { f ->
            f.convert = { listOf("one", "two", "three") }
            val result = f.engine.search("query")
            assertEquals(listOf("one", "two"), result)
            assertThrows(UnsupportedOperationException::class.java) { (result as MutableList).clear() }
            assertEquals(listOf("one", "two"), f.engine.current())
        }
    }
}
