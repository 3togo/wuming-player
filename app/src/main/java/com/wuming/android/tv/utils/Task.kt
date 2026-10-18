package com.wuming.android.tv.utils

import android.os.Looper
import com.wuming.tv.core.cache.MemoryBudget
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import java.util.concurrent.ThreadFactory as JavaThreadFactory

object Task {

    private val CPU_COUNT = Runtime.getRuntime().availableProcessors()
    private val MEMORY = MemoryBudget.current()
    private val CPU_THREADS = kotlin.math.max(2, CPU_COUNT)
    private val IO_THREADS = kotlin.math.max(4, CPU_COUNT * 2)
    private val MAX_QUEUED_TASKS = MEMORY.scale(256, 512, 1024)
    private val MAX_DEFERRED_MAIN_TASKS = MEMORY.scale(32, 64, 128)
    private const val MAX_DEFER_ATTEMPTS = 100
    private const val DEFER_RETRY_MILLIS = 50L
    private const val KEEP_ALIVE_TIME = 30L
    private val deferredMainTasks = AtomicInteger()
    private val scheduler = createScheduler()
    private val largeExecutor = MoreExecutors.listeningDecorator(createPool("TV-io", IO_THREADS))
    private val executor = MoreExecutors.listeningDecorator(createPool("TV-task", CPU_THREADS))

    @JvmStatic
    fun executor(): ListeningExecutorService = executor

    @JvmStatic
    fun largeExecutor(): ListeningExecutorService = largeExecutor

    @JvmStatic
    fun scheduler(): ScheduledExecutorService = scheduler

    @JvmStatic
    fun submit(task: Runnable): Future<*> = executor.submit(task)

    @JvmStatic
    fun submitLarge(task: Runnable): Future<*> = largeExecutor.submit(task)

    @JvmStatic
    fun execute(task: Runnable) {
        executor.execute(task)
    }

    @JvmStatic
    fun schedule(task: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
        scheduler.schedule(task, delay, unit)

    private fun createPool(name: String, threads: Int): ThreadPoolExecutor {
        val pool = ThreadPoolExecutor(
            threads, threads, KEEP_ALIVE_TIME, TimeUnit.SECONDS,
            LinkedBlockingQueue(MAX_QUEUED_TASKS),
            NamedThreadFactory(name)
        ) { task, owner -> handleRejected(task, owner) }
        pool.allowCoreThreadTimeOut(true)
        return pool
    }

    private fun createScheduler(): ScheduledThreadPoolExecutor {
        val value = ScheduledThreadPoolExecutor(1, NamedThreadFactory("TV-scheduler"))
        value.removeOnCancelPolicy = true
        return value
    }

    private fun handleRejected(task: Runnable, owner: ThreadPoolExecutor) {
        if (owner.isShutdown) {
            cancel(task)
            return
        }
        if (!isMainThread()) {
            task.run()
            return
        }
        if (deferredMainTasks.incrementAndGet() > MAX_DEFERRED_MAIN_TASKS) {
            deferredMainTasks.decrementAndGet()
            cancel(task)
            android.util.Log.e("Task", "Task queue and UI deferral queue are full")
            return
        }
        defer(task, owner, 0)
    }

    private fun defer(task: Runnable, owner: ThreadPoolExecutor, attempt: Int) {
        try {
            scheduler.schedule({
                if (owner.isShutdown) {
                    finishDeferred(task)
                } else if (owner.queue.offer(task)) {
                    deferredMainTasks.decrementAndGet()
                } else if (attempt + 1 < MAX_DEFER_ATTEMPTS) {
                    defer(task, owner, attempt + 1)
                } else {
                    finishDeferred(task)
                    android.util.Log.e("Task", "Task queue remained full for UI deferral timeout")
                }
            }, DEFER_RETRY_MILLIS, TimeUnit.MILLISECONDS)
        } catch (error: RejectedExecutionException) {
            finishDeferred(task)
        }
    }

    private fun finishDeferred(task: Runnable) {
        deferredMainTasks.decrementAndGet()
        cancel(task)
    }

    private fun cancel(task: Runnable) {
        if (task is Future<*>) task.cancel(false)
    }

    private fun isMainThread(): Boolean {
        return try {
            val main = Looper.getMainLooper()
            main != null && Looper.myLooper() === main
        } catch (ignored: RuntimeException) {
            false
        }
    }

    @JvmStatic
    fun <T> callback(onSuccess: Consumer<T>): FutureCallback<T> = callback(onSuccess, null)

    @JvmStatic
    fun <T> callback(onSuccess: Consumer<T>, onFailure: Consumer<Throwable>?): FutureCallback<T> {
        return object : FutureCallback<T> {
            override fun onSuccess(result: T) {
                onSuccess.accept(result)
            }

            override fun onFailure(error: Throwable) {
                onFailure?.accept(error)
            }
        }
    }

    private class NamedThreadFactory(private val prefix: String) : JavaThreadFactory {
        private val counter = AtomicInteger(1)

        override fun newThread(r: Runnable): Thread {
            val t = Thread(r, "$prefix-${counter.getAndIncrement()}")
            t.isDaemon = false
            if (t.priority != Thread.NORM_PRIORITY) t.priority = Thread.NORM_PRIORITY
            return t
        }
    }
}
