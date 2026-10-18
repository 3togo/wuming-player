package com.wuming.android.tv.browse;
import com.wuming.android.tv.setting.DisplaySettings;
import com.wuming.android.tv.setting.AppSettings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;

import com.wuming.android.tv.api.SiteApi;
import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.api.config.SiteSourceAdapter;
import com.wuming.android.tv.bean.BeanUtils;
import com.wuming.android.tv.bean.Episode;
import com.wuming.android.tv.bean.Flag;
import com.wuming.android.tv.bean.History;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.bean.Vod;
import com.wuming.android.tv.db.AppDatabase;
import com.wuming.android.tv.db.AsyncDB;
import com.wuming.android.tv.library.UserLibrary;
import com.wuming.android.tv.search.SearchResultAnnotator;
import com.wuming.android.tv.search.TitleAliasRepository;
import com.wuming.android.tv.search.TitleNormalizer;
import com.wuming.android.tv.utils.SearchResultMerger;
import com.wuming.android.tv.utils.Task;
import com.wuming.android.tv.utils.VodScorer;
import com.wuming.android.tv.utils.SearchCache;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.wuming.tv.core.cache.ExpiringLruCache;
import com.wuming.tv.core.cache.BoundedLruMap;
import com.wuming.tv.core.cache.MemoryBudget;
import com.wuming.tv.core.search.SearchQueryPlan;
import com.wuming.tv.core.search.SearchQueryPlanner;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

public class VodBrowse {

    static final String VOD_EP = "VE:";
    static final String VOD_PLAY = "VP:";
    static final String VOD_SEARCH = "VS:";

    private static final int SEARCH_LIMIT = 50;
    private static final int SEARCH_TIMEOUT = 5;
    private static final MemoryBudget MEMORY = MemoryBudget.current();
    private static final int VOD_CACHE_MAX_SIZE = MEMORY.scale(50, 100, 200);
    private static final int EP_CACHE_MAX_SIZE = MEMORY.scale(256, 500, 1024);
    private static final int SEARCH_CACHE_MAX_SIZE = MEMORY.scale(10, 20, 40);
    private static final long SEARCH_CACHE_TTL = TimeUnit.MINUTES.toMillis(5);
    private static final long VOD_CACHE_TTL = TimeUnit.MINUTES.toMillis(30);
    private static final long VOD_CACHE_MAX_WEIGHT = MEMORY.weightedBytes(0.03);

    private static final ExpiringLruCache<String, Vod> vodCache =
            new ExpiringLruCache<>(VOD_CACHE_MAX_SIZE, VOD_CACHE_TTL, VodBrowse::estimateVodWeight, VOD_CACHE_MAX_WEIGHT);
    private static final Map<String, String> epNavMap = BoundedLruMap.create(EP_CACHE_MAX_SIZE);
    private static final Map<String, EpEntry> epEntries = BoundedLruMap.create(EP_CACHE_MAX_SIZE);
    private static final Map<String, Integer> epCountMap = BoundedLruMap.create(EP_CACHE_MAX_SIZE);
    private static final ExpiringLruCache<String, ImmutableList<MediaItem>> searchResultCache =
            new ExpiringLruCache<>(SEARCH_CACHE_MAX_SIZE, SEARCH_CACHE_TTL);

    private static volatile History browseHistory;
    private static volatile ImmutableList<MediaItem> searchCache = ImmutableList.of();

    public static void clear() {
        vodCache.clear();
        epNavMap.clear();
        epEntries.clear();
        epCountMap.clear();
        searchResultCache.clear();
        SearchCache.get().clear();
        browseHistory = null;
        searchCache = ImmutableList.of();
    }

    static void trimCaches() {
        vodCache.trim();
        searchResultCache.trim();
        SearchCache.get().trim();
    }

    /**
     * Get history items asynchronously.
     * @param callback Called on main thread with history items
     */
    static void getHistoryAsync(java.util.function.Consumer<ImmutableList<MediaItem>> callback) {
        AsyncDB.query(() -> UserLibrary.get().histories(), historyList -> {
            ImmutableList<MediaItem> items = historyList.stream()
                .map(history -> BrowseTree.playable(VOD_PLAY + history.getKey(), history.getVodName(), history.getVodRemarks(), history.getVodPic()))
                .collect(ImmutableList.toImmutableList());
            callback.accept(items);
        });
    }

    /**
     * Get history items asynchronously with memory leak protection.
     * @param callbackRef WeakReference to prevent memory leaks if caller is destroyed
     */
    static void getHistoryAsyncSafe(WeakReference<java.util.function.Consumer<ImmutableList<MediaItem>>> callbackRef) {
        AsyncDB.query(() -> UserLibrary.get().histories(), historyList -> {
            java.util.function.Consumer<ImmutableList<MediaItem>> callback = callbackRef.get();
            if (callback != null) {
                ImmutableList<MediaItem> items = historyList.stream()
                    .map(history -> BrowseTree.playable(VOD_PLAY + history.getKey(), history.getVodName(), history.getVodRemarks(), history.getVodPic()))
                    .collect(ImmutableList.toImmutableList());
                callback.accept(items);
            }
        });
    }

    @NonNull
    static ImmutableList<MediaItem> getHistory() {
        return UserLibrary.get().histories().stream().map(history -> BrowseTree.playable(VOD_PLAY + history.getKey(), history.getVodName(), history.getVodRemarks(), history.getVodPic())).collect(ImmutableList.toImmutableList());
    }

    @NonNull
    static ImmutableList<MediaItem> search(@NonNull String query) {
        VodConfig.get().ensureLoaded();
        SearchQueryPlan plan = new SearchQueryPlanner(
                TitleAliasRepository.get(), TitleNormalizer.core()).plan(query);
        String cacheKey = plan.canonicalKey() + (DisplaySettings.isStrictSearch() ? "|strict" : "|broad");
        
        // Check cache first
        ImmutableList<MediaItem> cached = searchResultCache.get(cacheKey);
        if (cached != null) {
            searchCache = cached;
            return searchCache;
        }
        
        List<Site> sites = SiteSourceAdapter.catalog().searchable(VodConfig.get().getSites(), null);
        List<ListenableFuture<List<Vod>>> futures = sites.stream()
                .map(site -> Task.largeExecutor().submit(() -> searchSite(site, plan)))
                .toList();
        List<Vod> vods = SearchResultMerger.merge(collectResults(futures));
        VodScorer.sortByRelevance(vods, query);
        List<MediaItem> items = vods.stream()
                .map(vod -> BrowseTree.playable(
                        VOD_SEARCH + vod.getSiteKey() + "|" + vod.getId(),
                        vod.getSearchDisplayName(),
                        vod.getRemarks(),
                        vod.getPic()))
                .toList();
        searchCache = ImmutableList.copyOf(items.subList(0, Math.min(items.size(), SEARCH_LIMIT)));
        
        // Update cache (auto-evicts via removeEldestEntry)
        searchResultCache.put(cacheKey, searchCache);
        
        return searchCache;
    }

    private static List<Vod> searchSite(@NonNull Site site, @NonNull SearchQueryPlan plan) throws Exception {
        List<Vod> items = new ArrayList<>();
        Exception lastFailure = null;
        boolean completedRequest = false;
        for (SearchQueryPlan.Variant variant : plan.variants()) {
            try {
                Result result = SiteApi.searchContent(site, variant.text(), false, "1");
                completedRequest = true;
                SearchResultAnnotator.annotate(result.getList(), plan, variant);
                if (DisplaySettings.isStrictSearch()) SearchResultAnnotator.retainStrictMatches(result.getList(), plan, variant);
                items.addAll(result.getList());
            } catch (Exception error) {
                lastFailure = error;
            }
        }
        if (!completedRequest && lastFailure != null) throw lastFailure;
        return SearchResultMerger.merge(items);
    }

    private static List<Vod> collectResults(@NonNull List<ListenableFuture<List<Vod>>> futures) {
        List<Vod> items = new ArrayList<>();
        for (ListenableFuture<List<Vod>> future : futures) {
            try {
                List<Vod> result = future.get(SEARCH_TIMEOUT, TimeUnit.SECONDS);
                if (result != null) items.addAll(result);
                if (items.size() >= SEARCH_LIMIT) break;
            } catch (Exception ignored) {
            }
        }
        return items;
    }

    @NonNull
    static ImmutableList<MediaItem> getSearchResult() {
        return searchCache;
    }

    @Nullable
    static MediaItem resolve(@NonNull String mediaId) throws Exception {
        if (mediaId.startsWith(VOD_PLAY)) return resolvePlay(mediaId);
        if (mediaId.startsWith(VOD_EP)) return resolveEp(mediaId);
        if (mediaId.startsWith(VOD_SEARCH)) return resolveSearch(mediaId);
        return null;
    }

    @Nullable
    static MediaItem navigate(@NonNull String mediaId, int delta) throws Exception {
        if (mediaId.startsWith(VOD_EP)) return navigateEpisode(mediaId, delta);
        String epId = ensureEpLoaded(mediaId);
        if (epId != null) return navigateEpisode(epId, delta);
        return null;
    }

    static long consumeResumePosition() {
        if (browseHistory == null) return C.TIME_UNSET;
        History history = browseHistory;
        long position = history.getPosition();
        history.setPosition(0);
        return position > 0 ? position : C.TIME_UNSET;
    }

    static boolean saveProgress(long position, long duration) {
        if (browseHistory == null || AppSettings.isIncognito()) return false;
        History history = browseHistory;
        history.setPosition(position);
        history.setDuration(duration);
        history.setCreateTime(System.currentTimeMillis());
        if (history.canSave()) Task.execute(() -> UserLibrary.get().mergeAndSave(history));
        return true;
    }

    @Nullable
    private static MediaItem resolvePlay(@NonNull String mediaId) throws Exception {
        String historyKey = mediaId.substring(VOD_PLAY.length());
        String currentEpId = ensureEpLoaded(historyKey);
        return currentEpId != null ? resolveEp(currentEpId) : null;
    }

    @Nullable
    private static MediaItem resolveSearch(@NonNull String mediaId) throws Exception {
        String body = mediaId.substring(VOD_SEARCH.length());
        int sep = body.indexOf('|');
        if (sep < 0) return null;
        String siteKey = body.substring(0, sep);
        String vodId = body.substring(sep + 1);
        String historyKey = siteKey + AppDatabase.SYMBOL + vodId;
        History existing = UserLibrary.get().findHistory(historyKey);
        if (existing != null) return resolveWithHistory(historyKey, existing);
        VodConfig.get().ensureLoaded();
        Vod vod = SiteApi.detailContent(siteKey, vodId).getVod();
        if (BeanUtils.isEmpty(vod.getId()) || vod.getFlags().isEmpty()) return null;
        vodCache.put(historyKey, vod);
        History history = createHistory(historyKey, vod);
        Flag resumeFlag = findFlag(vod, history.getVodFlag());
        if (resumeFlag == null || resumeFlag.getEpisodes().isEmpty()) return null;
        int currentIdx = buildEpIndex(historyKey, resumeFlag, history);
        browseHistory = history;
        String epId = epNavMap.get(epNavKey(historyKey, resumeFlag.getFlag(), currentIdx));
        return epId != null ? resolveEp(epId) : null;
    }

    @Nullable
    private static MediaItem resolveWithHistory(@NonNull String historyKey, @NonNull History history) throws Exception {
        String epId = ensureEpLoaded(historyKey, history);
        return epId != null ? resolveEp(epId) : null;
    }

    private static History createHistory(@NonNull String historyKey, @NonNull Vod vod) {
        History history = new History();
        history.setKey(historyKey);
        history.setCid(VodConfig.getCid());
        history.setVodName(vod.getName());
        history.setVodPic(vod.getPic());
        UserLibrary.get().findEpisode(history, vod.getFlags());
        return history;
    }

    @Nullable
    private static String ensureEpLoaded(@NonNull String historyKey) throws Exception {
        return ensureEpLoaded(historyKey, UserLibrary.get().findHistory(historyKey));
    }

    @Nullable
    private static String ensureEpLoaded(@NonNull String historyKey, @Nullable History history) throws Exception {
        if (history == null) return null;
        Vod vod = getOrFetchVod(historyKey, history);
        if (vod == null) return null;
        Flag flag = findFlag(vod, history.getVodFlag());
        if (flag == null || flag.getEpisodes().isEmpty()) return null;
        int currentIdx = buildEpIndex(historyKey, flag, history);
        browseHistory = history;
        return epNavMap.get(epNavKey(historyKey, flag.getFlag(), currentIdx));
    }

    private static int buildEpIndex(@NonNull String historyKey, @NonNull Flag flag, @NonNull History history) {
        String prefix = historyKey + "|";
        epEntries.values().removeIf(entry -> entry.historyKey.equals(historyKey));
        epNavMap.keySet().removeIf(key -> key.startsWith(prefix));
        epCountMap.keySet().removeIf(key -> key.startsWith(prefix));
        String flagName = flag.getFlag();
        List<Episode> episodes = flag.getEpisodes();
        epCountMap.put(historyKey + '|' + flagName, episodes.size());
        IntStream.range(0, episodes.size()).forEach(i -> indexEpisode(historyKey, flagName, i, history));
        return findCurrentIndex(flag, history);
    }

    private static void indexEpisode(@NonNull String historyKey, @NonNull String flagName, int index, @NonNull History history) {
        String key = epNavKey(historyKey, flagName, index);
        String id = VOD_EP + key;
        epEntries.put(id, new EpEntry(historyKey, flagName, index, history.getSiteKey(), history.getVodPic()));
        epNavMap.put(key, id);
    }

    private static int findCurrentIndex(@NonNull Flag flag, @NonNull History history) {
        String currentUrl = history.getEpisode() != null ? history.getEpisode().getUrl() : null;
        if (BeanUtils.isEmpty(currentUrl)) return 0;
        List<Episode> episodes = flag.getEpisodes();
        return IntStream.range(0, episodes.size()).filter(i -> episodes.get(i).getUrl().equals(currentUrl)).findFirst().orElse(0);
    }

    @Nullable
    private static MediaItem navigateEpisode(@NonNull String mediaId, int delta) throws Exception {
        EpEntry current = epEntries.get(mediaId);
        if (current == null) return null;
        Integer count = epCountMap.get(current.historyKey + '|' + current.flagName);
        if (count == null || count == 0) return null;
        int target = BrowseTree.wrapIndex(current.index, delta, count);
        String nextId = epNavMap.get(epNavKey(current.historyKey, current.flagName, target));
        if (nextId == null) return null;
        if (browseHistory != null) browseHistory.setPosition(0);
        return resolveEp(nextId);
    }

    @Nullable
    private static MediaItem resolveEp(@NonNull String mediaId) throws Exception {
        EpEntry entry = epEntries.get(mediaId);
        if (entry == null) return null;
        Vod vod = getOrFetchVod(entry.historyKey);
        if (vod == null) return null;
        Flag flag = findFlag(vod, entry.flagName);
        if (flag == null || entry.index >= flag.getEpisodes().size()) return null;
        Episode episode = flag.getEpisodes().get(entry.index);
        Result result = SiteApi.playerContent(entry.siteKey, entry.flagName, episode.getUrl());
        if (BeanUtils.isEmpty(result.getRealUrl())) return null;
        updateHistory(episode);
        BrowseTree.putBrowseResult(mediaId, result);
        String vodName = vod.getName();
        if (BeanUtils.isEmpty(vodName) && browseHistory != null) vodName = browseHistory.getVodName();
        return BrowseTree.stream(mediaId, result.getRealUrl(), vodName, episode.getName(), entry.vodPic);
    }

    private static void updateHistory(@NonNull Episode episode) {
        if (browseHistory == null) return;
        browseHistory.setVodRemarks(episode.getName());
        browseHistory.setEpisodeUrl(episode.getUrl());
    }

    @Nullable
    private static Vod getOrFetchVod(@NonNull String historyKey) throws Exception {
        Vod vod = vodCache.get(historyKey);
        if (vod != null) return vod;
        return getOrFetchVod(historyKey, UserLibrary.get().findHistory(historyKey));
    }

    @Nullable
    private static Vod getOrFetchVod(@NonNull String historyKey, @Nullable History history) throws Exception {
        Vod vod = vodCache.get(historyKey);
        if (vod != null) return vod;
        if (history == null) return null;
        vod = fetchDetail(history);
        if (vod == null) return null;
        vodCache.put(historyKey, vod);
        return vod;
    }

    @Nullable
    private static Vod fetchDetail(@NonNull History history) throws Exception {
        VodConfig.get().ensureLoaded();
        Vod vod = SiteApi.detailContent(history.getSiteKey(), history.getVodId()).getVod();
        return BeanUtils.isEmpty(vod.getId()) ? null : vod;
    }

    @Nullable
    private static Flag findFlag(@NonNull Vod vod, @Nullable String name) {
        List<Flag> flags = vod.getFlags();
        if (flags.isEmpty()) return null;
        if (BeanUtils.isNotEmpty(name)) {
            for (Flag flag : flags) { if (flag.getFlag().equals(name)) return flag; }
        }
        return flags.get(0);
    }

    @NonNull
    private static String epNavKey(@NonNull String historyKey, @NonNull String flagName, int index) {
        return historyKey + '|' + flagName + '|' + index;
    }

    private static long estimateVodWeight(@NonNull Vod vod) {
        long weight = 512;
        for (Flag flag : vod.getFlags()) {
            weight += flag.getEpisodes().size() * 200L;
        }
        return weight;
    }

    private record EpEntry(String historyKey, String flagName, int index, String siteKey, String vodPic) {
    }
    
}
