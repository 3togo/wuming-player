package com.wuming.tv.core.search

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class SearchLifecycleTest {
    @Test fun providerCancellationKeepsItsCancellationOutcomeInsteadOfBecomingAFailure() {
        Fixture().use { f ->
            val cancellation = CancellationException("provider cancelled")
            var observed: SearchLifecycle.Outcome<String, String>? = null
            f.lifecycle.start(f.lifecycle.cancel(), listOf("one"), { it }, { throw cancellation }) { observed = it }
            f.next()
            assertEquals(SearchLifecycle.Status.CANCELLED, observed!!.status)
            assertSame(cancellation, observed!!.failure)
        }
    }

    @Test fun cachedDeliveryUsesTheSameEpochGateAsProviderDelivery() {
        Fixture().use { f ->
            val published = mutableListOf<String>()
            val old = f.lifecycle.cancel()
            f.lifecycle.deliverIfCurrent(old) { published.add("old cache") }
            val current = f.lifecycle.cancel()
            f.next()
            f.lifecycle.deliverIfCurrent(current) { published.add("current cache") }
            f.next()
            assertEquals(listOf("current cache"), published)
        }
    }

    private class Fixture(timeout: Long = 2_000, concurrency: Int = 2) : AutoCloseable {
        val workers = Executors.newFixedThreadPool(4)
        val timers = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
        val delivery = LinkedBlockingQueue<Runnable>()
        val lifecycle = SearchLifecycle<String, String>(workers, timers, Executor { delivery.add(it) }, timeout, concurrency)
        fun next() = delivery.poll(5, TimeUnit.SECONDS).also { assertNotNull("Missing terminal delivery", it) }!!.run()
        override fun close() { lifecycle.close(); workers.shutdownNow(); timers.shutdownNow() }
    }

    @Test fun boundsDispatchDeduplicatesSourcesAndReportsEmptySuccessAndFailures() {
        Fixture().use { f ->
            val entered = CountDownLatch(2)
            val release = CountDownLatch(1)
            val running = AtomicInteger()
            val peak = AtomicInteger()
            val calls = CopyOnWriteArrayList<String>()
            val outcomes = mutableListOf<SearchLifecycle.Outcome<String, String>>()
            val failure = IllegalStateException("provider failure")
            f.lifecycle.start(f.lifecycle.cancel(), listOf("one", "one", "two", "empty", "bad"), { it }, {
                calls.add(it)
                val count = running.incrementAndGet()
                peak.accumulateAndGet(count, ::maxOf)
                entered.countDown()
                try {
                    release.await()
                    if (it == "bad") throw failure
                    if (it == "empty") "" else it
                } finally { running.decrementAndGet() }
            }, outcomes::add)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(2, calls.size)
            release.countDown()
            repeat(4) { f.next() }
            assertTrue(peak.get() <= 2)
            assertEquals(4, calls.size)
            assertEquals("", outcomes.single { it.source == "empty" }.value)
            assertEquals(SearchLifecycle.Status.SUCCESS, outcomes.single { it.source == "empty" }.status)
            assertSame(failure, outcomes.single { it.source == "bad" }.failure)
            assertEquals(SearchLifecycle.Status.FAILED, outcomes.single { it.source == "bad" }.status)
            assertEquals(0, f.lifecycle.outstandingCount())
        }
    }

    @Test fun timeoutIsTerminalEvenWhenProviderIgnoresInterruptionAndLaterReturns() {
        Fixture(timeout = 150, concurrency = 1).use { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val returned = CountDownLatch(1)
            val outcomes = mutableListOf<SearchLifecycle.Outcome<String, String>>()
            try {
                f.lifecycle.start(f.lifecycle.cancel(), listOf("slow", "fast"), { it }, {
                    if (it == "slow") {
                        entered.countDown()
                        while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                        returned.countDown()
                    }
                    it
                }, outcomes::add)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                repeat(2) { f.next() }
                assertEquals(SearchLifecycle.Status.TIMED_OUT, outcomes.single { it.source == "slow" }.status)
                assertEquals(SearchLifecycle.Status.SUCCESS, outcomes.single { it.source == "fast" }.status)
                release.countDown()
                assertTrue(returned.await(5, TimeUnit.SECONDS))
                // A barrier on the timer thread ensures any completion-triggered pump has run.
                f.timers.submit {}.get(5, TimeUnit.SECONDS)
                assertTrue(f.delivery.isEmpty())
                assertEquals(0, f.lifecycle.outstandingCount())
            } finally { release.countDown() }
        }
    }

    @Test fun replacementSuppressesQueuedDeliveryAndRejectsLatePlanning() {
        Fixture().use { f ->
            val outcomes = mutableListOf<SearchLifecycle.Outcome<String, String>>()
            val old = f.lifecycle.cancel()
            f.lifecycle.start(old, listOf("old"), { it }, { it }, outcomes::add)
            val queued = f.delivery.poll(5, TimeUnit.SECONDS)!!
            val current = f.lifecycle.cancel()
            queued.run()
            f.lifecycle.start(old, listOf("late"), { it }, { error("late planning dispatched") }, outcomes::add)
            f.lifecycle.start(current, listOf("new"), { it }, { it }, outcomes::add)
            f.next()
            assertEquals(listOf("new"), outcomes.map { it.source })
        }
    }

    @Test fun cancellationInterruptsProviderAndClosesPlanningOwnership() {
        Fixture().use { f ->
            val entered = CountDownLatch(1)
            val interrupted = CountDownLatch(1)
            val epoch = f.lifecycle.cancel()
            val plan = FutureTask { "plan" }
            assertTrue(f.lifecycle.track(epoch, plan))
            f.lifecycle.start(epoch, listOf("blocked"), { it }, {
                entered.countDown()
                try { CountDownLatch(1).await(); "unreachable" } finally { interrupted.countDown() }
            }) { error("cancelled result delivered") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            f.lifecycle.close()
            assertTrue(plan.isCancelled)
            assertTrue(interrupted.await(5, TimeUnit.SECONDS))
            assertEquals(0, f.lifecycle.outstandingCount())
            val late = FutureTask { "late" }
            assertFalse(f.lifecycle.track(epoch, late))
            assertTrue(late.isCancelled)
        }
    }

    @Test fun workerAndTimerRejectionFinishEverySourceWithoutRunningProviders() {
        for (stopTimer in listOf(false, true)) Fixture().use { f ->
            val outcomes = mutableListOf<SearchLifecycle.Outcome<String, String>>()
            if (stopTimer) f.timers.shutdownNow() else f.workers.shutdownNow()
            f.lifecycle.start(f.lifecycle.cancel(), listOf("a", "b", "c"), { it }, { error("rejected work ran") }, outcomes::add)
            repeat(3) { f.next() }
            assertEquals(3, outcomes.size)
            assertTrue(outcomes.all { it.status == SearchLifecycle.Status.REJECTED })
            assertEquals(0, f.lifecycle.outstandingCount())
        }
    }

    @Test fun throwingTimeoutObserverCannotPreventProviderCancellationOrQueueProgress() {
        Fixture().use { f ->
            SearchLifecycle<String, String>(f.workers, f.timers, Executor { it.run() }, 150, 1).use { lifecycle ->
                val interrupted = CountDownLatch(1)
                val next = CountDownLatch(1)
                lifecycle.start(lifecycle.cancel(), listOf("blocked", "next"), { it }, {
                    if (it == "blocked") try { CountDownLatch(1).await() } finally { interrupted.countDown() }
                    it
                }) {
                    if (it.source == "blocked") throw IllegalStateException("observer failed")
                    if (it.status == SearchLifecycle.Status.SUCCESS) next.countDown()
                }
                assertTrue(interrupted.await(5, TimeUnit.SECONDS))
                assertTrue(next.await(5, TimeUnit.SECONDS))
                assertEquals(0, lifecycle.outstandingCount())
            }
        }
    }

    @Test fun observerCanReplaceRequestWithoutDeadlocking() {
        Fixture().use { f ->
            val outcomes = mutableListOf<String>()
            f.lifecycle.start(f.lifecycle.cancel(), listOf("old"), { it }, { it }) {
                outcomes.add(it.source)
                f.lifecycle.start(f.lifecycle.cancel(), listOf("new"), { it }, { it }) { outcomes.add(it.source) }
            }
            f.next()
            f.next()
            assertEquals(listOf("old", "new"), outcomes)
        }
    }
}
