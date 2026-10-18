package com.wuming.android.tv.search;

import static org.junit.Assert.assertEquals;

import com.wuming.android.tv.bean.Collect;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.bean.Vod;
import com.wuming.tv.core.search.SearchQueryPlan;
import com.wuming.tv.core.search.SearchQueryPlanner;
import com.wuming.tv.core.search.TitleIdentity;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class CollectionWorkflowTest {

    @Test
    public void strictAllKeepsSameTitleFromNuomiAndOtherProviders() {
        SearchQueryPlan plan = new SearchQueryPlanner(query -> new TitleIdentity(
                "title:early-spring", List.of("早春晴朗"), "2026", "tv"),
                TitleNormalizer.core()).plan("早春晴朗");
        Vod director = candidate("director", "1", "早春晴朗");
        Vod nuomi = candidate("nuomi", "1", "早春晴朗");
        List<Vod> incoming = new ArrayList<>(List.of(director, nuomi,
                candidate("nuomi", "unrelated", "另一部剧")));
        SearchResultAnnotator.annotate(incoming, plan, plan.variants().get(0));
        SearchResultAnnotator.retainStrictMatches(incoming, plan, plan.variants().get(0));

        List<Vod> all = CollectionWorkflow.mergeCandidates(List.of(), incoming);

        assertEquals(List.of(director, nuomi), all);
        assertEquals("nuomi", all.get(1).getSiteKey());
        assertEquals("1", all.get(1).getId());
    }

    @Test
    public void repeatedBatchesKeepDistinctPlayableIdsWithoutDuplicatingCards() {
        Vod first = candidate("nuomi", "1", "早春晴朗");
        Vod second = candidate("nuomi", "2", "早春晴朗");
        List<Vod> existing = List.of(first);

        List<Vod> all = CollectionWorkflow.mergeCandidates(existing, List.of(
                candidate("nuomi", "1", "早春晴朗"), second));
        all = CollectionWorkflow.mergeCandidates(all, List.of(second));

        assertEquals(List.of(first, second), all);
        assertEquals(List.of(first), existing);
    }

    private static Vod candidate(String site, String id, String name) {
        Vod vod = new Vod();
        vod.setSite(Site.get(site, site));
        vod.setId(id);
        vod.setName(name);
        try {
            java.lang.reflect.Field field = Vod.class.getDeclaredField("cachedName");
            field.setAccessible(true);
            field.set(vod, name);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
        return vod;
    }

    @Test
    public void paginationUsesMatchedProviderQueryWhenAvailable() {
        Vod vod = new Vod();
        vod.setSite(Site.get("site", "Site"));
        vod.setSearchIdentity("", List.of(), "fallback", "canonical title");
        Collect collect = new Collect(vod.getSite(), List.of(vod));

        assertEquals("canonical title", CollectionWorkflow.pageQuery(collect, "fallback"));
        assertEquals("fallback", CollectionWorkflow.pageQuery(null, "fallback"));
    }
}
