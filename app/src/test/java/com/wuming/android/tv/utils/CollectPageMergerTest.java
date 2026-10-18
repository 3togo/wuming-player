package com.wuming.android.tv.utils;

import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.bean.Vod;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class CollectPageMergerTest {

    @Test
    public void paginationAppendsOnlyNewItemsWithoutReplacingExistingList() {
        List<Vod> existing = new ArrayList<>(Arrays.asList(vod("Bili", "1"), vod("Bili", "2")));

        List<Vod> added = CollectPageMerger.appendUnique(existing,
                Arrays.asList(vod("Bili", "2"), vod("Bili", "3"), vod("Bili", "4")));

        assertEquals(Arrays.asList("3", "4"), ids(added));
        assertEquals(Arrays.asList("1", "2", "3", "4"), ids(existing));
    }

    @Test
    public void sameIdFromDifferentSitesIsNotDiscarded() {
        List<Vod> existing = new ArrayList<>(List.of(vod("Bili", "1")));

        List<Vod> added = CollectPageMerger.appendUnique(existing, List.of(vod("Other", "1")));

        assertEquals(1, added.size());
        assertEquals(2, existing.size());
    }

    private static Vod vod(String siteKey, String id) {
        Vod vod = new Vod();
        vod.setId(id);
        vod.setSite(Site.get(siteKey, siteKey));
        return vod;
    }

    private static List<String> ids(List<Vod> items) {
        List<String> ids = new ArrayList<>();
        for (Vod vod : items) ids.add(vod.getId());
        return ids;
    }
}
