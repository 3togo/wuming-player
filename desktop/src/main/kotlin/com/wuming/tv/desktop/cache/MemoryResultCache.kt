package com.wuming.tv.desktop.cache

import com.wuming.tv.core.cache.BoundedCachePolicy
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/** Bounded LRU of successful results; in-flight work is shared, invalidation fences old writers. */
internal class MemoryResultCache<K, V>(
    private val maximumEntries: Int,
    private val lifetime: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    private data class Entry<V>(val value: V, val written: Long)
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    private val pending = mutableMapOf<K, CompletableFuture<V>>()
    private var generation = 0L

    fun get(key: K, compute: () -> V): V {
        val future: CompletableFuture<V>
        val started: Long
        val owner: Boolean
        synchronized(this) {
            entries[key]?.let {
                if (!BoundedCachePolicy.isExpired(it.written, clock.millis(), lifetime.toMillis())) return it.value
                entries.remove(key)
            }
            started = generation
            val previous = pending[key]
            owner = previous == null
            future = previous ?: CompletableFuture<V>().also { pending[key] = it }
        }
        if (!owner) return try { future.get() } catch (error: ExecutionException) { throw error.cause ?: error }
        try {
            val value = compute()
            synchronized(this) {
                if (generation == started) {
                    entries[key] = Entry(value, clock.millis())
                    BoundedCachePolicy.evictToLimit(entries, maximumEntries)
                }
            }
            future.complete(value)
            return value
        } catch (failure: Throwable) {
            future.completeExceptionally(failure)
            throw failure
        } finally {
            synchronized(this) { if (pending[key] === future) pending.remove(key) }
        }
    }

    @Synchronized
    fun clear() {
        generation++
        entries.clear()
        val cancelled = java.util.concurrent.CancellationException("Cache cleared")
        for (future in pending.values) future.completeExceptionally(cancelled)
        pending.clear()
    }
}
