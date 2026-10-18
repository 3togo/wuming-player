package com.wuming.tv.core.source

import java.util.concurrent.TimeUnit

/** Cadence, retry budget and merge rules for persisting tracked site performance. */
object SitePersistencePolicy {

    /** Minimum interval between batched storage writes. */
    @JvmField
    val SAVE_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5)

    /** Initial delay before the first scheduled flush. */
    @JvmField
    val INITIAL_SAVE_DELAY_MS = TimeUnit.SECONDS.toMillis(30)

    /** Maximum consecutive write failures for a key before it is dropped. */
    const val MAX_RETRY_FAILURES = 3

    /** Milliseconds left before another flush may start, or `0` when it is due. */
    @JvmStatic
    fun remainingMillis(nowMillis: Long, lastSaveMillis: Long): Long {
        val elapsed = nowMillis - lastSaveMillis
        if (elapsed < 0L) return SAVE_INTERVAL_MS
        return if (elapsed >= SAVE_INTERVAL_MS) 0L else SAVE_INTERVAL_MS - elapsed
    }

    /** Whether a key that already failed `failures` times may be requeued. */
    @JvmStatic
    fun shouldRetry(failures: Int): Boolean = failures < MAX_RETRY_FAILURES

    /** A record without a single request carries no usable history. */
    @JvmStatic
    fun hasHistory(totalRequests: Long): Boolean = totalRequests > 0L

    /** Stored history is adopted only while the live record is still empty. */
    @JvmStatic
    fun shouldAdopt(liveTotalRequests: Long, storedTotalRequests: Long): Boolean {
        return hasHistory(storedTotalRequests) && !hasHistory(liveTotalRequests)
    }
}
