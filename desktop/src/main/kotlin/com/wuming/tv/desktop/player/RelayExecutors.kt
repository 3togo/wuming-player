package com.wuming.tv.desktop.player

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Bounds queued requests as well as workers; overloaded clients may retry a rejected connection. */
internal object RelayExecutors {
    fun create(name: String, workers: Int = 4): ThreadPoolExecutor = ThreadPoolExecutor(
        workers, workers, 30, TimeUnit.SECONDS, ArrayBlockingQueue(workers * 4),
        { task -> Thread(task, name).apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun hlsWorkers(
        heapLimit: Long = Runtime.getRuntime().maxMemory(),
        heapUsed: Long = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
    ): Int {
        val headroom = (heapLimit - heapUsed).coerceAtLeast(0)
        val budget = minOf(heapLimit / 4, headroom / 3)
        // Resource reads can briefly hold both chunks and the assembled 32 MiB response.
        return (budget / (64L * 1024 * 1024)).coerceIn(1, 4).toInt()
    }
}
