package com.wuming.tv.core.catalog

/** One paced retry for transient failures, with a bounded, header-sensitive cooldown. */
class ThumbnailRetryPolicy<K>(private val clock: () -> Long) {
    private val blockedUntil = object : LinkedHashMap<K, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Long>?): Boolean = size > 500
    }
    private var nextRetryAt = 0L

    @Synchronized
    fun isBlocked(key: K): Boolean {
        val until = blockedUntil[key] ?: return false
        if (clock() < until) return true
        blockedUntil.remove(key)
        return false
    }

    @Synchronized
    fun failed(key: K, transient: Boolean, alreadyRetried: Boolean): Long? {
        if (isBlocked(key)) return null
        val now = clock()
        blockedUntil[key] = now + COOLDOWN_MS
        if (!transient || alreadyRetried) return null
        val at = maxOf(now + DELAY_MS, nextRetryAt)
        // Do not queue an entire failed catalog; leave excess cards on their fallback.
        if (at - now > MAX_DELAY_MS) return null
        nextRetryAt = at + SPACING_MS
        return at - now
    }

    @Synchronized
    fun succeeded(key: K) { blockedUntil.remove(key) }

    @Synchronized
    fun clear() { blockedUntil.clear(); nextRetryAt = 0L }

    companion object {
        const val DELAY_MS = 15_000L
        const val SPACING_MS = 5_000L
        const val MAX_DELAY_MS = 50_000L
        const val COOLDOWN_MS = 300_000L

        fun isTransient(httpStatus: Int?, transportFailure: Boolean): Boolean =
            if (httpStatus != null && httpStatus > 0) httpStatus == 408 || httpStatus in 500..599
            else transportFailure
    }
}
