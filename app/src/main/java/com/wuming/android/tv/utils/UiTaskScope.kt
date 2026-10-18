package com.wuming.android.tv.utils

import android.os.Handler
import android.os.Looper

/** Main-thread work owned by one activity or one fragment view, never both. */
class UiTaskScope : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false

    @Synchronized
    fun post(task: Runnable, delayMillis: Long) {
        if (!closed) handler.postDelayed(task, delayMillis)
    }

    @Synchronized
    fun remove(task: Runnable) {
        handler.removeCallbacks(task)
    }

    @Synchronized
    override fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
    }
}
