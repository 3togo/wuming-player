package com.wuming.tv.core.cache

/** Keeps a bounded idle set and defers disposal until the last active caller releases a resource. */
class LeasedResourceCache<K : Any, V : Any>(
    private val idleLimit: Int,
    private val dispose: (V) -> Unit,
) {
    private class Entry<K, V>(val key: K) {
        @Volatile var value: V? = null
        var users = 0
    }

    private val entries = LinkedHashMap<K, Entry<K, V>>(16, 0.75f, true)
    private var pinned: Entry<K, V>? = null

    init { require(idleLimit >= 0) }

    fun <R> use(key: K, create: () -> V, operation: (V, () -> Unit) -> R): R {
        val entry = synchronized(this) {
            entries.getOrPut(key) { Entry(key) }.also { it.users++ }
        }
        try {
            val value = synchronized(entry) { entry.value ?: create().also { entry.value = it } }
            return operation(value) {
                synchronized(this) {
                    if (entries[entry.key] === entry) pinned = entry
                }
            }
        } finally {
            release(entry)
        }
    }

    fun <R> useExisting(key: K?, matchesPinned: (K) -> Boolean, operation: (V) -> R): R? {
        val entry = synchronized(this) {
            val candidate = pinned?.takeIf { matchesPinned(it.key) } ?: key?.let { entries[it] }
            candidate?.takeIf { it.value != null }?.also { it.users++ }
        } ?: return null
        try {
            return operation(checkNotNull(entry.value))
        } finally {
            release(entry)
        }
    }

    fun clear() {
        val retired = synchronized(this) {
            entries.values.filter { it.users == 0 }.mapNotNull { it.value }.also {
                entries.clear()
                pinned = null
            }
        }
        retired.forEach(dispose)
    }

    fun trim() {
        synchronized(this) { evictIdle(0) }.forEach(dispose)
    }

    private fun release(entry: Entry<K, V>) {
        val retired = synchronized(this) {
            entry.users--
            if (entry.users != 0) emptyList()
            else if (entries[entry.key] !== entry) listOfNotNull(entry.value)
            else if (entry.value == null) {
                entries.remove(entry.key)
                emptyList()
            } else evictIdle(idleLimit)
        }
        retired.forEach(dispose)
    }

    private fun evictIdle(limit: Int): List<V> {
        val idle = entries.values.filter { it.users == 0 && it !== pinned }
        return idle.take((idle.size - limit).coerceAtLeast(0)).mapNotNull {
            entries.remove(it.key)
            it.value
        }
    }
}
