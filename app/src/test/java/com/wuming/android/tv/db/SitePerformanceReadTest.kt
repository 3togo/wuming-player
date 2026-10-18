package com.wuming.android.tv.db

import com.wuming.android.tv.bean.SitePerformance
import org.junit.Assert.*
import org.junit.Test

class SitePerformanceReadTest {
    @Test fun uiReadsNeverReachDaoForReadyOrUnreadyCaches() {
        for (ready in listOf(false, true)) {
            val cache = DataCache { it.run() }
            DataCache::class.java.getDeclaredField("ready").apply { isAccessible = true }.set(cache, ready)
            val existing = SitePerformance("hit")
            cache.putSitePerformance(existing)
            val forbidden: (String) -> SitePerformance? = { error("UI DAO call") }
            assertSame(existing, SitePerformanceRepository.readOne("hit", true, cache, forbidden))
            assertNull(SitePerformanceRepository.readOne("miss", true, cache, forbidden))
            assertEquals(listOf(existing), SitePerformanceRepository.readAll(true, cache) { error("UI DAO call") })
        }
    }
    @Test fun workerHydratesCacheAndUiSeesTheLoadedValue() {
        val cache = DataCache { it.run() }
        val stored = SitePerformance("site")
        assertSame(stored, SitePerformanceRepository.readOne("site", false, cache) { stored })
        assertSame(stored, SitePerformanceRepository.readOne("site", true, cache) { error("UI DAO call") })
    }
}
