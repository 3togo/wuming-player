package com.wuming.tv.core.cache

import java.util.LinkedHashMap
import java.util.function.BiPredicate
import java.util.function.Predicate

/**
 * Thread-safe LRU for reference metadata that a reloadable owner can rebuild on demand.
 *
 * Process-long owners that clear their entire map when it reaches a limit make every item
 * already shown by the UI expire at the same time. Evicting only the least recently used entry
 * keeps memory bounded without causing that failure cascade.
 */
open class BoundedReferenceCache<K, V>(private val maxEntries: Int) {

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val values = LinkedHashMap<K, V>(16, 0.75f, true)

    @Synchronized
    open fun get(key: K): V? = values[key]

    @Synchronized
    open fun put(key: K, value: V) {
        values[key] = value
        while (values.size > maxEntries) {
            val eldest = values.keys.iterator().next()
            values.remove(eldest)
        }
    }

    @Synchronized
    open fun clear() {
        values.clear()
    }

    @Synchronized
    open fun remove(key: K): V? = values.remove(key)

    /** Removes the mapping only when it still holds [value], compared by identity then equality. */
    @Synchronized
    open fun remove(key: K, value: V): Boolean {
        val current = values[key]
        if (current !== value && (current == null || current != value)) return false
        values.remove(key)
        return true
    }

    @Synchronized
    open fun removeKeysIf(predicate: Predicate<K>) {
        values.keys.removeIf(predicate)
    }

    @Synchronized
    open fun removeEntriesIf(predicate: BiPredicate<K, V>) {
        values.entries.removeIf { predicate.test(it.key, it.value) }
    }

    @Synchronized
    open fun size(): Int = values.size

    @Synchronized
    open fun isEmpty(): Boolean = values.isEmpty()
}
