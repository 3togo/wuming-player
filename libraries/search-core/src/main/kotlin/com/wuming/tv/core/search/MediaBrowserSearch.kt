package com.wuming.tv.core.search

import com.wuming.tv.core.cache.ExpiringLruCache
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Browser request ownership, ordered collection, result cap and canonical/strict cache identity. */
@JvmSuppressWildcards
class MediaBrowserSearch<S, R, M>(
    workers: ExecutorService,
    timers: ScheduledExecutorService,
    private val host: Host<S, R, M>,
    private val cache: ExpiringLruCache<String, List<M>>,
    timeoutMillis: Long = 5_000,
    private val limit: Int = 50,
) {
    @JvmSuppressWildcards
    interface Host<S, R, M> {
        fun plan(query: String): SearchQueryPlan
        fun strict(): Boolean
        fun sources(): List<S>
        fun key(source: S): String
        @Throws(Exception::class)
        fun search(source: S, plan: SearchQueryPlan, variant: SearchQueryPlan.Variant, strict: Boolean): List<R>
        fun merge(items: List<R>): List<R>
        /** Platform metadata conversion; ranking uses the host's maintained shared policy. */
        fun items(results: List<R>, query: String): List<M>
    }

    private val lock = Any()
    private val lifecycle = SearchLifecycle<S, List<R>>(workers, timers, Executor { it.run() }, timeoutMillis)
    private var current: List<M> = emptyList()

    init { require(limit > 0) }

    fun begin(): Int = synchronized(lock) { lifecycle.cancel() }
    fun isCurrent(request: Int): Boolean = lifecycle.isCurrent(request)
    fun submit(request: Int, executor: Executor, action: () -> Unit): Future<*> {
        val work = lifecycle.submitTracked(request, executor, action)
        return object : Future<Any?> {
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean = synchronized(lock) {
                // Cancellation and final cache publication must use the same lock, just as
                // begin/clear do. A failed cancellation must not revoke a completed request.
                work.cancel(mayInterruptIfRunning).also { cancelled ->
                    if (cancelled) lifecycle.cancelIfCurrent(request, mayInterruptIfRunning)
                }
            }
            override fun isCancelled(): Boolean = work.isCancelled
            override fun isDone(): Boolean = work.isDone
            override fun get(): Any? = work.get()
            override fun get(timeout: Long, unit: TimeUnit): Any? = work.get(timeout, unit)
        }
    }

    @JvmOverloads
    fun search(query: String, request: Int = begin()): List<M> {
        if (!isCurrent(request)) return emptyList()
        val plan = host.plan(query)
        val strict = host.strict()
        val cacheKey = plan.canonicalKey() + if (strict) "|strict" else "|broad"
        synchronized(lock) {
            if (!isCurrent(request)) return emptyList()
            cache.get(cacheKey)?.let { current = it; return it }
        }
        val collected = try {
            lifecycle.collectOrderedCancellable(request, host.sources(), host::key, { source, cancelled ->
                searchSite(source, plan, strict, cancelled)
            }, limit) { it.size }
        } catch (_: InterruptedException) {
            return emptyList() // collectOrdered has restored interruption and cancelled the batch.
        }
        if (!isCurrent(request)) return emptyList()
        val items = java.util.Collections.unmodifiableList(ArrayList(host.items(host.merge(collected.flatten()), query).take(limit)))
        synchronized(lock) {
            if (!isCurrent(request)) return emptyList()
            cache.put(cacheKey, items)
            current = items
            return items
        }
    }

    private fun searchSite(source: S, plan: SearchQueryPlan, strict: Boolean, cancelled: () -> Boolean): List<R> {
        val items = ArrayList<R>()
        var completed = false
        var lastFailure: Exception? = null
        for (variant in plan.variants()) {
            checkActive(cancelled)
            try {
                items.addAll(host.search(source, plan, variant, strict))
                completed = true
            } catch (failure: Exception) {
                // A failure of one alias is not cancellation of the owning source/request.
                // Preserve prior successful variants, but never issue another call for work
                // the lifecycle abandoned, even if the provider cleared its interrupt flag.
                if (cancelled() || Thread.currentThread().isInterrupted) throw failure
                lastFailure = failure
            }
        }
        checkActive(cancelled)
        if (!completed) lastFailure?.let { throw it }
        return host.merge(items)
    }

    private fun checkActive(cancelled: () -> Boolean) {
        if (cancelled()) throw java.util.concurrent.CancellationException("Browser search cancelled")
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Browser search cancelled")
    }

    fun current(): List<M> = synchronized(lock) { current }
    fun trim() = synchronized(lock) { cache.trim() }
    fun clear() = synchronized(lock) {
        lifecycle.cancel()
        cache.clear()
        current = emptyList()
    }
}
