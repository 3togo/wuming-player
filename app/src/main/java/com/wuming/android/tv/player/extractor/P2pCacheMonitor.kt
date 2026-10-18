package com.wuming.android.tv.player.extractor

import com.wuming.android.tv.utils.AppLog
import com.wuming.android.tv.utils.Task
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps native/disk work off the shared scheduler and skips overlapping checks. */
internal class P2pCacheMonitor(private val name: String, private val check: () -> Unit) {
    private val running = AtomicBoolean()
    @Volatile private var cancelled = false
    private val future = Task.scheduler().scheduleWithFixedDelay({
        if (!cancelled && running.compareAndSet(false, true)) {
            try {
                Task.submitLarge {
                    try {
                        if (!cancelled) check()
                    } catch (error: Exception) {
                        AppLog.e(name, error)
                    } finally {
                        running.set(false)
                    }
                }
            } catch (error: Exception) {
                running.set(false)
                AppLog.e(name, error)
            }
        }
    }, 10, 10, TimeUnit.SECONDS)

    fun cancel() {
        cancelled = true
        future.cancel(false)
    }
}
