package com.wuming.tv.core.cache

import java.util.LinkedHashMap
import java.util.Objects
import java.util.concurrent.TimeUnit
import java.util.function.LongSupplier
import java.util.function.ToLongFunction

/**
 * Bounded process-local cache. Expiry uses elapsed time, independent of wall-clock changes.
 *
 * Entries are evicted by entry count and, optionally, by total weight. Weight defaults to one
 * per entry, so the count-only constructors keep their original meaning. A value whose own weight
 * exceeds the weight budget is simply not retained.
 */
class ExpiringLruCache<K, V> internal constructor(
    private val capacity: Int,
    private val ttlMillis: Long,
    private val clock: LongSupplier,
    private val weigher: ToLongFunction<V>,
    private val maximumWeight: Long
) {
    private val entries = LinkedHashMap<K, Entry<K, V>>(8, 0.75f, true)
    private var totalWeight = 0L

    constructor(capacity: Int, ttlMillis: Long) : this(
        capacity, ttlMillis, LongSupplier { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) }
    )

    constructor(capacity: Int, ttlMillis: Long, clock: LongSupplier) : this(
        capacity, ttlMillis, clock, ToLongFunction { 1L }, capacity.toLong()
    )

    constructor(capacity: Int, ttlMillis: Long, weigher: ToLongFunction<V>, maximumWeight: Long) : this(
        capacity, ttlMillis, LongSupplier { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) }, weigher, maximumWeight
    )

    init {
        if (capacity <= 0 || ttlMillis <= 0) throw IllegalArgumentException("Positive capacity and TTL required")
        if (maximumWeight <= 0) throw IllegalArgumentException("Positive maximum weight required")
        @Suppress("LeakingThis")
        Objects.requireNonNull(clock)
        @Suppress("LeakingThis")
        Objects.requireNonNull(weigher)
    }

    @Synchronized
    fun get(key: K): V? {
        val entry = entries[key] ?: return null
        if (isExpired(entry, clock.asLong)) {
            removeEntry(entry)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun remove(key: K): V? {
        val entry = entries.remove(key) ?: return null
        totalWeight -= entry.weight
        return if (isExpired(entry, clock.asLong)) null else entry.value
    }

    @Synchronized
    fun put(key: K, value: V) {
        Objects.requireNonNull(value)
        trim()
        val weight = weigher.applyAsLong(value)
        if (weight < 0L) throw IllegalArgumentException("Entry weight must not be negative")
        removeExisting(key)
        // A value which alone breaks the budget must not evict everything else.
        if (weight > maximumWeight) return
        evictBeforeInsert(weight)
        val entry = Entry(key, value, weight, clock.asLong)
        entries[key] = entry
        totalWeight += entry.weight
    }

    @Synchronized
    fun trim() {
        val now = clock.asLong
        entries.values.removeIf { entry ->
            if (!isExpired(entry, now)) return@removeIf false
            totalWeight -= entry.weight
            true
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        totalWeight = 0L
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun totalWeight(): Long = totalWeight

    private fun removeExisting(key: K) {
        val previous = entries.remove(key)
        if (previous != null) totalWeight -= previous.weight
    }

    private fun removeEntry(entry: Entry<K, V>) {
        entries.remove(entry.key)
        totalWeight -= entry.weight
    }

    private fun evictBeforeInsert(weight: Long) {
        while (entries.isNotEmpty() && (entries.size >= capacity || totalWeight > maximumWeight - weight)) {
            val eldest = entries.entries.iterator().next()
            val entry = eldest.value
            entries.remove(eldest.key)
            totalWeight -= entry.weight
        }
    }

    private fun isExpired(entry: Entry<K, V>, now: Long): Boolean {
        val age = now - entry.created
        return age < 0L || age >= ttlMillis
    }

    private class Entry<K, V>(val key: K, val value: V, val weight: Long, val created: Long)
}
