package com.wuming.android.tv.bean;

import static org.junit.Assert.*;

import com.google.gson.Gson;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class FilterCacheTest {
    private final Gson gson = new Gson();

    @Before public void clearBefore() { FilterCache.clear(); }
    @After public void clearAfter() { FilterCache.clear(); }

    @Test public void categoryCachePreservesMissingAndExplicitAllDefaultsIndependently() {
        Class movie = gson.fromJson("{\"type_id\":\"movie\",\"filters\":[{\"key\":\"type\",\"value\":[{\"v\":\"movie_hot\"}]}]}", Class.class);
        Class tv = gson.fromJson("{\"type_id\":\"tv\",\"filters\":[{\"key\":\"type\",\"init\":\"\",\"value\":[{\"v\":\"\"}]}]}", Class.class);
        Result result = new Result();
        result.setTypes(List.of(movie, tv));
        FilterCache.clear().put(result);

        assertNull(FilterCache.get(movie).get(0).getInit());
        assertEquals("", FilterCache.get(tv).get(0).getInit());
        List<Filter> movieCopy = FilterCache.copy("movie");
        List<Filter> tvCopy = FilterCache.copy("tv");
        assertNull(movieCopy.get(0).getInit());
        assertEquals("", tvCopy.get(0).getInit());
        tvCopy.get(0).setActivated("");
        assertFalse(FilterCache.get(tv).get(0).getValue().get(0).isActivated());
        assertFalse(movieCopy.get(0).getValue().get(0).isActivated());
        assertTrue(FilterCache.get("unknown").isEmpty());
    }

    @Test public void newSourceReplacesCategoryDefaultsWithoutKeepingOldSelections() {
        Result oldSource = new Result();
        oldSource.setTypes(List.of(gson.fromJson("{\"type_id\":\"tv\",\"filters\":[{\"key\":\"type\",\"init\":\"\"}]}", Class.class)));
        FilterCache.clear().put(oldSource);
        Result newSource = new Result();
        newSource.setTypes(List.of(gson.fromJson("{\"type_id\":\"tv\",\"filters\":[{\"key\":\"type\"}]}", Class.class)));
        FilterCache.clear().put(newSource);
        assertNull(FilterCache.get("tv").get(0).getInit());
    }
    @Test public void wireMapWithoutInlineCategoriesRecoversAfterEviction() throws Exception {
        Result retained = new Result();
        retained.setTypes(List.of(gson.fromJson("{\"type_id\":\"movie\"}", Class.class),
                gson.fromJson("{\"type_id\":\"tv\"}", Class.class)));
        Filter movie = gson.fromJson("{\"key\":\"genre\",\"value\":[{\"n\":\"Comedy\",\"v\":\"comedy\"}]}", Filter.class);
        Filter tv = gson.fromJson("{\"key\":\"year\",\"init\":\"\",\"value\":[{\"n\":\"All\",\"v\":\"\"}]}", Filter.class);
        java.lang.reflect.Field wireMap = Result.class.getDeclaredField("filters");
        wireMap.setAccessible(true);
        wireMap.set(retained, new java.util.LinkedHashMap<>(java.util.Map.of(
                "movie", List.of(movie), "tv", List.of(tv))));
        for (int eviction = 0; eviction < 2; eviction++) {
            FilterCache.clear();
            assertTrue(FilterCache.get("movie").isEmpty());
            FilterCache.putResult(retained);
            assertEquals(1, FilterCache.get("movie").size());
            assertEquals(1, FilterCache.get("tv").size());
            assertNull(FilterCache.copy("movie").get(0).getInit());
            assertEquals("", FilterCache.copy("tv").get(0).getInit());
            FilterCache.copy("movie").get(0).getValue().get(0).setV("changed");
            assertEquals("comedy", retained.getFilters().get("movie").get(0).getValue().get(0).getV());
        }
        assertTrue(FilterCache.get("unknown").isEmpty());
    }

    @Test public void partialResultsDoNotEraseLearnedFilters() {
        Result populated = new Result();
        populated.setTypes(List.of(gson.fromJson("{\"type_id\":\"movie\",\"filters\":[{\"key\":\"genre\"}]}", Class.class)));
        FilterCache.putResult(populated);
        Result partial = new Result();
        partial.setTypes(List.of(gson.fromJson("{\"type_id\":\"movie\"}", Class.class)));
        FilterCache.putResult(partial);
        assertEquals("genre", FilterCache.get("movie").get(0).getKey());
    }

}
