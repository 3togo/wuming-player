package com.wuming.android.tv.search;

import static org.junit.Assert.assertEquals;

import com.wuming.android.tv.bean.Vod;
import com.wuming.tv.core.search.SearchQueryPlan;
import com.wuming.tv.core.search.SearchQueryPlanner;
import com.wuming.tv.core.search.TitleIdentity;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class SearchResultAnnotatorTest {

    @Test
    public void strictModeKeepsExactAliasesAndMetadataSuffixesOnly() {
        TitleIdentity identity = new TitleIdentity(
                "wikidata:Q207954",
                List.of("Pretty Woman", "风月俏佳人", "漂亮女人", "麻雀变凤凰"),
                "1990",
                "movie");
        SearchQueryPlan plan = new SearchQueryPlanner(
                query -> identity, TitleNormalizer.core()).plan("Pretty Woman");
        SearchQueryPlan.Variant english = plan.variants().get(0);
        List<Vod> items = new ArrayList<>(List.of(
                vod("Pretty Woman"),
                vod("风月俏佳人 (1990)"),
                vod("漂亮女人"),
                vod("Pretty Little Liars"),
                vod("A Pretty Woman Documentary")));

        SearchResultAnnotator.annotate(items, plan, english);
        SearchResultAnnotator.retainStrictMatches(items, plan, english);

        assertEquals(List.of("Pretty Woman", "风月俏佳人 (1990)", "漂亮女人"),
                items.stream().map(Vod::getName).toList());
    }

    @Test
    public void strictModeUsesTraditionalSimplifiedEquivalence() {
        SearchQueryPlan plan = SearchQueryPlanner.localOnly(TitleNormalizer.core()).plan("無間道");
        SearchQueryPlan.Variant traditional = plan.variants().get(0);
        List<Vod> items = new ArrayList<>(List.of(vod("无间道"), vod("无间道风云")));

        SearchResultAnnotator.annotate(items, plan, traditional);
        SearchResultAnnotator.retainStrictMatches(items, plan, traditional);

        assertEquals(List.of("无间道"), items.stream().map(Vod::getName).toList());
    }

    private static Vod vod(String name) {
        Vod vod = new Vod();
        vod.setName(name);
        try {
            Field field = Vod.class.getDeclaredField("cachedName");
            field.setAccessible(true);
            field.set(vod, name);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
        return vod;
    }
}
