package com.wuming.android.tv.server.process

import okhttp3.Call
import java.io.IOException
import java.io.InterruptedIOException

internal class CastCallScope : AutoCloseable {
    private val active: MutableSet<Call> = HashSet()
    private var closed = false

    @Synchronized
    @Throws(IOException::class)
    fun check() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Cast work interrupted")
        if (closed) throw IOException("Cast work cancelled")
    }

    @Synchronized
    @Throws(IOException::class)
    fun register(call: Call) {
        if (closed || Thread.currentThread().isInterrupted) {
            call.cancel()
            throw InterruptedIOException("Cast work cancelled")
        }
        active.add(call)
    }

    @Synchronized
    fun remove(call: Call) {
        active.remove(call)
    }

    override fun close() {
        val calls: List<Call>
        synchronized(this) {
            if (closed) return
            closed = true
            calls = ArrayList(active)
        }
        for (call in calls) call.cancel()
    }
}
