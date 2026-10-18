package com.wuming.android.tv.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.bean.Vod;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;

public class FastSearchCoordinatorTest {

    @Test
    public void filtersSourcesGroupsResultsAndRetainsRankOrder() {
        Site first = Site.get("first", "First");
        first.setSearchable(1);
        Site second = Site.get("second", "Second");
        second.setSearchable(1);
        FastSearchCoordinator coordinator = new FastSearchCoordinator();

        assertEquals(List.of(first), coordinator.begin(List.of(first, second), Map.of("first", "1")));
        coordinator.add(List.of(vod(first, "one"), vod(first, "two")));

        assertEquals(2, coordinator.selectSource("First").size());
        coordinator.showAllSources();
        assertEquals(2, coordinator.selectSource("First").size());
        assertEquals("First", coordinator.sourceName("first"));
    }

    @Test
    public void indexedRanksMatchLinearOrderWithUnknownsAndReset() {
        List<Site> sites = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            Site site = Site.get("source-" + (i == 1 ? 0 : i), "Source " + i); site.setSearchable(1); sites.add(site);
        }
        FastSearchCoordinator coordinator = new FastSearchCoordinator();
        List<Site> ranked = coordinator.begin(sites, null);
        List<Vod> values = new ArrayList<>();
        for (int i = 79; i >= 0; i--) { values.add(vod(sites.get(i), "a")); values.add(vod(sites.get(i), "b")); }
        Vod unknownA = vod(Site.get("unknown-a", "Unknown A"), "a");
        Vod unknownB = vod(Site.get("unknown-b", "Unknown B"), "b");
        values.add(unknownA); values.add(unknownB);
        List<Vod> expected = new ArrayList<>(values);
        expected.sort(Comparator.comparingInt(value -> {
            for (int i = 0; i < ranked.size(); i++) if (ranked.get(i).getKey().equals(value.getSite().getKey())) return i;
            return Integer.MAX_VALUE;
        }));
        values.sort(coordinator.rankingComparator());
        for (int i = 0; i < values.size(); i++) assertSame(expected.get(i), values.get(i));
        coordinator.reset();
        assertEquals(0, coordinator.rankingComparator().compare(values.get(0), unknownA));
        coordinator.begin(List.of(sites.get(0)), null);
        assertEquals(0, coordinator.rankingComparator().compare(vod(sites.get(2), "old"), unknownB));
    }

    private static Vod vod(Site site, String id) {
        Vod vod = new Vod();
        vod.setSite(site);
        vod.setId(id);
        return vod;
    }
}
