package com.wuming.tv.core.catalog

import java.util.concurrent.*

/** Bounded multi-source orchestration. The platform supplies threads and dispatches UI callbacks. */
class CatalogSourceCoordinator(
    private val executor: ExecutorService,
    private val progressiveWaitMillis: Long = 1_500,
    private val initialHomeWaitMillis: Long = 5_000,
    private val emptyHomeWaitMillis: Long = 30_000,
    private val searchWaitMillis: Long = 10_000,
    private val singleWaitMillis: Long = 30_000,
) : AutoCloseable {
    private val jobs = ConcurrentHashMap<CompletableFuture<CatalogSourceOutcome>, Future<*>>()
    @Volatile private var closed = false

    fun collect(targets: List<CatalogSource>, operationName: String,
                lateResult: ((CatalogSourceOutcome) -> Unit)? = null,
                operation: (CatalogSource) -> CatalogSourcePayload): List<CatalogSourceOutcome> {
        if (closed) return emptyList()
        val futures = targets.map { source -> submit(source, operation) }
        val progressive = operationName == "home" && lateResult != null
        val wait = when {
            progressive -> progressiveWaitMillis
            targets.size == 1 -> singleWaitMillis
            operationName == "home" -> initialHomeWaitMillis
            else -> searchWaitMillis
        }
        try {
            await(futures, wait, operationName == "home" && !progressive && targets.size > 1)
            return futures.mapIndexed { index, future ->
                if (future.isDone) outcome(future, targets[index])
                else {
                    if (lateResult == null) cancel(future)
                    else future.thenAccept { if (!closed) lateResult(it) }
                    CatalogSourceOutcome(targets[index], CatalogSourcePayload(), TimeoutException(
                        if (lateResult == null) "Show All stopped waiting for this slow site"
                        else "Show All site is still loading home data"), wait)
                }
            }
        } catch (interrupted: InterruptedException) {
            futures.forEach(::cancel)
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (error: RuntimeException) {
            futures.forEach(::cancel)
            throw error
        }
    }

    @Synchronized private fun submit(source: CatalogSource, operation: (CatalogSource) -> CatalogSourcePayload): CompletableFuture<CatalogSourceOutcome> {
        val result = CompletableFuture<CatalogSourceOutcome>()
        if (closed) { result.cancel(false); return result }
        try {
            val task = FutureTask {
                val started = System.nanoTime()
                try {
                    val outcome = try { CatalogSourceOutcome(source, operation(source), null, elapsed(started)) }
                    catch (error: Exception) {
                        if (error is InterruptedException) Thread.currentThread().interrupt()
                        CatalogSourceOutcome(source, CatalogSourcePayload(), error, elapsed(started))
                    }
                    result.complete(outcome)
                } catch (fatal: Throwable) {
                    result.completeExceptionally(fatal)
                    throw fatal
                } finally { jobs.remove(result) }
            }
            jobs[result] = task
            executor.execute(task)
        } catch (rejected: RejectedExecutionException) {
            jobs.remove(result)
            result.complete(CatalogSourceOutcome(source, CatalogSourcePayload(), rejected, 0))
        }
        return result
    }

    private fun await(futures: List<CompletableFuture<CatalogSourceOutcome>>, initial: Long, extendHome: Boolean) {
        val started = System.nanoTime()
        try { CompletableFuture.allOf(*futures.toTypedArray()).get(initial, TimeUnit.MILLISECONDS); return }
        catch (_: TimeoutException) { /* Completed sources are useful even if one source stalls. */ }
        catch (_: ExecutionException) { return }
        if (!extendHome || futures.anyUseful()) return
        val deadline = started + TimeUnit.MILLISECONDS.toNanos(emptyHomeWaitMillis)
        while (futures.any { !it.isDone } && !futures.anyUseful()) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return
            try { CompletableFuture.anyOf(*futures.filterNot { it.isDone }.toTypedArray()).get(remaining, TimeUnit.NANOSECONDS) }
            catch (_: TimeoutException) { return }
            catch (_: ExecutionException) { /* Continue waiting for another provider. */ }
        }
    }

    private fun List<CompletableFuture<CatalogSourceOutcome>>.anyUseful() = any {
        it.isDone && !it.isCompletedExceptionally && it.join().payload.let { value -> value.items.isNotEmpty() || value.types.isNotEmpty() }
    }
    private fun outcome(future: CompletableFuture<CatalogSourceOutcome>, source: CatalogSource): CatalogSourceOutcome =
        try { future.join() } catch (error: Exception) { CatalogSourceOutcome(source, CatalogSourcePayload(), error, 0) }
    private fun elapsed(started: Long) = (System.nanoTime() - started) / 1_000_000
    private fun cancel(result: CompletableFuture<CatalogSourceOutcome>) { jobs.remove(result)?.cancel(true); result.cancel(true) }
    /** `keys.toList()` reads size before iterating, so a job finishing in between breaks Kotlin's single-element fast path. */
    @Synchronized override fun close() { closed = true; ArrayList(jobs.keys).forEach(::cancel) }
}

data class CatalogSourcePayload(val items: List<CatalogItem> = emptyList(), val types: List<CatalogType> = emptyList())
data class CatalogSourceOutcome(val source: CatalogSource, val payload: CatalogSourcePayload, val failure: Throwable?, val elapsedMillis: Long)
