package com.wuming.tv.core.search

import java.util.concurrent.*

/**
 * One replaceable search request, including planning and bounded provider dispatch.
 * Hosts own executors. Cancellation invalidates queued delivery as well as provider work.
 * Callbacks run on [delivery] without holding the state lock. Hosts serialize UI delivery
 * with their own teardown and can use [isCurrent] at the point of publication.
 */
class SearchLifecycle<S, R>(
    private val workers: ExecutorService,
    private val timers: ScheduledExecutorService,
    private val delivery: Executor,
    private val timeoutMillis: Long,
    private val concurrency: Int = 4,
) : AutoCloseable {
    enum class Status { SUCCESS, FAILED, TIMED_OUT, CANCELLED, REJECTED }
    data class Outcome<S, R>(val source: S, val status: Status, val value: R? = null, val failure: Throwable? = null, val elapsedMillis: Long = 0)

    private val lock = Any()
    private var epoch = 0
    private var closed = false
    private var startedEpoch = -1
    private val tracked = HashSet<Future<*>>()
    private val pending = ArrayDeque<Job>()
    private val active = HashSet<Job>()

    init { require(timeoutMillis > 0); require(concurrency > 0) }

    fun isCurrent(request: Int): Boolean = synchronized(lock) { !closed && request == epoch }
    fun outstandingCount(): Int = synchronized(lock) { tracked.size + pending.size + active.size }

    /** Begins a new epoch, cancelling both planning and provider futures from its predecessor. */
    fun cancel(): Int = cancelRequest(null)

    fun cancelIfCurrent(request: Int): Int = cancelRequest(request)

    internal fun cancelIfCurrent(request: Int, mayInterruptIfRunning: Boolean): Int =
        cancelRequest(request, mayInterruptIfRunning)

    private fun cancelRequest(expected: Int?, mayInterruptIfRunning: Boolean = true): Int {
        val futures: List<Future<*>>
        val request: Int
        synchronized(lock) {
            if (expected != null && expected != epoch) return epoch
            request = ++epoch
            futures = tracked.toList() + active.flatMap { listOfNotNull(it.task, it.deadline) }
            tracked.clear()
            active.clear()
            pending.clear()
        }
        futures.forEach { it.cancel(mayInterruptIfRunning) }
        (workers as? ThreadPoolExecutor)?.purge()
        return request
    }

    fun track(request: Int, future: Future<*>): Boolean {
        val accepted = synchronized(lock) { (!closed && epoch == request).also { if (it) tracked.add(future) } }
        if (!accepted) future.cancel(true)
        return accepted
    }

    fun untrack(future: Future<*>) { synchronized(lock) { tracked.remove(future) } }

    fun start(request: Int, sources: List<S>, key: (S) -> String, operation: (S) -> R,
              callback: (Outcome<S, R>) -> Unit) {
        // Snapshot potentially host-backed collections outside the lifecycle lock.
        val unique = linkedMapOf<String, S>()
        sources.forEach(java.util.function.Consumer { source -> unique.putIfAbsent(key(source), source) })
        synchronized(lock) {
            if (closed || epoch != request) return
            check(startedEpoch != request) { "Search epoch already dispatched" }
            startedEpoch = request
            unique.values.forEach { pending.addLast(Job(request, it, operation, callback)) }
        }
        requestPump()
    }

    /** Registers before dispatch, including hosts whose executor may run work inline. */
    fun submitTracked(request: Int, executor: Executor, action: () -> Unit): Future<*> {
        val task = object : FutureTask<Unit>(Callable { action(); Unit }) {
            override fun done() { untrack(this) }
        }
        if (track(request, task)) {
            try { executor.execute(task) }
            catch (rejected: RejectedExecutionException) { task.cancel(false); throw rejected }
        }
        return task
    }

    /**
     * Legacy browser policy: eager submission, source-order collection and a fresh wait budget
     * for each source's turn. No dispatch deadline is applied to this mode. Unconsumed work is
     * cancelled on the result limit, interruption, replacement and normal completion.
     */
    @Throws(InterruptedException::class)
    fun collectOrdered(request: Int, sources: List<S>, key: (S) -> String, operation: (S) -> R,
                       limit: Int, size: (R) -> Int): List<R> =
        collectOrderedCancellable(request, sources, key, { source, _ -> operation(source) }, limit, size)

    /** The cancellation probe remains valid even if provider code clears its thread interrupt. */
    internal fun collectOrderedCancellable(request: Int, sources: List<S>, key: (S) -> String,
                                          operation: (S, () -> Boolean) -> R,
                                          limit: Int, size: (R) -> Int): List<R> {
        require(limit > 0)
        val unique = sources.distinctBy(key)
        synchronized(lock) {
            if (closed || request != epoch) return emptyList()
            check(startedEpoch != request) { "Search epoch already dispatched" }
            startedEpoch = request
        }
        val tasks = ArrayList<FutureTask<R>>()
        try {
            for (source in unique) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Browser search cancelled")
                lateinit var task: FutureTask<R>
                task = object : FutureTask<R>(Callable {
                    operation(source) { task.isCancelled || !isCurrent(request) }
                }) {
                    override fun done() { untrack(this) }
                }
                tasks.add(task)
                if (!track(request, task)) break
                try { workers.execute(task) }
                catch (_: RejectedExecutionException) { task.cancel(false) }
            }
            val values = ArrayList<R>()
            var count = 0L
            for (task in tasks) {
                if (!isCurrent(request)) break
                try {
                    val value = task.get(timeoutMillis, TimeUnit.MILLISECONDS)
                    if (value != null) {
                        values.add(value)
                        count += size(value).coerceAtLeast(0)
                    }
                    if (count >= limit) break
                } catch (_: TimeoutException) { /* A slow source does not discard other results. */ }
                catch (_: ExecutionException) { /* Provider failures remain partial success. */ }
                catch (_: CancellationException) { /* Rejection or cancellation has no payload. */ }
            }
            return values
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } finally {
            tasks.forEach { it.cancel(true); untrack(it) }
            (workers as? ThreadPoolExecutor)?.purge()
        }
    }

    private fun requestPump() {
        try { timers.execute(::pump) }
        catch (rejected: RejectedExecutionException) {
            val abandoned = synchronized(lock) { pending.toList().also { pending.clear() } }
            abandoned.forEach { deliver(it, Outcome(it.source, Status.REJECTED, failure = rejected)) }
        }
    }

    private fun pump() {
        while (true) {
            val job = synchronized(lock) {
                if (closed || active.size >= concurrency || pending.isEmpty()) return
                pending.removeFirst().also { job ->
                    active.add(job)
                    job.started = System.nanoTime()
                    job.task = object : FutureTask<R>(Callable { job.operation(job.source) }) {
                        override fun done() {
                            val outcome = try { Outcome(job.source, Status.SUCCESS, get(), elapsedMillis = elapsed(job)) }
                            catch (cancelled: CancellationException) { Outcome<S, R>(job.source, Status.CANCELLED, failure = cancelled, elapsedMillis = elapsed(job)) }
                            catch (failed: ExecutionException) {
                                val cause = failed.cause ?: failed
                                val status = if (cause is CancellationException) Status.CANCELLED else Status.FAILED
                                Outcome<S, R>(job.source, status, failure = cause, elapsedMillis = elapsed(job))
                            }
                            catch (interrupted: InterruptedException) {
                                Thread.currentThread().interrupt()
                                Outcome<S, R>(job.source, Status.CANCELLED, failure = interrupted, elapsedMillis = elapsed(job))
                            }
                            finish(job, outcome)
                        }
                    }
                }
            }
            try {
                val deadline = timers.schedule({
                    finish(job, Outcome(job.source, Status.TIMED_OUT, failure = TimeoutException("Search source timed out"), elapsedMillis = elapsed(job)), cancelTask = true)
                }, timeoutMillis, TimeUnit.MILLISECONDS)
                val dispatch = synchronized(lock) {
                    if (job in active && job.epoch == epoch && !closed) { job.deadline = deadline; true }
                    else { deadline.cancel(false); false }
                }
                if (dispatch) workers.execute(job.task!!)
            } catch (rejected: RejectedExecutionException) {
                finish(job, Outcome(job.source, Status.REJECTED, failure = rejected, elapsedMillis = elapsed(job)), cancelTask = true)
            }
        }
    }

    private fun finish(job: Job, outcome: Outcome<S, R>, cancelTask: Boolean = false) {
        synchronized(lock) {
            if (!active.remove(job)) return
            job.deadline?.cancel(false)
        }
        if (cancelTask) {
            job.task?.cancel(true)
            (workers as? ThreadPoolExecutor)?.purge()
        }
        try { deliver(job, outcome) } finally { requestPump() }
    }

    /** Cached results use the same last-moment epoch check as provider completions. */
    fun deliverIfCurrent(request: Int, action: () -> Unit) {
        delivery.execute {
            if (isCurrent(request)) action()
        }
    }

    private fun deliver(job: Job, outcome: Outcome<S, R>) =
        deliverIfCurrent(job.epoch) { job.callback(outcome) }

    private fun elapsed(job: Job): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - job.started)

    override fun close() {
        synchronized(lock) { closed = true }
        cancel()
    }

    private inner class Job(val epoch: Int, val source: S, val operation: (S) -> R, val callback: (Outcome<S, R>) -> Unit) {
        var started: Long = 0
        var task: FutureTask<R>? = null
        var deadline: ScheduledFuture<*>? = null
    }
}
