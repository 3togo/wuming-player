package com.wuming.android.tv.browse;

import androidx.media3.common.MediaItem;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.bean.Vod;
import com.wuming.tv.core.cache.ExpiringLruCache;
import com.wuming.tv.core.search.MediaBrowserSearch;
import com.wuming.tv.core.search.SearchQueryPlan;
import com.wuming.tv.core.search.SearchQueryPlanner;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class VodBrowseSearchTest {
    @Test public void browseAdapterUsesSharedResultCapCacheAndClear() {
        try (Fixture f = new Fixture()) {
            List<MediaItem> items = VodBrowse.search("query");
            assertEquals(50, items.size());
            assertEquals("id-0", items.get(0).mediaId);
            assertEquals("id-49", items.get(49).mediaId);
            assertEquals(items, VodBrowse.getSearchResult());
            assertEquals(items, VodBrowse.search("query"));
            assertEquals(1, f.calls.get());
            VodBrowse.clear();
            assertTrue(VodBrowse.getSearchResult().isEmpty());
            assertEquals(50, VodBrowse.search("query").size());
            assertEquals(2, f.calls.get());
        }
    }

    @Test public void clearedQueuedBrowserRequestCannotStartOrRepopulateTheServiceResult() throws Exception {
        try (Fixture f = new Fixture()) {
            BlockingQueue<Runnable> queued = new LinkedBlockingQueue<>();
            int request = VodBrowse.beginSearch();
            Future<?> work = VodBrowse.browserSearch.submit(request, queued::add, () -> {
                VodBrowse.search("old", request);
                return kotlin.Unit.INSTANCE;
            });
            VodBrowse.clear();
            queued.remove().run();
            assertTrue(work.isCancelled());
            assertFalse(VodBrowse.isSearchCurrent(request));
            assertEquals(0, f.calls.get());
            assertTrue(VodBrowse.getSearchResult().isEmpty());
            VodBrowse.search("new");
            assertEquals("id-0", VodBrowse.getSearchResult().get(0).mediaId);
        }
    }

    @Test public void directlyCancelledBrowserTaskCannotPublishTheConvertedMediaItems() throws Exception {
        try (Fixture f = new Fixture()) {
            ExecutorService caller = Executors.newSingleThreadExecutor();
            CountDownLatch finished = new CountDownLatch(1);
            f.blockConversion = true;
            int request = VodBrowse.beginSearch();
            try {
                Future<?> work = VodBrowse.browserSearch.submit(request, caller, () -> {
                    try { VodBrowse.search("old", request); } finally { finished.countDown(); }
                    return kotlin.Unit.INSTANCE;
                });
                assertTrue(f.conversionEntered.await(5, TimeUnit.SECONDS));
                assertTrue(work.cancel(true));
                f.releaseConversion.countDown();
                assertTrue(finished.await(5, TimeUnit.SECONDS));
                assertFalse(VodBrowse.isSearchCurrent(request));
                assertTrue(VodBrowse.getSearchResult().isEmpty());
                f.blockConversion = false;
                assertEquals(50, VodBrowse.search("fresh").size());
            } finally { f.releaseConversion.countDown(); caller.shutdownNow(); }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final MediaBrowserSearch<Site, Vod, MediaItem> original = VodBrowse.browserSearch;
        final ExecutorService workers = Executors.newFixedThreadPool(2);
        final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch conversionEntered = new CountDownLatch(1);
        final CountDownLatch releaseConversion = new CountDownLatch(1);
        volatile boolean blockConversion;
        Fixture() {
            VodBrowse.browserSearch = new MediaBrowserSearch<>(workers, timers, new MediaBrowserSearch.Host<>() {
                @Override public SearchQueryPlan plan(String query) { return SearchQueryPlanner.localOnly().plan(query); }
                @Override public boolean strict() { return false; }
                @Override public List<Site> sources() { return List.of(Site.get("one", "One")); }
                @Override public String key(Site source) { return source.getKey(); }
                @Override public List<Vod> search(Site source, SearchQueryPlan plan, SearchQueryPlan.Variant variant, boolean strict) {
                    calls.incrementAndGet();
                    List<Vod> results = new ArrayList<>();
                    for (int i = 0; i < 60; i++) { Vod vod = new Vod(); vod.setId("id-" + i); results.add(vod); }
                    return results;
                }
                @Override public List<Vod> merge(List<Vod> items) { return items; }
                @Override public List<MediaItem> items(List<Vod> results, String query) {
                    if (blockConversion) {
                        conversionEntered.countDown();
                        while (releaseConversion.getCount() > 0) try { releaseConversion.await(); } catch (InterruptedException ignored) { }
                    }
                    return results.stream().map(vod -> new MediaItem.Builder().setMediaId(vod.getId()).build()).toList();
                }
            }, new ExpiringLruCache<>(2, 10_000), 500, 50);
        }
        @Override public void close() {
            releaseConversion.countDown();
            VodBrowse.browserSearch.clear();
            VodBrowse.browserSearch = original;
            workers.shutdownNow(); timers.shutdownNow();
        }
    }
}
