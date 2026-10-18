package com.wuming.tv.desktop.player

import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayExecutorsTest {
    @Test
    fun `HLS concurrency leaves headroom on small and busy heaps`() {
        val mib = 1024L * 1024
        assertEquals(1, RelayExecutors.hlsWorkers(256 * mib, 64 * mib))
        assertEquals(4, RelayExecutors.hlsWorkers(2048 * mib, 256 * mib))
        assertEquals(1, RelayExecutors.hlsWorkers(2048 * mib, 2000 * mib))
    }

    @Test
    fun `overload is bounded and admitted work completes when the player resumes reading`() {
        val executor = RelayExecutors.create("relay-test", 1)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(4)
        try {
            executor.execute {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            repeat(4) { executor.execute { completed.countDown() } }
            assertThrows(RejectedExecutionException::class.java) { executor.execute {} }
            assertEquals(1, executor.poolSize)
            assertEquals(4, executor.queue.size)
            release.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            executor.submit {}.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
