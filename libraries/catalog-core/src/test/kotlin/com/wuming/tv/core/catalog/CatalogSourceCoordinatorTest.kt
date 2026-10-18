package com.wuming.tv.core.catalog

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*

class CatalogSourceCoordinatorTest {
    @Test fun timeoutInterruptsTheUnderlyingProviderAndRetainsFastResults() {
        val executor = Executors.newFixedThreadPool(2)
        val stopped = CountDownLatch(1)
        val coordinator = CatalogSourceCoordinator(executor, searchWaitMillis = 250)
        try {
            val outcomes = coordinator.collect(listOf(CatalogSource("fast", "Fast"), CatalogSource("slow", "Slow")), "search") {
                if (it.key == "slow") try { CountDownLatch(1).await() } finally { stopped.countDown() }
                CatalogSourcePayload(listOf(CatalogItem(it.key, "id", "Title")))
            }
            assertEquals(1, outcomes.first().payload.items.size)
            assertTrue(outcomes.last().failure is TimeoutException)
            assertTrue(stopped.await(5, TimeUnit.SECONDS))
        } finally { coordinator.close(); executor.shutdownNow() }
    }

    @Test fun progressiveHomeReturnsLateTypesAndClosePreventsNewWork() {
        val executor = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        val late = CountDownLatch(1)
        val coordinator = CatalogSourceCoordinator(executor, progressiveWaitMillis = 25)
        try {
            val outcomes = coordinator.collect(listOf(CatalogSource("site", "Site")), "home", {
                if (it.payload.types.isNotEmpty()) late.countDown()
            }) {
                release.await()
                CatalogSourcePayload(types = listOf(CatalogType("movie", "Movies")))
            }
            assertTrue(outcomes.single().failure is TimeoutException)
            release.countDown()
            assertTrue(late.await(5, TimeUnit.SECONDS))
            coordinator.close()
            assertTrue(coordinator.collect(listOf(CatalogSource("site", "Site")), "search") { error("closed") }.isEmpty())
        } finally { release.countDown(); coordinator.close(); executor.shutdownNow() }
    }

    @Test fun callerInterruptionCancelsProviderWorkAndPreservesInterruptStatus() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val interrupted = java.util.concurrent.atomic.AtomicBoolean()
        val coordinator = CatalogSourceCoordinator(executor)
        val caller = Thread {
            try {
                coordinator.collect(listOf(CatalogSource("site", "Site")), "search") {
                    entered.countDown()
                    try { CountDownLatch(1).await(); CatalogSourcePayload() } finally { stopped.countDown() }
                }
            } catch (_: InterruptedException) { interrupted.set(Thread.currentThread().isInterrupted) }
            finally { finished.countDown() }
        }
        try {
            caller.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            caller.interrupt()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertTrue(stopped.await(5, TimeUnit.SECONDS))
            assertTrue(interrupted.get())
        } finally { caller.interrupt(); coordinator.close(); executor.shutdownNow() }
    }

    @Test fun navigationIsBoundedAndSearchDoesNotConsumeVodHistory() {
        val vod = CatalogNavigationHistory<String>(2)
        val search = CatalogNavigationHistory<String>(2)
        listOf("old", "home", "category").forEach(vod::push)
        search.push("query")
        assertEquals("category", vod.pop())
        assertEquals("home", vod.pop())
        assertNull(vod.pop())
        assertEquals("query", search.pop())
    }
}
