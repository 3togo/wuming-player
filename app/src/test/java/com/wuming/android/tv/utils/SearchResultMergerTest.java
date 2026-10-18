package com.wuming.android.tv.utils;

import static org.junit.Assert.assertEquals;

import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.bean.Vod;

import org.junit.Test;

import java.util.List;

public class SearchResultMergerTest {

    @Test
    public void canonicalAliasMergesEnglishAndChineseResults() {
        Vod english = vod("Infernal Affairs", "2002", "one");
        english.setSearchIdentity("douban:1307914", List.of("Infernal Affairs", "无间道"),
                "Infernal Affairs", "Infernal Affairs");
        Vod chinese = vod("无间道", "2002", "two");
        chinese.setSearchIdentity("douban:1307914", List.of("Infernal Affairs", "无间道"),
                "Infernal Affairs", "无间道");

        List<Vod> merged = SearchResultMerger.merge(List.of(english, chinese));

        assertEquals(1, merged.size());
        assertEquals(2, merged.get(0).getSourceCount());
    }

    @Test
    public void sameTitleFromDifferentYearsIsNotMerged() {
        assertEquals(2, SearchResultMerger.merge(List.of(
                vod("The Thing", "1951", "one"),
                vod("The Thing", "1982", "two"))).size());
    }

    private static Vod vod(String name, String year, String siteKey) {
        Vod vod = new Vod();
        vod.setName(name);
        vod.setSite(Site.get(siteKey, siteKey));
        setField(vod, "cachedName", name);
        setField(vod, "vodYear", year);
        return vod;
    }

    private static void setField(Vod vod, String name, Object value) {
        try {
            java.lang.reflect.Field field = Vod.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(vod, value);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }
}
