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
    @Test public void stoppedSearchSuppressesPublicationAlreadyQueuedForTheMainThread() throws Exception {
        assertStoppedQueuedPublication(false);
    }

    @Test public void stoppedCachedSearchSuppressesPublicationAlreadyQueuedForTheMainThread() throws Exception {
        assertStoppedQueuedPublication(true);
    }

    @Test public void observerStartingNewSearchCannotCompleteItsProgressWithThePreviousResult() throws Exception {
        Site site = Site.get("one", "One");
        CountDownLatch nextStarted = new CountDownLatch(1);
        CountDownLatch releaseNext = new CountDownLatch(1);
        RecordingGateway gateway = new RecordingGateway() {
            @Override public Result search(Site source, String keyword, boolean quick, String page) {
                if (keyword.equals("new")) { nextStarted.countDown(); awaitRelease(releaseNext); }
                return message(keyword);
            }
        };
        SiteViewModel model = new SiteViewModel(gateway, keyword -> null);
        Observer<Result> observer = value -> {
            if (value != null && value.getMsg().equals("old")) model.searchContent(List.of(site), "new", false);
        };
        model.getSearch().observeForever(observer);
        try {
            model.searchContent(List.of(site), "old", false);
            assertTrue(nextStarted.await(5, TimeUnit.SECONDS));
            synchronized (model) {
                assertEquals(0, model.getSearchProgress().getValue().getCompleted());
            }
            releaseNext.countDown();
            awaitValue(model.getSearchProgress(), SearchProgress.ProgressInfo::isComplete);
            assertEquals("new", model.getSearch().getValue().getMsg());
        } finally {
            releaseNext.countDown(); model.getSearch().removeObserver(observer); model.onCleared();
        }
    }

    private void assertStoppedQueuedPublication(boolean cached) throws Exception {
        java.util.Queue<Runnable> mainQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
        CountDownLatch queued = new CountDownLatch(2);
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(new androidx.arch.core.executor.TaskExecutor() {
            @Override public void executeOnDiskIO(Runnable task) { task.run(); }
            @Override public void postToMainThread(Runnable task) { mainQueue.add(task); queued.countDown(); }
            @Override public boolean isMainThread() { return true; }
        });
        Site site = Site.get("one", "One");
        SiteViewModel model = new SiteViewModel(new RecordingGateway(), keyword -> cached ? List.of(new Collect(site, List.of(vod("cached")))) : null);
        try {
            model.searchContent(List.of(site), "fixture", false);
            assertTrue("Provider publication was not queued", queued.await(5, TimeUnit.SECONDS));
            model.stopSearch();
            Runnable task;
            while ((task = mainQueue.poll()) != null) task.run();
            assertTrue("Cancelled search reached the UI through a pending LiveData post", model.getSearch().getValue() == null);
        } finally {
            model.onCleared();
            androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null);
        }
    }


    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    @Test public void pagingPreservesExactArgumentsAndResultWithoutAliasCacheOrBatchProgress() throws Exception {
        Site site = Site.get("page", "Page");
        Result pageResult = message("page metadata");
        RecordingGateway gateway = new RecordingGateway() {
            @Override public Result search(Site requested, String keyword, boolean quick, String page) {
                assertSame(site, requested);
                assertEquals("  raw title  ", keyword);
                assertTrue(quick);
                assertEquals("3", page);
                searchCalls.incrementAndGet();
                return pageResult;
            }
        };
        SiteViewModel model = new SiteViewModel(gateway, keyword -> { throw new AssertionError("Page must not use batch cache"); },
                new SearchQueryPlanner(keyword -> { throw new AssertionError("Page must not expand aliases"); }, TitleNormalizer.core()),
                () -> { throw new AssertionError("Page must not strict-filter the provider response"); });
        try {
            model.searchContent(site, "  raw title  ", true, "3");
            assertSame(pageResult, await(model.getResult(), value -> value == pageResult));
            assertNull(model.getSearch().getValue());
            assertNull(model.getSearchProgress().getValue());
            assertEquals(1, gateway.searchCalls.get());
        } finally { model.onCleared(); }
    }

    @Test public void pagingAndOtherResultRequestsStillSupersedeEachOther() throws Exception {
        for (boolean pageFirst : List.of(false, true)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch returned = new CountDownLatch(1);
            RecordingGateway gateway = new RecordingGateway() {
                private Result oldResult() {
                    entered.countDown();
                    while (release.getCount() > 0) try { release.await(); } catch (InterruptedException ignored) { interrupted.countDown(); }
                    returned.countDown();
                    return message("old");
                }
                @Override public Result search(Site site, String keyword, boolean quick, String page) {
                    return pageFirst ? oldResult() : message("page");
                }
                @Override public Result detail(String key, String id) { return pageFirst ? message("detail") : oldResult(); }
            };
            SiteViewModel model = new SiteViewModel(gateway, keyword -> null);
            try {
                if (pageFirst) model.searchContent(Site.get("one", "One"), "query", false, "2");
                else model.detailContent("one", "old");
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                if (pageFirst) model.detailContent("one", "new");
                else model.searchContent(Site.get("one", "One"), "query", false, "2");
                String expected = pageFirst ? "detail" : "page";
                await(model.getResult(), value -> expected.equals(value.getMsg()));
                assertTrue("Superseded provider must be interrupted", interrupted.await(5, TimeUnit.SECONDS));
                release.countDown();
                assertTrue(returned.await(5, TimeUnit.SECONDS));
                assertEquals(expected, model.getResult().getValue().getMsg());
            } finally { release.countDown(); model.onCleared(); }
        }
    }

    @Test public void laterPageReplacesEarlierPageEvenWhenTheProviderIgnoresInterruption() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        RecordingGateway gateway = new RecordingGateway() {
            @Override public Result search(Site site, String keyword, boolean quick, String page) {
                if (page.equals("2")) {
                    entered.countDown(); awaitRelease(release); returned.countDown();
                }
                return message(page);
            }
        };
        SiteViewModel model = new SiteViewModel(gateway, keyword -> null);
        try {
            Site site = Site.get("one", "One");
            model.searchContent(site, "query", false, "2");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            model.searchContent(site, "query", false, "3");
            await(model.getResult(), value -> value.getMsg().equals("3"));
            release.countDown();
            assertTrue(returned.await(5, TimeUnit.SECONDS));
            assertEquals("3", model.getResult().getValue().getMsg());
        } finally { release.countDown(); model.onCleared(); }
    }

    @Test public void pagingAndOtherResultsShareTheSameQueuedPublicationOwnership() throws Exception {
        for (boolean pageFirst : List.of(false, true)) {
            java.util.concurrent.BlockingQueue<Runnable> queue = new java.util.concurrent.LinkedBlockingQueue<>();
            androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(new androidx.arch.core.executor.TaskExecutor() {
                @Override public void executeOnDiskIO(Runnable action) { action.run(); }
                @Override public void postToMainThread(Runnable action) { queue.add(action); }
                @Override public boolean isMainThread() { return true; }
            });
            SiteViewModel model = new SiteViewModel(new RecordingGateway(), keyword -> null);
            try {
                Site site = Site.get("one", "One");
                if (pageFirst) model.searchContent(site, "query", false, "2"); else model.detailContent("one", "id");
                Runnable old = queue.poll(5, TimeUnit.SECONDS);
                assertTrue("Initial result was not queued", old != null);
                if (pageFirst) model.detailContent("one", "id"); else model.searchContent(site, "query", false, "3");
                Runnable fresh = queue.poll(5, TimeUnit.SECONDS);
                assertTrue("Replacement result was not queued", fresh != null);
                old.run();
                assertTrue("Old queued result escaped request ownership", model.getResult().getValue() == null);
                fresh.run();
                assertEquals(pageFirst ? "detail" : "search", model.getResult().getValue().getMsg());
            } finally { model.onCleared(); androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null); }
        }
    }

    @Test public void pagingDoesNotCancelAnIndependentAllSourceBatch() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RecordingGateway gateway = new RecordingGateway() {
            @Override public Result search(Site site, String keyword, boolean quick, String page) {
                if (keyword.equals("batch")) { entered.countDown(); awaitRelease(release); }
                return message(keyword);
            }
        };
        SiteViewModel model = new SiteViewModel(gateway, keyword -> null);
        try {
            Site site = Site.get("one", "One");
            model.searchContent(List.of(site), "batch", false);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            model.searchContent(site, "page", false, "2");
            await(model.getResult(), value -> value.getMsg().equals("page"));
            release.countDown();
            await(model.getSearch(), value -> value.getMsg().equals("batch"));
            awaitValue(model.getSearchProgress(), SearchProgress.ProgressInfo::isComplete);
        } finally { release.countDown(); model.onCleared(); }
    }

    @Test public void stopAndClearCancelPagingWithoutPublishingAnEmptyOrErrorResult() throws Exception {
        for (boolean clear : List.of(false, true)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch stopped = new CountDownLatch(1);
            RecordingGateway gateway = new RecordingGateway() {
                @Override public Result search(Site site, String keyword, boolean quick, String page) {
                    entered.countDown();
                    try { new CountDownLatch(1).await(); return message("late"); }
                    catch (InterruptedException expected) { throw new java.util.concurrent.CancellationException(); }
                    finally { stopped.countDown(); }
                }
            };
            SiteViewModel model = new SiteViewModel(gateway, keyword -> null);
            try {
                model.searchContent(Site.get("one", "One"), "query", false, "2");
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                if (clear) model.onCleared(); else model.stopSearch();
                assertTrue(stopped.await(5, TimeUnit.SECONDS));
                assertNull(model.getResult().getValue());
            } finally { model.onCleared(); }
        }
    }

    @Test public void pagingKeepsUsefulProviderErrorsAndEmptyResponsesOnTheResultChannel() throws Exception {
        RecordingGateway gateway = new RecordingGateway() {
            @Override public Result search(Site site, String keyword, boolean quick, String page) {
                if (page.equals("2")) throw new IllegalStateException("page request failed");
                return Result.empty();
            }
        };
        SiteViewModel model = new SiteViewModel(gateway, keyword -> null);
        try {
            Site site = Site.get("one", "One");
            model.searchContent(site, "query", false, "2");
            await(model.getResult(), value -> value.getMsg().equals("page request failed"));
            model.searchContent(site, "query", false, "3");
            Result empty = await(model.getResult(), value -> value.getMsg().isEmpty());
            assertTrue(empty.getList().isEmpty());
            assertNull(model.getSearch().getValue());
        } finally { model.onCleared(); }
    }

    @Test public void providerCancellationDuringPagingRemainsSilent() throws Exception {
        java.util.Queue<Runnable> queue = new java.util.concurrent.ConcurrentLinkedQueue<>();
        CountDownLatch queued = new CountDownLatch(1);
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(new androidx.arch.core.executor.TaskExecutor() {
            @Override public void executeOnDiskIO(Runnable action) { action.run(); }
            @Override public void postToMainThread(Runnable action) { queue.add(action); queued.countDown(); }
            @Override public boolean isMainThread() { return true; }
        });
        RecordingGateway gateway = new RecordingGateway() {
            @Override public Result search(Site site, String keyword, boolean quick, String page) {
                throw new java.util.concurrent.CancellationException("provider cancelled");
            }
        };
        SiteViewModel model = new SiteViewModel(gateway, keyword -> null);
        try {
            model.searchContent(Site.get("one", "One"), "query", false, "2");
            assertTrue(queued.await(5, TimeUnit.SECONDS));
            Runnable action;
            while ((action = queue.poll()) != null) action.run();
            assertTrue("Cancellation became a visible error", model.getResult().getValue() == null);
        } finally { model.onCleared(); androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null); }
    }

    @Test public void queuedPagingFailureCannotOverwriteItsSuccessfulReplacement() throws Exception {
        java.util.concurrent.BlockingQueue<Runnable> queue = new java.util.concurrent.LinkedBlockingQueue<>();
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(new androidx.arch.core.executor.TaskExecutor() {
            @Override public void executeOnDiskIO(Runnable action) { action.run(); }
            @Override public void postToMainThread(Runnable action) { queue.add(action); }
            @Override public boolean isMainThread() { return true; }
        });
        RecordingGateway gateway = new RecordingGateway() {
            @Override public Result search(Site site, String keyword, boolean quick, String page) {
                if (page.equals("2")) throw new IllegalStateException("old page failure");
                return message("fresh page");
            }
        };
        SiteViewModel model = new SiteViewModel(gateway, keyword -> null);
        try {
            Site site = Site.get("one", "One");
            model.searchContent(site, "query", false, "2");
            Runnable stale = queue.poll(5, TimeUnit.SECONDS);
            assertTrue(stale != null);
            model.searchContent(site, "query", false, "3");
            Runnable fresh = queue.poll(5, TimeUnit.SECONDS);
            assertTrue(fresh != null);
            fresh.run();
            stale.run();
            assertEquals("fresh page", model.getResult().getValue().getMsg());
        } finally { model.onCleared(); androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null); }
    }

    @Test public void pagingQueuedForMainCannotPublishAfterStopSearch() throws Exception {
        java.util.Queue<Runnable> queue = new java.util.concurrent.ConcurrentLinkedQueue<>();
        CountDownLatch queued = new CountDownLatch(1);
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(new androidx.arch.core.executor.TaskExecutor() {
            @Override public void executeOnDiskIO(Runnable action) { action.run(); }
            @Override public void postToMainThread(Runnable action) { queue.add(action); queued.countDown(); }
            @Override public boolean isMainThread() { return true; }
        });
        SiteViewModel model = new SiteViewModel(new RecordingGateway(), keyword -> null);
        try {
            model.searchContent(Site.get("one", "One"), "query", false, "2");
            assertTrue(queued.await(5, TimeUnit.SECONDS));
            model.stopSearch();
            Runnable action;
            while ((action = queue.poll()) != null) action.run();
            assertTrue("Stopped page was delivered", model.getResult().getValue() == null);
        } finally { model.onCleared(); androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null); }
    }

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

        viewModel.searchContent(List.of(first, first, second), "fixture", false);
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
                assertEquals(0, ((com.wuming.tv.core.search.SearchLifecycle<?, ?>) field(model, "searchLifecycle")).outstandingCount());
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
                assertEquals(0, ((com.wuming.tv.core.search.SearchLifecycle<?, ?>) field(model, "searchLifecycle")).outstandingCount());
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
                assertEquals(0, ((com.wuming.tv.core.search.SearchLifecycle<?, ?>) field(model, "searchLifecycle")).outstandingCount());
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
