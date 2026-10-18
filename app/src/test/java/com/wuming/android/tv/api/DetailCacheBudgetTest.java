package com.wuming.android.tv.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.wuming.tv.core.cache.MemoryBudget;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

public class DetailCacheBudgetTest {

    @Test
    public void keepsTheTenMinuteDetailWindow() {
        assertEquals(TimeUnit.MINUTES.toMillis(10), SiteApi.DetailCacheBudget.TTL_MILLIS);
    }

    @Test
    public void usesOneMeasuredHeapTierForEntriesAndBytes() {
        MemoryBudget budget = MemoryBudget.current();

        assertEquals(budget.scale(100, 200, 400), SiteApi.DetailCacheBudget.MAX_ENTRIES);
        assertTrue(SiteApi.DetailCacheBudget.MAX_TOTAL_BYTES > 0);
        assertTrue(SiteApi.DetailCacheBudget.MAX_TOTAL_BYTES <= budget.maxMemoryBytes());
        assertTrue(SiteApi.DetailCacheBudget.MAX_SNAPSHOT_BYTES < SiteApi.DetailCacheBudget.MAX_TOTAL_BYTES);
    }

    @Test
    public void refusesOnlySnapshotsOverThePerEntryCeiling() {
        long ceiling = SiteApi.DetailCacheBudget.MAX_SNAPSHOT_BYTES;

        assertTrue(SiteApi.DetailCacheBudget.accepts(0));
        assertTrue(SiteApi.DetailCacheBudget.accepts(ceiling));
        assertFalse(SiteApi.DetailCacheBudget.accepts(ceiling + 1));
    }

    @Test
    public void startsEmptyAndClearsOnConfigReload() {
        com.wuming.tv.core.cache.ExpiringLruCache<String, SiteApi.DetailCacheEntry> cache =
                SiteApi.DetailCacheBudget.newCache();

        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(0L, cache.totalWeight());
    }
}
