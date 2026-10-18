package com.wuming.android.tv.bean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import com.wuming.tv.core.catalog.ShortNameCachePolicy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure-JVM coverage for the shared {@link ShortNameCachePolicy} algorithms. The Android-backed
 * {@code compute}/{@code lookup} paths run through {@link EpisodeShortNameTest} at runtime;
 * here we lock in the two pieces that have real logic: input normalization and the
 * insertion-ordered (oldest-first) eviction.
 */
public class ShortNameCacheTest {

    @Test
    public void normalizeTrimsAndNullGuards() {
        assertEquals("", ShortNameCachePolicy.normalize(null));
        assertEquals("", ShortNameCachePolicy.normalize("   "));
        assertEquals("庆余年", ShortNameCachePolicy.normalize("  庆余年  "));
    }

    @Test
    public void evictionIsInsertionOrderedOldestFirst() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("a", "1");
        map.put("b", "2");
        map.put("c", "3");
        // maxEntries = 2 -> drop the oldest ("a")
        ShortNameCachePolicy.evictIfNeeded(map, 2);
        assertEquals(2, map.size());
        assertFalse(map.containsKey("a"));
        assertEquals("2", map.get("b"));
        assertEquals("3", map.get("c"));
    }

    @Test
    public void evictionUnderExactCapacityKeepsAll() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("a", "1");
        map.put("b", "2");
        ShortNameCachePolicy.evictIfNeeded(map, 2);
        assertEquals(2, map.size());
        assertEquals("1", map.get("a"));
    }

    @Test
    public void evictionDropsOnlyWhatIsNeeded() {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) map.put("k" + i, "v" + i);
        ShortNameCachePolicy.evictIfNeeded(map, 3);
        assertEquals(3, map.size());
        // oldest two gone, newest three kept in order
        assertFalse(map.containsKey("k0"));
        assertFalse(map.containsKey("k1"));
        assertEquals("v2", map.get("k2"));
        assertEquals("v3", map.get("k3"));
        assertEquals("v4", map.get("k4"));
    }
}
