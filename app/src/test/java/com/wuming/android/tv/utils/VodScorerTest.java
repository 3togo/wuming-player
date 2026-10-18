package com.wuming.android.tv.utils;

import com.wuming.android.tv.bean.SearchFilter;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.bean.Vod;
import com.wuming.android.tv.search.CollectionWorkflow;
import org.junit.Test;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.Assert.*;

public class VodScorerTest {
    @Test public void preparedSortPreservesProviderIdentityRankPriorityAndCollectionOrder() throws Exception {
        Field entries = VideoRankManager.class.getDeclaredField("entries"); entries.setAccessible(true);
        Object previous = entries.get(null);
        Map<String, VideoRankManager.Entry> ranks = new LinkedHashMap<>(); entries.set(null,ranks);
        try {
            Vod unrelated = vod("a","shared","Different"), exact = vod("b","shared","Needle");
            Vod preferred = vod("c","third","Other"), tie = vod("d","fourth","Needle");
            String key = "quality:" + VideoRankManager.contentKey("c","third");
            ranks.put(key,new VideoRankManager.Entry(key,"Other",5));
            List<Vod> input = List.of(unrelated,exact,preferred,tie);
            List<Vod> expected = new ArrayList<>(input);
            expected.sort((a,b)->VodScorer.compareScore(a,b,"Needle"));
            List<Vod> actual = new ArrayList<>(input); VodScorer.sortByRelevance(actual,"Needle");
            assertOrder(expected,actual); assertSame(preferred,actual.get(0)); assertSame(exact,actual.get(1));
            assertOrder(expected,CollectionWorkflow.filterAndSort(input,new SearchFilter(),"Needle"));
            ranks.clear();
            VodScorer.sortByRelevance(actual,"Needle");
            assertSame(exact,actual.get(0)); assertSame(tie,actual.get(1));
        } finally { entries.set(null,previous); }
    }
    private static void assertOrder(List<Vod> expected,List<Vod> actual) {
        assertEquals(expected.size(),actual.size()); for(int i=0;i<actual.size();i++)assertSame(expected.get(i),actual.get(i));
    }
    private static Vod vod(String site,String id,String title) throws Exception {
        Vod vod=new Vod();vod.setSite(Site.get(site,site));vod.setId(id);vod.setName(title);
        Field name=Vod.class.getDeclaredField("cachedName");name.setAccessible(true);name.set(vod,title);return vod;
    }
}
