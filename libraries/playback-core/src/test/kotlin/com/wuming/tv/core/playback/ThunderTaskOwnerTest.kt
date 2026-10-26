package com.wuming.tv.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ThunderTaskOwnerTest {
    @Test
    fun stopWaitsForTaskCreationAndThenStopsThePublishedTask() {
        val enteredCreate = CountDownLatch(1)
        val releaseCreate = CountDownLatch(1)
        val stopped = mutableListOf<Any>()
        var leases = 0
        val owner = ThunderTaskOwner(
            begin = { leases++ },
            end = { leases-- },
            stopNative = { task: Any -> stopped += task },
        )
        val task = Any()
        val failure = AtomicReference<Throwable?>()
        val starting = Thread {
            try {
                owner.start {
                    enteredCreate.countDown()
                    check(releaseCreate.await(5, TimeUnit.SECONDS))
                    task
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        val stopping = Thread {
            try {
                owner.stop()
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        starting.start()
        try {
            assertEquals(true, enteredCreate.await(5, TimeUnit.SECONDS))
            stopping.start()
        } finally {
            releaseCreate.countDown()
        }
        starting.join(5000)
        stopping.join(5000)
        assertFalse(starting.isAlive)
        assertFalse(stopping.isAlive)
        assertEquals(null, failure.get())
        assertEquals(listOf(task), stopped)
        assertEquals(0, leases)
    }

    @Test
    fun staleFetchCannotProbeOrStopItsReplacement() {
        val stopped = mutableListOf<Any>()
        val owner = ThunderTaskOwner(
            begin = {}, end = {}, stopNative = { task: Any -> stopped += task },
        )
        val first = owner.start { Any() }
        val second = owner.start { Any() }
        owner.stopIfCurrent(first)
        assertThrows(CancellationException::class.java) { owner.withCurrent(first) { error("stale task was probed") } }
        assertEquals("current", owner.withCurrent(second) { "current" })
        owner.stop()
        assertEquals(listOf(first, second), stopped)
    }

    @Test
    fun failedCreationReleasesItsLease() {
        var leases = 0
        val owner = ThunderTaskOwner(
            begin = { leases++ }, end = { leases-- }, stopNative = { _: Any -> },
        )
        assertThrows(IllegalStateException::class.java) { owner.start { error("create failed") } }
        assertEquals(0, leases)
    }
}
