package com.wuming.android.tv.db

import com.wuming.android.tv.bean.SitePerformance

/**
 * Write side of site performance persistence.
 *
 * Deliberately write-only. Readers keep receiving the tracker's live records on purpose: a
 * flush lags the increments it stores, so routing reads through this port would hand out the last
 * snapshot and make ranking blind to everything recorded since it.
 *
 * Cache eviction is split from row deletion because the tracker evicts synchronously on the
 * caller thread and deletes on the storage executor. A port that bundled both into one call would
 * let a reader refill the cache from a row that a clear had not removed yet.
 */
interface SitePerformanceStore {
    /** Stores one detached snapshot and refreshes its read-through cache entry. */
    @Throws(Exception::class)
    fun persist(snapshot: SitePerformance)

    /** Removes every stored record. */
    @Throws(Exception::class)
    fun deleteAll()

    /** Removes one stored record. */
    @Throws(Exception::class)
    fun delete(siteKey: String)

    /** Drops every read-through cache entry, leaving stored rows untouched. */
    fun evictAll()

    /** Drops one read-through cache entry, leaving the stored row untouched. */
    fun evict(siteKey: String)
}
