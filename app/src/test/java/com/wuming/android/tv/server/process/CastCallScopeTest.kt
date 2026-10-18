package com.wuming.android.tv.server.process

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.reflect.KClass

class CastCallScopeTest {

    private class FakeCall : Call {
        var cancelled = false
        override fun request(): Request = Request.Builder().url("https://fixture.test/segment").build()
        override fun execute(): Response = throw UnsupportedOperationException()
        override fun enqueue(responseCallback: Callback) = throw UnsupportedOperationException()
        override fun cancel() {
            cancelled = true
        }

        override fun isExecuted(): Boolean = false
        override fun isCanceled(): Boolean = cancelled
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = this
        override fun <T : Any> tag(type: KClass<T>): T? = null
        override fun <T> tag(type: Class<out T>): T? = null
        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
    }

    @Test
    fun anOpenScopePermitsWork() {
        val scope = CastCallScope()
        scope.check()
        scope.register(FakeCall())
        scope.check()
    }

    @Test
    fun closingCancelsEveryRegisteredCall() {
        val scope = CastCallScope()
        val first = FakeCall()
        val second = FakeCall()
        scope.register(first)
        scope.register(second)
        scope.close()
        assertTrue(first.cancelled)
        assertTrue(second.cancelled)
    }

    @Test
    fun removingACallExcludesItFromCancellation() {
        val scope = CastCallScope()
        val finished = FakeCall()
        val running = FakeCall()
        scope.register(finished)
        scope.register(running)
        scope.remove(finished)
        scope.close()
        assertFalse(finished.cancelled)
        assertTrue(running.cancelled)
    }

    @Test
    fun closedScopeRejectsChecksAndLateRegistrations() {
        val scope = CastCallScope()
        scope.close()
        assertThrows(IOException::class.java) { scope.check() }
        val late = FakeCall()
        assertThrows(IOException::class.java) { scope.register(late) }
        assertTrue("A call registered after cancellation must not keep running", late.cancelled)
    }

    @Test
    fun closeIsIdempotent() {
        val scope = CastCallScope()
        val call = FakeCall()
        scope.register(call)
        scope.close()
        scope.close()
        assertTrue(call.cancelled)
    }

    @Test
    fun anInterruptedThreadStopsGettingWorkAccepted() {
        val thread = Thread.currentThread()
        thread.interrupt()
        try {
            val scope = CastCallScope()
            assertThrows(InterruptedIOException::class.java) { scope.check() }
            val call = FakeCall()
            assertThrows(InterruptedIOException::class.java) { scope.register(call) }
            assertTrue(call.cancelled)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun interruptionIsReportedEvenAfterTheScopeIsClosed() {
        val scope = CastCallScope()
        scope.close()
        val thread = Thread.currentThread()
        thread.interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) { scope.check() }
        } finally {
            Thread.interrupted()
        }
    }
}
