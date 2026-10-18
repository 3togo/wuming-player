package com.wuming.android.tv.db

import android.os.Looper
import com.wuming.android.tv.bean.SitePerformance
import com.wuming.android.tv.utils.AppLog

/** UI reads only consult memory, regardless of cache readiness. IO reads may hydrate it. */
object SitePerformanceRepository {
    @JvmStatic
    fun findBySiteKey(siteKey: String): SitePerformance? {
        if (siteKey.isEmpty()) return null
        return try {
            readOne(siteKey, Looper.myLooper() == Looper.getMainLooper(), DataCache.get(), ::loadStored)
        } catch (failure: Exception) {
            AppLog.w("SitePerformanceRepository", "Performance hydration failed", failure)
            null
        }
    }

    /** Strict IO boundary: an unavailable database is never interpreted as an empty baseline. */
    @JvmStatic
    fun loadStored(siteKey: String): SitePerformance? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Performance hydration requires IO" }
        return AppDatabase.get().getSitePerformanceDao().findBySiteKey(siteKey)
    }

    @JvmStatic
    fun findAll(): List<SitePerformance> {
        return try {
            readAll(Looper.myLooper() == Looper.getMainLooper(), DataCache.get()) {
                AppDatabase.get().getSitePerformanceDao().findAll()
            }
        } catch (failure: Exception) {
            AppLog.w("SitePerformanceRepository", "Performance hydration failed", failure)
            DataCache.get().getAllSitePerformance()
        }
    }

    @JvmStatic
    fun findBySiteKeys(siteKeys: Set<String>): Map<String, SitePerformance> =
        siteKeys.mapNotNull { key -> findBySiteKey(key)?.let { key to it } }.toMap()
    internal fun readOne(key: String, onMain: Boolean, cache: DataCache,
                         read: (String) -> SitePerformance?): SitePerformance? {
        cache.getSitePerformance(key)?.let { return it }
        if (onMain) return null
        return read(key)?.also { cache.putSitePerformance(it) }
    }

    internal fun readAll(onMain: Boolean, cache: DataCache,
                         read: () -> List<SitePerformance>): List<SitePerformance> {
        if (onMain) return cache.getAllSitePerformance()
        return read().also { all -> all.forEach { cache.putSitePerformance(it) } }
    }
}
