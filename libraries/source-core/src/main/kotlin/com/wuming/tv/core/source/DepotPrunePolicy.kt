package com.wuming.tv.core.source

/**
 * Pure, Android-free rules for the depot source list. Kept out of DepotManager so the
 * non-destructive behaviour can be unit-tested under plain JUnit (DepotManager pulls in OkHttp,
 * App, etc., which are not mockable in a JVM test).
 */
object DepotPrunePolicy {
    /**
     * A source is only removed after this many consecutive failed checks. One failed fetch is far more
     * often a 403/rate-limit/DNS blip than a dead source, and the depot is a reference list, so losing
     * an entry costs more than keeping a stale one.
     */
    const val PRUNE_AFTER_FAILS = 3

    /** Consecutive-failure counter: resets to 0 on success. */
    @JvmStatic
    fun nextFailCount(previous: Int, ok: Boolean): Int = if (ok) 0 else previous + 1

    /** True while the entry should stay in the list. */
    @JvmStatic
    fun keep(failCount: Int): Boolean = failCount < PRUNE_AFTER_FAILS
}
