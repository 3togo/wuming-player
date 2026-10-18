package com.wuming.tv.core.playback

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class OrderedPrefetchStreamTest {
    private fun request(bytes: ByteArray, length: Long = bytes.size.toLong()) = object : OrderedPrefetchStream.Request {
        override val length = length
        override fun open(): InputStream = ByteArrayInputStream(bytes)
        override fun cancel() {}
    }
    private fun await(latch: CountDownLatch) { assertTrue("latch timed out", latch.await(5, TimeUnit.SECONDS)) }

    @Test fun deliversFirstBytesBeforeTheWholeSegmentFinishes() {
        val rest = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val reader = Executors.newSingleThreadExecutor()
        val stream = OrderedPrefetchStream(1, { object : OrderedPrefetchStream.Request {
            override val length = 2L * OrderedPrefetchStream.BLOCK_BYTES
            override fun open() = object : InputStream() {
                var calls = 0
                override fun read(): Int = error("bulk only")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (calls++ > 0) { waiting.countDown(); rest.await() }
                    b.fill(42, off, off + len)
                    return len
                }
            }
            override fun cancel() { rest.countDown() }
        } }, OrderedPrefetchStream.Budget(1))
        try {
            val first = reader.submit<Int> { stream.read() }
            await(waiting)
            assertEquals(42, first.get(5, TimeUnit.SECONDS).toInt())
            assertEquals(1L, rest.count)
        } finally { stream.close(); rest.countDown(); reader.shutdownNow() }
    }

    @Test fun keepsOrderAcrossManySegmentsAndReusesBoundedWindow() {
        val expected = (0 until 50).flatMap { i -> List(1000 + i) { i.toByte() } }.toByteArray()
        val opened = AtomicInteger()
        OrderedPrefetchStream(50, { index ->
            opened.incrementAndGet()
            request(ByteArray(1000 + index) { index.toByte() }, if (index % 2 == 0) -1 else (1000 + index).toLong())
        }, OrderedPrefetchStream.Budget(1)).use { input ->
            assertArrayEquals(expected, input.readBytes())
        }
        assertEquals(50, opened.get())
    }

    @Test fun downloadsLaterSegmentsWhileFirstOpenWaitsButCannotRunAheadUnboundedly() {
        val opened = CountDownLatch(3)
        val laterFilled = CountDownLatch(2)
        val release = CountDownLatch(1)
        val factories = AtomicInteger()
        val fetched = AtomicInteger()
        val cancelled = AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        val stream = OrderedPrefetchStream(100, { index ->
            factories.incrementAndGet()
            object : OrderedPrefetchStream.Request {
                override fun open(): InputStream {
                    opened.countDown()
                    if (index == 0) release.await()
                    return object : InputStream() {
                        var reads = 0
                        override fun read(): Int = error("bulk only")
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            b.fill(index.toByte(), off, off + len)
                            fetched.addAndGet(len)
                            if (++reads == OrderedPrefetchStream.QUEUED_BLOCKS + 1) laterFilled.countDown()
                            return len
                        }
                    }
                }
                override fun cancel() { cancelled.incrementAndGet(); release.countDown() }
            }
        }, OrderedPrefetchStream.Budget(1))
        try {
            val read = worker.submit<Int> { stream.read() }
            await(opened)
            await(laterFilled)
            assertEquals(3, factories.get())
            assertTrue(fetched.get() <= OrderedPrefetchStream.BUFFER_COUNT * OrderedPrefetchStream.BLOCK_BYTES)
            assertFalse(read.isDone)
            stream.close()
            try { read.get(5, TimeUnit.SECONDS) } catch (_: java.util.concurrent.ExecutionException) { }
            assertEquals(3, cancelled.get())
            assertEquals(3, factories.get())
        } finally { stream.close(); release.countDown(); worker.shutdownNow() }
    }

    @Test fun failsOnTruncationAndDoesNotReplaceMissingMediaWithPadding() {
        OrderedPrefetchStream(2, { i -> if (i == 0) request(byteArrayOf(1, 2)) else request(byteArrayOf(3), 5) },
            OrderedPrefetchStream.Budget(1)).use { input ->
            assertEquals(1, input.read())
            assertEquals(2, input.read())
            assertEquals(3, input.read())
            assertThrows(IOException::class.java) { input.read() }
        }
    }

    @Test fun publishesWorkerInterruptFailureInsteadOfLeavingReaderWaiting() {
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> {
                OrderedPrefetchStream(1, { object : OrderedPrefetchStream.Request {
                    override fun open(): InputStream {
                        Thread.currentThread().interrupt()
                        throw InterruptedIOException("fixture")
                    }
                    override fun cancel() {}
                } }, OrderedPrefetchStream.Budget(1)).use { input ->
                    assertThrows(InterruptedIOException::class.java) { input.read() }
                    true
                }
            }
            assertTrue(result.get(5, TimeUnit.SECONDS))
        } finally { worker.shutdownNow() }
    }

    @Test fun saturatedBudgetUsesCallerThreadAndPreservesLengthChecks() {
        val caller = Thread.currentThread()
        val openedBy = AtomicReference<Thread>()
        OrderedPrefetchStream(2, { object : OrderedPrefetchStream.Request {
            override val length = 2L
            override fun open(): InputStream { openedBy.set(Thread.currentThread()); return ByteArrayInputStream(byteArrayOf(7, 8)) }
            override fun cancel() {}
        } }, OrderedPrefetchStream.Budget(0)).use { input ->
            assertArrayEquals(byteArrayOf(7, 8, 7, 8), input.readBytes())
        }
        assertSame(caller, openedBy.get())
        OrderedPrefetchStream(1, { request(byteArrayOf(1), 3) }, OrderedPrefetchStream.Budget(0)).use { input ->
            assertThrows(IOException::class.java) { input.readBytes() }
        }
    }

    @Test fun abandonedOpenRetainsAdmissionUntilWorkersActuallyExit() {
        val budget = OrderedPrefetchStream.Budget(1)
        val opened = CountDownLatch(3)
        val exit = CountDownLatch(1)
        val reader = Executors.newSingleThreadExecutor()
        val stream = OrderedPrefetchStream(3, { object : OrderedPrefetchStream.Request {
            override fun open(): InputStream {
                opened.countDown()
                while (true) { try { exit.await(); break } catch (_: InterruptedException) {} }
                return ByteArrayInputStream(byteArrayOf(1))
            }
            override fun cancel() {}
        } }, budget)
        try {
            val read = reader.submit<Int> { stream.read() }
            await(opened)
            stream.close()
            assertEquals(0, budget.permits.availablePermits())
            try { read.get(5, TimeUnit.SECONDS) } catch (_: java.util.concurrent.ExecutionException) { }
            exit.countDown()
            assertTrue(budget.permits.tryAcquire(5, TimeUnit.SECONDS))
            budget.permits.release()
        } finally { stream.close(); exit.countDown(); reader.shutdownNow() }
    }

    @Test fun zeroReadsBoundsAndCloseDoNotStartNetworkWork() {
        OrderedPrefetchStream(1, { error("must remain lazy") }, OrderedPrefetchStream.Budget(1)).use { stream ->
            assertEquals(0, stream.read(ByteArray(0), 0, 0))
            assertThrows(IndexOutOfBoundsException::class.java) { stream.read(ByteArray(1), -1, 1) }
        }
        OrderedPrefetchStream(0, { error("empty") }).use { assertEquals(-1, it.read()) }
    }
}
