package com.wuming.android.tv.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import com.wuming.android.tv.Constant;
import com.wuming.android.tv.bean.Collect;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.bean.Vod;
import com.wuming.android.tv.exception.ExtractException;
import com.wuming.android.tv.search.TitleNormalizer;
import com.wuming.tv.core.search.SearchQueryPlanner;
import com.wuming.tv.core.search.TitleIdentity;

import org.junit.Rule;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

public class SiteViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    @Test
    public void initClearsAllPublishedResultChannels() {
        SiteViewModel viewModel = new SiteViewModel().init();

        assertNull(viewModel.getResult().getValue());
        assertNull(viewModel.getPlayer().getValue());
        assertNull(viewModel.getSearch().getValue());
        assertNull(viewModel.getAction().getValue());
    }

    @Test
    public void stopSearchAdvancesCancellationEpoch() {
        SiteViewModel viewModel = new SiteViewModel();

        assertEquals(1, viewModel.stopSearch());
        assertEquals(2, viewModel.stopSearch());
    }

    @Test
    public void loginActionUsesPlayerQrTimeoutWithoutExtendingNormalActions() {
        assertEquals(Constant.TIMEOUT_PLAYER, SiteViewModel.timeoutForAction("LoginShow"));
        assertEquals(Constant.TIMEOUT_VOD, SiteViewModel.timeoutForAction("refresh"));
    }

    @Test
    public void categoryGatewayPreservesAbsentAndExplicitEmptySelections() throws Exception {
        RecordingGateway gateway = new RecordingGateway();
        SiteViewModel viewModel = new SiteViewModel(gateway, keyword -> null);
        HashMap<String, String> defaults = new HashMap<>();
        defaults.put("area", ""); // Explicit All must survive; missing type must stay absent.
        defaults.put("year", "2026");
        viewModel.categoryContent("site", "tv", "1", true, defaults);
        await(viewModel.getResult(), value -> "category".equals(value.getMsg()));
        assertEquals(defaults, gateway.categoryExtend);
        assertTrue(!gateway.categoryExtend.containsKey("type"));
        assertEquals("", gateway.categoryExtend.get("area"));

        viewModel.init();
        viewModel.categoryContent("site", "movie", "1", true, new HashMap<>());
        await(viewModel.getResult(), value -> "category".equals(value.getMsg()));
        assertTrue(gateway.categoryExtend.isEmpty());
    }

    @Test
    public void contentCallsPublishToTheirIndependentChannels() throws Exception {
        RecordingGateway gateway = new RecordingGateway();
        SiteViewModel viewModel = new SiteViewModel(gateway, keyword -> null);

        viewModel.homeContent();
        assertEquals("home", await(viewModel.getResult(), value -> "home".equals(value.getMsg())).getMsg());
        viewModel.categoryContent("site", "tv", "2", true, new HashMap<>());
        assertEquals("category", await(viewModel.getResult(), value -> "category".equals(value.getMsg())).getMsg());
        viewModel.detailContent("site", "vod");
        assertEquals("detail", await(viewModel.getResult(), value -> "detail".equals(value.getMsg())).getMsg());
        viewModel.action("site", "refresh");
        assertEquals("action", await(viewModel.getAction(), value -> "action".equals(value.getMsg())).getMsg());
        viewModel.playerContent("site", "line", "vod");
        assertEquals("player", await(viewModel.getPlayer(), value -> "player".equals(value.getMsg())).getMsg());
        viewModel.searchContent(Site.get("site", "Site"), "movie", false, "3");
        assertEquals("search", await(viewModel.getResult(), value -> "search".equals(value.getMsg())).getMsg());

        assertEquals(1, gateway.homeCalls.get());
        assertEquals(1, gateway.categoryCalls.get());
        assertEquals(1, gateway.detailCalls.get());
        assertEquals(1, gateway.actionCalls.get());
        assertEquals(1, gateway.playerCalls.get());
        assertEquals(1, gateway.searchCalls.get());
    }

    @Test
    public void extractAndUnexpectedFailuresProduceUsefulSafeResults() throws Exception {
        RecordingGateway extractFailure = new RecordingGateway();
        extractFailure.detailFailure = new ExtractException("provider detail failed");
        SiteViewModel extractModel = new SiteViewModel(extractFailure, keyword -> null);
        extractModel.detailContent("site", "vod");
        assertEquals("provider detail failed", await(extractModel.getResult(), Result::hasMsg).getMsg());

        RecordingGateway runtimeFailure = new RecordingGateway();
        runtimeFailure.actionFailure = new IllegalStateException("broken provider");
        SiteViewModel runtimeModel = new SiteViewModel(runtimeFailure, keyword -> null);
        runtimeModel.action("site", "refresh");
        Result safe = await(runtimeModel.getAction(), value -> value != null);
        assertTrue(safe.getMsg().isEmpty());
        assertTrue(safe.getList().isEmpty());
    }

    @Test
    public void playerFailurePreservesProviderQrChallenge() throws Exception {
        RecordingGateway gateway = new RecordingGateway();
        gateway.playerFailure = new IllegalStateException("need verify: <img src=\"data:image/jpeg;base64,YWJjZA==\">");
        SiteViewModel viewModel = new SiteViewModel(gateway, keyword -> null);

        viewModel.playerContent("site", "UC", "episode");

        Result result = await(viewModel.getPlayer(), Result::hasMsg);
        assertTrue(result.getMsg().contains("data:image/jpeg;base64,YWJjZA=="));
        viewModel.clearPlayer(Result.empty());
        assertSame(result, viewModel.getPlayer().getValue());
        viewModel.clearPlayer(result);
        assertNull(viewModel.getPlayer().getValue());
    }

    @Test
    public void newerRequestSuppressesLateResultFromCancelledRequest() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        RecordingGateway gateway = new RecordingGateway() {
            @Override
            public Result detail(String key, String id) throws Exception {
                if (calls.incrementAndGet() == 1) {
                    firstStarted.countDown();
                    while (releaseFirst.getCount() > 0) {
                        try {
                            releaseFirst.await();
                        } catch (InterruptedException ignored) {
                            // Simulate a provider which ignores cancellation and completes late.
                        }
                    }
                    return message("stale");
                }
                return message("fresh");
            }
        };
        SiteViewModel viewModel = new SiteViewModel(gateway, keyword -> null);

        viewModel.detailContent("site", "old");
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
        viewModel.detailContent("site", "new");
        assertEquals("fresh", await(viewModel.getResult(), value -> "fresh".equals(value.getMsg())).getMsg());
        releaseFirst.countDown();
        Thread.sleep(100);
        assertEquals("fresh", viewModel.getResult().getValue().getMsg());
    }

    @Test
    public void cachedMultiSiteSearchPublishesEveryCollectionWithoutCallingProvider() throws Exception {
        Site first = Site.get("one", "One");
        Site second = Site.get("two", "Two");
        Collect firstCollect = new Collect(first, List.of(vod("one-result")));
        Collect secondCollect = new Collect(second, List.of(vod("two-result")));
        RecordingGateway gateway = new RecordingGateway();
        SiteViewModel viewModel = new SiteViewModel(gateway, keyword -> List.of(firstCollect, secondCollect));
        CountDownLatch results = new CountDownLatch(2);
        Observer<Result> observer = value -> {
            if (value != null) results.countDown();
        };
        viewModel.getSearch().observeForever(observer);
        try {
            viewModel.searchContent(List.of(first, second), "fixture", false);
            assertTrue(results.await(2, TimeUnit.SECONDS));
            SearchProgress.ProgressInfo progress = awaitValue(viewModel.getSearchProgress(), SearchProgress.ProgressInfo::isComplete);
            assertEquals(2, progress.getCompleted());
            assertEquals(2, progress.getTotal());
            assertEquals(0, gateway.searchCalls.get());
        } finally {
            viewModel.getSearch().removeObserver(observer);
        }
    }

    @Test
    public void multiSiteSearchCompletesProgressAcrossSuccessAndFailure() throws Exception {
        Site first = Site.get("one", "One");
        Site second = Site.get("two", "Two");
        Result found = Result.list(List.of(vod("found")));
        RecordingGateway gateway = new RecordingGateway() {
            @Override
            public Result search(Site site, String keyword, boolean quick, String page) {
                searchCalls.incrementAndGet();
                if ("two".equals(site.getKey())) throw new IllegalStateException("offline");
                return found;
            }
        };
        SiteViewModel viewModel = new SiteViewModel(gateway, keyword -> null);

        viewModel.searchContent(List.of(first, second), "fixture", false);
        SearchProgress.ProgressInfo progress = awaitValue(viewModel.getSearchProgress(), SearchProgress.ProgressInfo::isComplete);
        assertEquals(2, progress.getCompleted());
        assertEquals(2, progress.getTotal());
        assertEquals(2, gateway.searchCalls.get());
        assertTrue(await(viewModel.getSearch(), value -> value == found) == found);
    }

    @Test
    public void englishSearchCallsProviderWithEnglishAndResolvedChineseTitle() throws Exception {
        Site site = Site.get("one", "One");
        List<String> queries = new CopyOnWriteArrayList<>();
        RecordingGateway gateway = new RecordingGateway() {
            @Override
            public Result search(Site requestedSite, String keyword, boolean quick, String page) {
                searchCalls.incrementAndGet();
                queries.add(keyword);
                return "无间道".equals(keyword)
                        ? Result.list(List.of(vod("无间道")))
                        : Result.empty();
            }
        };
        TitleIdentity identity = new TitleIdentity(
                "douban:1307914",
                List.of("Infernal Affairs", "无间道", "無間道"),
                "2002",
                "movie");
        SiteViewModel viewModel = new SiteViewModel(
                gateway,
                keyword -> null,
                new SearchQueryPlanner(keyword -> identity, TitleNormalizer.core()));

        viewModel.searchContent(List.of(site), "Infernal Affairs", false);

        Result result = await(viewModel.getSearch(), value -> !value.getList().isEmpty());
        assertEquals(List.of("Infernal Affairs", "无间道"), queries);
        assertEquals("douban:1307914", result.getVod().getSearchCanonicalId());
        assertEquals("Infernal Affairs · 无间道", result.getVod().getSearchDisplayName());
    }

    @Test
    public void traditionalSearchCallsProviderWithOriginalAndSimplifiedTitle() throws Exception {
        Site site = Site.get("one", "One");
        List<String> queries = new CopyOnWriteArrayList<>();
        RecordingGateway gateway = new RecordingGateway() {
            @Override
            public Result search(Site requestedSite, String keyword, boolean quick, String page) {
                searchCalls.incrementAndGet();
                queries.add(keyword);
                return Result.empty();
            }
        };
        SiteViewModel viewModel = new SiteViewModel(gateway, keyword -> null);

        viewModel.searchContent(List.of(site), "無間道", false);

        awaitValue(viewModel.getSearchProgress(), SearchProgress.ProgressInfo::isComplete);
        assertEquals(List.of("無間道", "无间道"), queries);
    }

    @Test
    public void clearingModelCancelsPrewarmAndRejectsNewRequests() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RecordingGateway gateway = new RecordingGateway() {
            @Override public Result player(String key, String flag, String id) {
                playerCalls.incrementAndGet();
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return message("prewarm");
            }
        };
        SiteViewModel model = new SiteViewModel(gateway, keyword -> null);
        try {
            model.prewarmPlayerContent("site", "line", "next");
            assertTrue(started.await(2, TimeUnit.SECONDS));
            model.onCleared();
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            model.prewarmPlayerContent("site", "line", "later");
            model.homeContent();
            model.searchContent(List.of(Site.get("one", "One")), "fixture", false);
            synchronized (model) {
                assertTrue(requests(model, "futures").isEmpty());
                assertTrue(((List<?>) field(model, "searchFuture")).isEmpty());
            }
            assertEquals(1, gateway.playerCalls.get());
            assertEquals(0, gateway.homeCalls.get());
            assertEquals(0, gateway.searchCalls.get());
            assertNull(model.getPlayer().getValue());
        } finally {
            release.countDown();
            model.onCleared();
        }
    }

    @Test
    public void consumedPlayerResultIsNotRetainedByCompletedFuture() throws Exception {
        SiteViewModel model = new SiteViewModel(new RecordingGateway(), keyword -> null);
        try {
            model.playerContent("site", "line", "episode");
            Result result = await(model.getPlayer(), Result::hasMsg);
            model.clearPlayer(result);
            synchronized (model) {
                assertTrue(requests(model, "futures").isEmpty());
            }
            assertNull(model.getPlayer().getValue());
        } finally {
            model.onCleared();
        }
    }

    @Test
    public void stoppedSearchDoesNotLaunchProvidersAfterLatePlanning() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        SearchQueryPlanner planner = new SearchQueryPlanner(keyword -> {
            started.countDown();
            while (release.getCount() > 0) {
                try { release.await(); } catch (InterruptedException ignored) { }
            }
            finished.countDown();
            return null;
        }, TitleNormalizer.core());
        RecordingGateway gateway = new RecordingGateway();
        SiteViewModel model = new SiteViewModel(gateway, keyword -> null, planner);
        try {
            model.searchContent(List.of(Site.get("one", "One")), "fixture", false);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            model.stopSearch();
            release.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            synchronized (model) {
                assertTrue(((List<?>) field(model, "searchFuture")).isEmpty());
            }
            assertEquals(0, gateway.searchCalls.get());
            assertNull(model.getSearch().getValue());
        } finally {
            release.countDown();
            model.onCleared();
        }
    }

    @Test
    public void stopDuringSearchFanOutPreventsUnownedRequests() throws Exception {
        CountDownLatch allowPlan = new CountDownLatch(1);
        CountDownLatch fanOutStarted = new CountDownLatch(1);
        CountDownLatch allowFanOut = new CountDownLatch(1);
        CountDownLatch fanOutFinished = new CountDownLatch(1);
        List<Site> sites = new java.util.ArrayList<>(List.of(Site.get("one", "One"))) {
            @Override public void forEach(java.util.function.Consumer<? super Site> action) {
                fanOutStarted.countDown();
                awaitRelease(allowFanOut);
                try { super.forEach(action); } finally { fanOutFinished.countDown(); }
            }
        };
        SearchQueryPlanner planner = new SearchQueryPlanner(keyword -> {
            awaitRelease(allowPlan);
            return null;
        }, TitleNormalizer.core());
        RecordingGateway gateway = new RecordingGateway();
        SiteViewModel model = new SiteViewModel(gateway, keyword -> null, planner);
        try {
            model.searchContent(sites, "fixture", false);
            allowPlan.countDown();
            assertTrue(fanOutStarted.await(2, TimeUnit.SECONDS));
            model.stopSearch();
            allowFanOut.countDown();
            assertTrue(fanOutFinished.await(2, TimeUnit.SECONDS));
            synchronized (model) {
                assertTrue(((List<?>) field(model, "searchFuture")).isEmpty());
            }
            assertEquals(0, gateway.searchCalls.get());
        } finally {
            allowPlan.countDown();
            allowFanOut.countDown();
            model.onCleared();
        }
    }

    private static void awaitRelease(CountDownLatch latch) {
        while (latch.getCount() > 0) {
            try { latch.await(); } catch (InterruptedException ignored) { }
        }
    }

    private static java.util.Map<?, ?> requests(Object model, String name) throws Exception {
        return (java.util.Map<?, ?>) field(model, name);
    }

    private static Object field(Object model, String name) throws Exception {
        java.lang.reflect.Field field = model.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(model);
    }

    private static Result await(LiveData<Result> data, Predicate<Result> predicate) throws Exception {
        return awaitValue(data, predicate);
    }

    private static <T> T awaitValue(LiveData<T> data, Predicate<T> predicate) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            T current = data.getValue();
            if (current != null && predicate.test(current)) return current;
            Thread.sleep(5);
        }
        T current = data.getValue();
        assertTrue("Timed out waiting for LiveData", current != null && predicate.test(current));
        return current;
    }

    private static Result message(String text) {
        Result result = Result.empty();
        result.setMsg(text);
        return result;
    }

    private static Vod vod(String name) {
        Vod vod = new Vod();
        vod.setId(name);
        vod.setName(name);
        setField(vod, "cachedName", name);
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

    private static class RecordingGateway implements SiteViewModel.ContentGateway {
        final AtomicInteger homeCalls = new AtomicInteger();
        final AtomicInteger categoryCalls = new AtomicInteger();
        final AtomicInteger actionCalls = new AtomicInteger();
        final AtomicInteger detailCalls = new AtomicInteger();
        final AtomicInteger searchCalls = new AtomicInteger();
        final AtomicInteger playerCalls = new AtomicInteger();
        volatile HashMap<String, String> categoryExtend;
        Exception detailFailure;
        RuntimeException actionFailure;
        RuntimeException playerFailure;

        @Override public Result home() { homeCalls.incrementAndGet(); return message("home"); }
        @Override public Result category(String key, String tid, String page, boolean filter, HashMap<String, String> extend) { categoryExtend = new HashMap<>(extend); categoryCalls.incrementAndGet(); return message("category"); }
        @Override public Result action(String key, String act) { actionCalls.incrementAndGet(); if (actionFailure != null) throw actionFailure; return message("action"); }
        @Override public Result detail(String key, String id) throws Exception { detailCalls.incrementAndGet(); if (detailFailure != null) throw detailFailure; return message("detail"); }
        @Override public Result player(String key, String flag, String id) { playerCalls.incrementAndGet(); if (playerFailure != null) throw playerFailure; return message("player"); }
        @Override public Result search(Site site, String keyword, boolean quick, String page) { searchCalls.incrementAndGet(); return message("search"); }
    }
}
