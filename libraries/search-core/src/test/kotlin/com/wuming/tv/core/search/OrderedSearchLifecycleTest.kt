package com.wuming.tv.core.search

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

class OrderedSearchLifecycleTest {
    @Test fun sourceOrderAtTheLimitIgnoresAnAlreadyCompletedLaterProvider() {
        val laterDone = CountDownLatch(1)
        val workers = object : ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>()) {
            override fun afterExecute(task: Runnable, failure: Throwable?) {
                super.afterExecute(task, failure)
                val result = task as Future<*>
                if (!result.isCancelled && result.get() == listOf("later")) laterDone.countDown()
            }
        }
        val timers = Executors.newSingleThreadScheduledExecutor()
        val lifecycle = SearchLifecycle<String, List<String>>(workers, timers, Executor { it.run() }, 5_000)
        try {
            val result = lifecycle.collectOrdered(lifecycle.cancel(), listOf("first", "later"), { it }, { source ->
                if (source == "first") assertTrue(laterDone.await(5, TimeUnit.SECONDS))
                listOf(source)
            }, 1) { it.size }
            assertEquals(listOf(listOf("first")), result)
        } finally { lifecycle.close(); workers.shutdownNow(); timers.shutdownNow() }
    }

    @Test fun aTimedOutSourceIsNotBackfilledIfItFinishesBeforeCollectionEnds() {
        val workers = Executors.newFixedThreadPool(2)
        val timers = Executors.newSingleThreadScheduledExecutor()
        val lifecycle = SearchLifecycle<String, List<String>>(workers, timers, Executor { it.run() }, 100)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        try {
            val result = lifecycle.collectOrdered(lifecycle.cancel(), listOf("late", "fast"), { it }, { source ->
                if (source == "late") { release.await(); returned.countDown() }
                listOf(source)
            }, 50) { value ->
                if (value == listOf("fast")) {
                    release.countDown()
                    assertTrue(returned.await(5, TimeUnit.SECONDS))
                }
                value.size
            }
            assertEquals(listOf(listOf("fast")), result)
        } finally { release.countDown(); lifecycle.close(); workers.shutdownNow(); timers.shutdownNow() }
    }

    @Test fun sourcesAreEagerButCollectedInOrderWithAFreshWaitBudgetPerTurn() {
        val workers = Executors.newFixedThreadPool(2)
        val timers = Executors.newSingleThreadScheduledExecutor()
        val caller = Executors.newSingleThreadExecutor()
        val lifecycle = SearchLifecycle<String, List<String>>(workers, timers, Executor { it.run() }, 500)
        val entered = CountDownLatch(2)
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        try {
            val result = caller.submit<List<List<String>>> {
                lifecycle.collectOrdered(lifecycle.cancel(), listOf("one", "two"), { it }, {
                    entered.countDown()
                    if (it == "one") first.await() else second.await()
                    listOf(it)
                }, 50) { it.size }
            }
            assertTrue("Both providers must start before collecting either", entered.await(5, TimeUnit.SECONDS))
            Thread.sleep(300)
            first.countDown()
            Thread.sleep(300)
            second.countDown()
            assertEquals(listOf(listOf("one"), listOf("two")), result.get(5, TimeUnit.SECONDS))
            assertEquals(0, lifecycle.outstandingCount())
        } finally { first.countDown(); second.countDown(); lifecycle.close(); caller.shutdownNow(); workers.shutdownNow(); timers.shutdownNow() }
    }

    @Test fun timedOutSourceIsSkippedAndPartialResultsSurviveWhileItsWorkIsCancelled() {
        val workers = Executors.newFixedThreadPool(2)
        val timers = Executors.newSingleThreadScheduledExecutor()
        val lifecycle = SearchLifecycle<String, List<String>>(workers, timers, Executor { it.run() }, 100)
        val stopped = CountDownLatch(1)
        try {
            val result = lifecycle.collectOrdered(lifecycle.cancel(), listOf("slow", "fast"), { it }, {
                if (it == "slow") try { CountDownLatch(1).await() } finally { stopped.countDown() }
                listOf(it)
            }, 50) { it.size }
            assertEquals(listOf(listOf("fast")), result)
            assertTrue(stopped.await(5, TimeUnit.SECONDS))
            assertEquals(0, lifecycle.outstandingCount())
        } finally { lifecycle.close(); workers.shutdownNow(); timers.shutdownNow() }
    }

    @Test fun callerInterruptionCancelsProvidersAndRetainsInterruptStatus() {
        val workers = Executors.newSingleThreadExecutor()
        val timers = Executors.newSingleThreadScheduledExecutor()
        val lifecycle = SearchLifecycle<String, List<String>>(workers, timers, Executor { it.run() }, 5_000)
        val entered = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val caller = Thread {
            try {
                lifecycle.collectOrdered(lifecycle.cancel(), listOf("one"), { it }, {
                    entered.countDown()
                    try { CountDownLatch(1).await(); emptyList() } finally { stopped.countDown() }
                }, 50) { it.size }
            } catch (_: InterruptedException) { interrupted.set(Thread.currentThread().isInterrupted) }
        }
        try {
            caller.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            caller.interrupt()
            caller.join(5_000)
            assertFalse(caller.isAlive)
            assertTrue(interrupted.get())
            assertTrue(stopped.await(5, TimeUnit.SECONDS))
        } finally { caller.interrupt(); lifecycle.close(); workers.shutdownNow(); timers.shutdownNow() }
    }
}
