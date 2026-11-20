package com.wuming.tv.desktop.net

import okhttp3.Call
import java.util.concurrent.CancellationException

/** Cancels the transport even when the worker is blocked in an OkHttp body read. */
internal class DesktopResourceCancellation {
    @Volatile private var cancelled = false
    private var active: Call? = null

    @Synchronized
    fun cancel() {
        cancelled = true
        active?.cancel()
    }

    fun check() {
        if (cancelled || Thread.currentThread().isInterrupted) throw CancellationException("Resource request cancelled")
    }

    fun <T> run(call: Call, action: () -> T): T {
        synchronized(this) {
            check()
            check(active == null)
            active = call
        }
        try {
            return action().also { check() }
        } finally {
            synchronized(this) { active = null }
        }
    }
}
