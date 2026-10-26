package com.wuming.tv.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class JianPianSessionTest {
    private class FakeNative : JianPianSession.Native {
        val calls = mutableListOf<String>()
        var failOn: String? = null
        var resultOn: String? = null
        var positiveOn: String? = null

        private fun record(operation: String, path: ByteArray): Int {
            calls += "$operation:${String(path, charset("GBK"))}"
            if (operation == failOn) error("failed $operation")
            return when (operation) {
                resultOn -> -1
                positiveOn -> 7
                else -> 0
            }
        }

        override fun start(path: ByteArray) = record("start", path)
        override fun add(path: ByteArray) = record("add", path)
        override fun pause(path: ByteArray) = record("pause", path)
        override fun delete(path: ByteArray) = record("delete", path)
    }

    @Test
    fun switchingAndStoppingAlwaysPairTheNativeCalls() {
        val native = FakeNative()
        val session = JianPianSession(native)

        session.start("ftp://example/中文.mp4")
        session.start("ftp://example/next.mp4")
        session.stop()

        assertEquals(
            listOf(
                "start:ftp://example/中文.mp4", "add:ftp://example/中文.mp4",
                "pause:ftp://example/中文.mp4", "delete:ftp://example/中文.mp4",
                "start:ftp://example/next.mp4", "add:ftp://example/next.mp4",
                "pause:ftp://example/next.mp4", "delete:ftp://example/next.mp4",
            ),
            native.calls,
        )
        assertNull(session.path)
    }

    @Test
    fun failedAddCleansUpTheStartedTask() {
        val native = FakeNative().apply { failOn = "add" }
        val session = JianPianSession(native)

        assertThrows(IllegalStateException::class.java) { session.start("ftp://example/video") }

        assertEquals(
            listOf("start:ftp://example/video", "add:ftp://example/video",
                "pause:ftp://example/video", "delete:ftp://example/video"),
            native.calls,
        )
        assertNull(session.path)
    }

    @Test
    fun negativeNativeStatusCleansUpAndCanRetryDeletion() {
        val native = FakeNative().apply { resultOn = "add" }
        val session = JianPianSession(native)
        assertThrows(IllegalStateException::class.java) { session.start("ftp://example/video") }
        assertNull(session.path)
        assertEquals("delete:ftp://example/video", native.calls.last())

        native.resultOn = null
        session.start("ftp://example/video")
        native.resultOn = "delete"
        assertThrows(IllegalStateException::class.java) { session.stop() }
        assertEquals("ftp://example/video", session.path)
        native.resultOn = null
        session.stop()
        assertNull(session.path)
    }

    @Test
    fun positiveNativeTaskIdIsAccepted() {
        val native = FakeNative().apply { positiveOn = "start" }
        val session = JianPianSession(native)
        session.start("ftp://example/video")
        assertEquals("ftp://example/video", session.path)
        session.stop()
        assertNull(session.path)
    }

    @Test
    fun deletionStillRunsWhenPauseFails() {
        val native = FakeNative().apply { failOn = "pause" }
        val session = JianPianSession(native)
        session.start("ftp://example/video")

        assertThrows(IllegalStateException::class.java) { session.stop() }

        assertEquals("delete:ftp://example/video", native.calls.last())
        assertNull(session.path)
    }

    @Test
    fun failedDeletionCanBeRetriedBeforeTheNextStart() {
        val native = FakeNative()
        val session = JianPianSession(native)
        session.start("ftp://example/video")
        native.failOn = "delete"

        assertThrows(IllegalStateException::class.java) { session.stop() }
        assertEquals("ftp://example/video", session.path)

        native.failOn = null
        session.stop()
        assertNull(session.path)
        assertEquals("delete:ftp://example/video", native.calls.last())
    }

    @Test
    fun stopWaitsUntilStartAndAddComplete() {
        val enteredStart = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val calls = mutableListOf<String>()
        val native = object : JianPianSession.Native {
            override fun start(path: ByteArray): Int {
                synchronized(calls) { calls += "start" }
                enteredStart.countDown()
                check(releaseStart.await(5, TimeUnit.SECONDS))
                return 0
            }
            override fun add(path: ByteArray): Int { synchronized(calls) { calls += "add" }; return 0 }
            override fun pause(path: ByteArray): Int { synchronized(calls) { calls += "pause" }; return 0 }
            override fun delete(path: ByteArray): Int { synchronized(calls) { calls += "delete" }; return 0 }
        }
        val session = JianPianSession(native)
        val failure = AtomicReference<Throwable?>()
        val starting = Thread { try { session.start("ftp://example/video") } catch (e: Throwable) { failure.set(e) } }
        val stopping = Thread { try { session.stop() } catch (e: Throwable) { failure.set(e) } }
        starting.start()
        try {
            assertEquals(true, enteredStart.await(5, TimeUnit.SECONDS))
            stopping.start()
        } finally {
            releaseStart.countDown()
        }
        starting.join(5000)
        stopping.join(5000)
        assertEquals(null, failure.get())
        assertEquals(listOf("start", "add", "pause", "delete"), synchronized(calls) { calls.toList() })
    }
}
