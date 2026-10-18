package com.wuming.tv.core.cache

/**
 * Platform-neutral cache math shared by every client. Both the desktop in-memory result cache and the
 * desktop disk cache use the same freshness and bounded-eviction rules so a cache-policy fix applies
 * uniformly instead of drifting between implementations.
 */
object BoundedCachePolicy {
    /** True when [writtenMillis] is in the future or older than [lifetimeMillis]. */
    @JvmStatic
    fun isExpired(writtenMillis: Long, nowMillis: Long, lifetimeMillis: Long): Boolean {
        val age = nowMillis - writtenMillis
        return age < 0 || age >= lifetimeMillis
    }

    /** Removes the eldest entries from an access-order [LinkedHashMap] until it fits [maxEntries]. */
    @JvmStatic
    fun <K, V> evictToLimit(map: LinkedHashMap<K, V>, maxEntries: Int) {
        val excess = map.size - maxEntries
        if (excess <= 0) return
        val iterator = map.keys.iterator()
        repeat(excess) {
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }
}
