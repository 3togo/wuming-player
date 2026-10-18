package com.wuming.android.tv.db

import com.wuming.android.tv.bean.SitePerformance

/**
 * [SitePerformanceStore] backed by the Room table plus the in-memory
 * [DataCache] read-through entries.
 */
class RoomSitePerformanceStore : SitePerformanceStore {
    @Throws(Exception::class)
    override fun persist(snapshot: SitePerformance) {
        AppDatabase.get().getSitePerformanceDao().insertOrUpdate(snapshot)
        if (DataCache.isReady()) DataCache.get().putSitePerformance(snapshot)
    }

    @Throws(Exception::class)
    override fun deleteAll() {
        AppDatabase.get().getSitePerformanceDao().deleteAll()
    }

    @Throws(Exception::class)
    override fun delete(siteKey: String) {
        AppDatabase.get().getSitePerformanceDao().deleteBySiteKey(siteKey)
    }

    override fun evictAll() {
        if (DataCache.isReady()) DataCache.get().clearSitePerformance()
    }

    override fun evict(siteKey: String) {
        if (DataCache.isReady()) DataCache.get().removeSitePerformance(siteKey)
    }
}
