package com.wuming.android.tv.utils

import androidx.lifecycle.LifecycleOwner
import com.wuming.android.tv.App
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.function.Consumer

/** One background result owned by a UI lifecycle. Construct and close on main.
 * Operation, cancellation and discard callbacks must capture services/data, not UI owners. */
@JvmSuppressWildcards
class UiRequest<T>(
    owner: LifecycleOwner,
    operation: Callable<T>,
    success: Consumer<T>,
    failure: Consumer<Exception>,
    cancel: Runnable?,
    private val discard: Consumer<T>,
    worker: Executor,
    private val main: Executor,
    private val remove: Consumer<Runnable>,
) : AutoCloseable {
    private val work: FutureTask<Void?>
    private val delivery = Runnable { deliver() }
    private var lifetime: LifecycleCleanup? = null
    private var success: Consumer<T>? = success
    private var failure: Consumer<Exception>? = failure
    private var cancel: Runnable? = cancel
    private var result: T? = null
    private var error: Exception? = null
    private var closed = false
    constructor(
        owner: LifecycleOwner,
        operation: Callable<T>,
        success: Consumer<T>,
        failure: Consumer<Exception>,
        cancel: Runnable?,
        discard: Consumer<T>,
    ) :
        this(
            owner,
            operation,
            success,
            failure,
            cancel,
            discard,
            Task.largeExecutor(),
            Executor {
                App.post(it)
            },
            Consumer { App.removeCallbacks(it) },
        )

    init {
        work =
            object : FutureTask<Void?>(
                Callable {
                    var value: T? = null
                    var problem: Exception? = null
                    try {
                        value = operation.call()
                    } catch (e: Exception) {
                        problem = e
                    }
                    synchronized(this) {
                        if (closed || cancelled()) {
                            discard(value)
                            return@Callable null
                        }
                        result = value
                        error = problem
                        main.execute(delivery)
                    }
                    null
                },
            ) {
                override fun done() {
                    synchronized(this@UiRequest) {
                        if (!isCancelled || closed) return
                        error = CancellationException("Request could not be completed")
                        main.execute(delivery)
                    }
                }
            }
        lifetime = LifecycleCleanup(owner) { close() }
        if (!closed) {
            try {
                worker.execute(work)
            } catch (
                rejected: RuntimeException,
            ) {
                synchronized(this) {
                    error = rejected
                    main.execute(delivery)
                }
            }
        }
    }

    private fun cancelled(): Boolean = work.isCancelled

    @Suppress("UNCHECKED_CAST")
    private fun deliver() {
        val target: Consumer<T>?
        val failed: Consumer<Exception>?
        val value: T?
        val problem: Exception?
        synchronized(this) {
            if (closed) return
            target = success
            failed = failure
            value = result
            problem = error
            result = null // Transfer ownership before closing the request.
        }
        close()
        if (problem == null) {
            target!!.accept(value as T)
        } else {
            discard(value)
            failed!!.accept(problem)
        }
    }

    private fun discard(value: T?) {
        if (value == null) return
        try {
            discard.accept(value)
        } catch (e: RuntimeException) {
            AppLog.w("UiRequest", "Result cleanup failed", e)
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        success = null
        failure = null
        error = null
        work.cancel(true)
        remove.accept(delivery)
        lifetime?.close()
        val cleanup = cancel
        cancel = null
        try {
            cleanup?.run()
        } catch (
            e: RuntimeException,
        ) {
            AppLog.w("UiRequest", "Request cancellation failed", e)
        } finally {
            discard(result)
            result = null
        }
    }
}
