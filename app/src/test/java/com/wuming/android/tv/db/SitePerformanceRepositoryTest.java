package com.wuming.android.tv.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.wuming.android.tv.bean.SitePerformance;

import org.junit.Test;

import java.util.Collections;

public class SitePerformanceRepositoryTest {

    @Test
    public void findBySiteKeyReturnsNullForEmptyKey() {
        assertNull(SitePerformanceRepository.findBySiteKey(""));
    }

    @Test
    public void findBySiteKeysReturnsEmptyMapForEmptySet() {
        assertEquals(Collections.emptyMap(), SitePerformanceRepository.findBySiteKeys(Collections.emptySet()));
    }
}
