package com.wuming.android.tv.api;

import android.os.Parcel;
import com.wuming.android.tv.cloud.opendrive.OpenDriveLog;
import com.wuming.android.tv.setting.AppSettings;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.collection.ArrayMap;

import com.wuming.android.tv.App;
import com.wuming.android.tv.R;
import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.api.loader.BaseLoader;
import com.wuming.android.tv.bean.Class;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.bean.Vod;
import com.wuming.android.tv.cloud.opendrive.OpenDriveSpider;
import com.wuming.tv.core.cache.ExpiringLruCache;
import com.wuming.tv.core.cache.MemoryBudget;
import com.wuming.tv.core.catalog.CatalogTypeSelectionPolicy;
import com.wuming.tv.core.media.OpenDriveIdentityCodec;
import com.wuming.tv.core.media.OpenDriveShadowProbe;
import com.wuming.android.tv.media.AndroidMediaPipelineFactory;
import com.wuming.tv.core.media.OpenDriveUri;
import com.wuming.android.tv.player.Source;
import com.wuming.android.tv.utils.ResUtil;
import com.wuming.android.tv.utils.Sniffer;
import com.wuming.android.tv.utils.VideoRankManager;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;
import com.github.catvod.utils.Util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Response;

public class SiteApi {

    private static final String LOGIN_ACTION = "LoginShow";
    private static final OpenDriveShadowProbe OPEN_DRIVE_SHADOW_PROBE =
            AndroidMediaPipelineFactory.shadowProbe();
    private static final ExpiringLruCache<String, DetailCacheEntry> detailCache = DetailCacheBudget.newCache();

    /**
     * Budget for the serialized detail snapshots. Entries are bounded by count and by retained
     * bytes, and one oversized snapshot is kept out of the cache instead of evicting the details
     * which fit.
     */
    static final class DetailCacheBudget {
        private static final MemoryBudget MEMORY = MemoryBudget.current();
        static final int MAX_ENTRIES = MEMORY.scale(100, 200, 400);
        static final long TTL_MILLIS = TimeUnit.MINUTES.toMillis(10);
        static final long MAX_TOTAL_BYTES = MEMORY.weightedBytes(0.04);
        static final long MAX_SNAPSHOT_BYTES = MEMORY.weightedBytes(0.01);

        private DetailCacheBudget() {
        }

        static ExpiringLruCache<String, DetailCacheEntry> newCache() {
            return new ExpiringLruCache<>(MAX_ENTRIES, TTL_MILLIS,
                    DetailCacheEntry::snapshotBytes, MAX_TOTAL_BYTES);
        }

        static boolean accepts(long snapshotBytes) {
            return snapshotBytes <= MAX_SNAPSHOT_BYTES;
        }
    }

    static final class DetailCacheEntry {
        // Screens mutate flags and episode selection. The cache owns only a
        // serialized snapshot, never a graph which can be adopted by UI adapters.
        private final byte[] snapshot;

        DetailCacheEntry(Result result) {
            Parcel parcel = Parcel.obtain();
            try {
                result.writeToParcel(parcel, 0);
                snapshot = parcel.marshall();
            } finally {
                parcel.recycle();
            }
        }

        long snapshotBytes() {
            return snapshot.length;
        }

        Result getResult() {
            Parcel parcel = Parcel.obtain();
            try {
                parcel.unmarshall(snapshot, 0, snapshot.length);
                parcel.setDataPosition(0);
                return Result.CREATOR.createFromParcel(parcel);
            } finally {
                parcel.recycle();
            }
        }
    }

    public static final String PUSH = "push_agent";

    public static void clearDetailCache() {
        detailCache.clear();
    }

    public static String call(@NonNull Site site, @NonNull ArrayMap<String, String> params) throws IOException {
        if (!site.getExt().isEmpty()) params.put("extend", site.getExt());
        Call call = site.getExt().length() <= 1000 ? OkHttp.newCall(site.getApi(), site.getHeader(), params) : OkHttp.newCall(site.getApi(), site.getHeader(), OkHttp.toBody(params));
        try (Response response = call.execute()) {
            okhttp3.ResponseBody body = response.body();
            return body != null ? body.string() : "";
        }
    }

    private static boolean isSpider(@NonNull Site site) {
        return site.getType() == 3;
    }

    private static String ac(int type) {
        return type == 0 ? "videolist" : "detail";
    }

    @NonNull
    public static Result homeContent(@NonNull Site site) throws Exception {
        if (isSpider(site)) {
            Spider spider = site.recent().spider();
            boolean crash = Prefers.getBoolean("crash");
            String home = crash ? "" : spider.homeContent(true);
            String video = crash ? "" : spider.homeVideoContent();
            Prefers.put("crash", false);
            SpiderDebug.log("home", home);
            SpiderDebug.log("homeVideo", video);
            Result result = Result.fromJson(home);
            List<Vod> list = Result.fromJson(video).getList();
            if (list != null && !list.isEmpty()) result.setList(list);
            setTypes(site, result);
            return VideoRankManager.apply(site.getKey(), result);
        } else if (site.getType() == 4) {
            ArrayMap<String, String> params = new ArrayMap<>();
            params.put("filter", "true");
            String homeContent = call(site.fetchExt(), params);
            SpiderDebug.log("home", homeContent);
            Result result = Result.fromJson(homeContent);
            setTypes(site, result);
            return VideoRankManager.apply(site.getKey(), result);
        } else {
            try (Response response = OkHttp.newCall(site.getApi(), site.getHeader()).execute()) {
                okhttp3.ResponseBody responseBody = response.body();
                String homeContent = responseBody != null ? responseBody.string() : "";
                SpiderDebug.log("home", homeContent);
                Result result = Result.fromType(site.getType(), homeContent);
                fetchPic(site, result);
                setTypes(site, result);
                return VideoRankManager.apply(site.getKey(), result);
            }
        }
    }

    @NonNull
    public static Result categoryContent(@NonNull String key, @NonNull String tid, @NonNull String page, boolean filter, @NonNull HashMap<String, String> extend) throws Exception {
        SpiderDebug.log("category", "key=%s,tid=%s,page=%s,filter=%s,extend=%s", key, tid, page, filter, extend);
        Site site = VodConfig.get().getSite(key);
        if (isSpider(site)) {
            if (Prefers.getBoolean("crash")) return Result.empty();
            Spider spider = site.recent().spider();
            String categoryContent = spider.categoryContent(tid, page, filter, extend);
            SpiderDebug.log("category", categoryContent);
            Result result = Result.fromJson(categoryContent);
            if (hasLoginAction(result) && !BaseLoader.get().isInteractive(site.getKey())) {
                /*
                 * The isolated provider instance produced this action page, but
                 * LoginShow must run in the Activity-backed app process. Warm the
                 * local instance with the exact same category request before the
                 * user taps login so provider fields used by action() are present.
                 * This also makes the QR originate from the visible cloud page,
                 * rather than from an uninitialised/stale provider instance.
                 */
                spider = BaseLoader.get().getInteractiveSpider(site.getKey(), site.getApi(), site.getExt(), site.getJar());
                categoryContent = spider.categoryContent(tid, page, filter, extend);
                SpiderDebug.log("category-interactive", categoryContent);
                result = Result.fromJson(categoryContent);
            }
            return VideoRankManager.apply(site.getKey(), result);
        } else {
            ArrayMap<String, String> params = new ArrayMap<>();
            if (site.getType() == 1 && !extend.isEmpty()) params.put("f", App.gson().toJson(extend));
            if (site.getType() == 4) params.put("ext", Util.base64(App.gson().toJson(extend), Util.URL_SAFE));
            params.put("ac", ac(site.getType()));
            params.put("t", tid);
            params.put("pg", page);
            String categoryContent = call(site, params);
            SpiderDebug.log("category", categoryContent);
            return VideoRankManager.apply(site.getKey(), Result.fromType(site.getType(), categoryContent));
        }
    }

    @NonNull
    public static Result detailContent(@NonNull String key, @NonNull String id) throws Exception {
        String cacheKey = key + ":" + id;
        DetailCacheEntry cached = detailCache.get(cacheKey);
        if (cached != null) {
            Result restored = cached.getResult();
            if (DetailPlaybackAvailability.canCache(restored.getVod())) {
                SpiderDebug.log("detail-cache", "hit,key=%s,id=%s", key, id);
                return restored;
            }
            detailCache.remove(cacheKey);
        }
        SpiderDebug.log("detail", "key=%s,id=%s", key, id);
        Site site = VodConfig.get().getSite(key);
        Result result;
        if (site.isEmpty() && PUSH.equals(key)) {
            Vod vod = new Vod();
            vod.setId(id);
            vod.setName(id);
            vod.setPlayUrl(id);
            vod.setPlayFrom(ResUtil.getString(R.string.push));
            vod.setPic("android.resource://" + App.get().getPackageName() + "/" + R.drawable.push_image);
            Source.get().parse(vod.setFlags());
            result = Result.vod(vod);
        } else if (isSpider(site)) {
            if (Prefers.getBoolean("crash")) return Result.empty();
            String detailContent = site.recent().spider().detailContent(Arrays.asList(id));
            result = Result.fromJson(detailContent);
            if (result.getVod() != null) Source.get().parse(result.getVod().setFlags());
            int routed = routeOpenDriveEpisodes(result.getVod());
            if (routed > 0) SpiderDebug.log("detail", "route=OpenDriveSpider,episodes=%s", routed);
            else SpiderDebug.log("detail", "route=external,responseLength=%s", detailContent.length());
        } else {
            ArrayMap<String, String> params = new ArrayMap<>();
            params.put("ac", ac(site.getType()));
            params.put("ids", id);
            String detailContent = call(site, params);
            SpiderDebug.log("detail", detailContent);
            result = Result.fromType(site.getType(), detailContent);
            if (result.getVod() != null) Source.get().parse(result.getVod().setFlags());
        }
        if (DetailPlaybackAvailability.canCache(result.getVod())) {
            DetailCacheEntry entry = new DetailCacheEntry(result);
            if (DetailCacheBudget.accepts(entry.snapshotBytes())) detailCache.put(cacheKey, entry);
        } else {
            SpiderDebug.log("detail", "no-playable-episodes,key=%s,id=%s,cache=skipped", key, id);
        }
        return result;
    }

    @NonNull
    public static Result playerContent(@NonNull String key, @NonNull String flag, @NonNull String id) throws Exception {
        com.wuming.android.tv.utils.QuarkPlaybackTrace.write("player route="
                + AppSettings.getLinkRoute() + " smart=" + flag.contains("智")
                + " canonical=" + OpenDriveUri.hasScheme(id)
                + " routed=" + OpenDriveSpider.isRoutedId(id));
        SpiderDebug.log("player", "key=%s,flag=%s,idLength=%s", key, flag, id.length());
        String sourceKey = key;
        String sourceId = id;
        if (AppSettings.getLinkRoute() == AppSettings.LINK_ROUTE_BUILTIN
                && !OpenDriveUri.hasScheme(id) && !OpenDriveSpider.isRoutedId(id)) {
            Site openDriveSite = VodConfig.get().getSite(VodConfig.OPEN_DRIVE_KEY);
            Spider openDriveSpider = openDriveSite.spider();
            if (openDriveSpider instanceof OpenDriveSpider builtIn) {
                String routed = builtIn.routeExternalEpisode(flag, id);
                if (routed != null) {
                    SpiderDebug.log("player-route",
                            "sourceKey=%s,targetKey=%s,stage=player", sourceKey,
                            VodConfig.OPEN_DRIVE_KEY);
                    id = routed;
                }
            }
        }
        if (OpenDriveUri.hasScheme(id) || OpenDriveSpider.isDeferredId(id)
                || (OpenDriveSpider.isFileId(id)
                && (VodConfig.OPEN_DRIVE_KEY.equals(key)
                || AppSettings.getLinkRoute() == AppSettings.LINK_ROUTE_BUILTIN))) {
            SpiderDebug.log("player-route", "sourceKey=%s,targetKey=%s,target=OpenDriveSpider",
                    key, VodConfig.OPEN_DRIVE_KEY);
            key = VodConfig.OPEN_DRIVE_KEY;
        }
        Site site = VodConfig.get().getSite(key);
        com.wuming.android.tv.utils.QuarkPlaybackTrace.write("resolver builtin="
                + VodConfig.OPEN_DRIVE_KEY.equals(key));
        Source.get().stop();
        if (site.getType() == 3) {
            if (Prefers.getBoolean("crash")) return Result.empty();
            /*
             * REGRESSION GUARDRAIL:
             * Player calls can open Quark/UC/Baidu QR login UI. Do not switch
             * this back to site.recent().spider() or a RemoteSpider: an
             * isolated provider cannot display the QR dialog, and its proxy
             * authentication state would be lost. See the regression playbook.
             */
            Spider spider = BaseLoader.get().getInteractiveSpider(site.getKey(), site.getApi(), site.getExt(), site.getJar());
            String playerContent;
            try {
                playerContent = spider.playerContent(flag, id, VodConfig.get().getFlags());
            } catch (Exception openDriveFailure) {
                logOpenDriveFailure(sourceKey, openDriveFailure);
                throw openDriveFailure;
            }
            SpiderDebug.log("player", "key=%s,responseLength=%s", key, playerContent.length());
            Result result = preparePlayerResult(site, key, flag, playerContent);
            Result fallback = tryOpenDriveFallback(sourceKey, flag, sourceId, result);
            if (fallback != null) return fallback;
            return observeOpenDriveShadow(result, id);
        } else if (site.getType() == 4) {
            ArrayMap<String, String> params = new ArrayMap<>();
            params.put("play", id);
            params.put("flag", flag);
            String playerContent = call(site, params);
            SpiderDebug.log("player", "key=%s,responseLength=%s", key, playerContent.length());
            Result result = Result.fromJson(playerContent);
            if (result.getFlag().isEmpty()) result.setFlag(flag);
            result.setUrl(Source.get().fetch(result));
            result.setHeader(site.getHeader());
            result.setKey(key);
            return observeOpenDriveShadow(result, id);
        } else if (site.isEmpty() && "push_agent".equals(key)) {
            Result result = new Result();
            result.setUrl(id);
            result.setParse(0);
            result.setFlag(flag);
            result.setUrl(Source.get().fetch(result));
            result.setKey(key);
            SpiderDebug.log("player", "key=%s,parse=%s", key, result.getParse());
            return observeOpenDriveShadow(result, id);
        } else {
            Result result = new Result();
            result.setUrl(id);
            result.setFlag(flag);
            result.setHeader(site.getHeader());
            result.setPlayUrl(site.getPlayUrl());
            result.setParse(Sniffer.isVideoFormat(id) && result.getPlayUrl().isEmpty() ? 0 : 1);
            result.setUrl(Source.get().fetch(result));
            result.setKey(key);
            SpiderDebug.log("player", "key=%s,parse=%s", key, result.getParse());
            return observeOpenDriveShadow(result, id);
        }
    }

    private static Result preparePlayerResult(Site site, String key, String flag,
                                              String playerContent) throws Exception {
        Result result = Result.fromJson(playerContent);
        // Refresh before Source.fetch/UI consumption: preview, local playback and casting
        // must share authenticated choices. A cast-only fix leaves 720p absent from the UI.
        com.wuming.android.tv.utils.BilibiliQualityResolver.refresh(result);
        if (result.getFlag().isEmpty()) result.setFlag(flag);
        result.setUrl(Source.get().fetch(result));
        result.setHeader(site.getHeader());
        result.setKey(key);
        return result;
    }

    /**
     * CatVod remains the primary resolver in direct mode. A supported cloud episode is handed to
     * OpenDrive only after CatVod has returned a terminal error or no playback URL. This is not a
     * speculative protocol retry: routeExternalEpisode accepts only payloads understood by an
     * authenticated first-party provider.
     */
    private static Result tryOpenDriveFallback(String sourceKey, String flag, String sourceId,
                                                Result catVodResult) {
        if (!shouldTryOpenDriveFallback(AppSettings.getLinkRoute(), sourceId, catVodResult)) return null;
        try {
            Site openDriveSite = VodConfig.get().getSite(VodConfig.OPEN_DRIVE_KEY);
            Spider spider = BaseLoader.get().getInteractiveSpider(openDriveSite.getKey(),
                    openDriveSite.getApi(), openDriveSite.getExt(), openDriveSite.getJar());
            if (!(spider instanceof OpenDriveSpider builtIn)) return null;
            String routed = builtIn.routeExternalEpisode(flag, sourceId);
            if (routed == null) return null;
            OpenDriveLog.catvodFallbackTriggered(sourceKey, null, flag);
            SpiderDebug.log("player-route",
                    "sourceKey=%s,targetKey=%s,stage=catvod-fallback", sourceKey,
                    VodConfig.OPEN_DRIVE_KEY);
            String playerContent = builtIn.playerContent(flag, routed, VodConfig.get().getFlags());
            Result fallback = preparePlayerResult(openDriveSite, VodConfig.OPEN_DRIVE_KEY, flag,
                    playerContent);
            if (fallback.hasMsg() || TextUtils.isEmpty(fallback.getRealUrl())) {
                OpenDriveLog.resolveInvalid("catvod-fallback", sourceKey, "invalid_result", fallback.getMsg());
                SpiderDebug.log("player-route",
                        "sourceKey=%s,targetKey=%s,stage=catvod-fallback-invalid", sourceKey,
                        VodConfig.OPEN_DRIVE_KEY);
                return null;
            }
            return observeOpenDriveShadow(fallback, routed);
        } catch (Exception fallbackFailure) {
            OpenDriveLog.catvodFallbackFailed(sourceKey, fallbackFailure);
            SpiderDebug.log("player-route",
                    "sourceKey=%s,targetKey=%s,stage=catvod-fallback-failed,failure=%s",
                    sourceKey, VodConfig.OPEN_DRIVE_KEY,
                    fallbackFailure.getClass().getSimpleName());
            return null;
        }
    }

    static boolean shouldTryOpenDriveFallback(int linkRoute, String sourceId, Result result) {
        return linkRoute == AppSettings.LINK_ROUTE_DIRECT
                && sourceId != null
                && !sourceId.isEmpty()
                && !OpenDriveUri.hasScheme(sourceId)
                && !OpenDriveSpider.isRoutedId(sourceId)
                && result != null
                && (result.hasMsg() || result.getRealUrl().isEmpty());
    }

    private static void logOpenDriveFailure(String sourceKey, Exception error) {
        StackTraceElement origin = null;
        for (StackTraceElement element : error.getStackTrace()) {
            if (!element.getClassName().contains(".cloud.opendrive.")) continue;
            origin = element;
            break;
        }
        SpiderDebug.log("player-route",
                "sourceKey=%s,failure=%s,origin=%s", sourceKey,
                error.getClass().getSimpleName(), origin == null ? "unknown"
                        : origin.getClassName() + ":" + origin.getLineNumber());
    }

    private static Result observeOpenDriveShadow(Result result, String requestedId) {
        OpenDriveShadowProbe.Observation observation =
                OPEN_DRIVE_SHADOW_PROBE.inspect(result.getRealUrl());
        SpiderDebug.log("opendrive-shadow", "status=%s,equivalent=%s",
                observation.status(), observation.byteEquivalent());
        if (!OpenDriveUri.isValid(result.getMediaUri())) result.setMediaUri("");
        if (OpenDriveUri.isValid(requestedId)) {
            result.setMediaUri(requestedId);
        } else if (!result.hasMediaUri()) {
            String mediaUri = OpenDriveIdentityHolder.CODEC.encode(
                    result.getRealUrl(), result.getHeader());
            if (!mediaUri.isEmpty()) result.setMediaUri(mediaUri);
        }
        return result;
    }

    /** Defers Room initialization until a worker-thread player request needs identity encoding. */
    private static final class OpenDriveIdentityHolder {
        private static final OpenDriveIdentityCodec CODEC = AndroidMediaPipelineFactory.identityCodec();
    }

    private static int routeOpenDriveEpisodes(Vod vod) {
        if (vod == null || AppSettings.getLinkRoute() != AppSettings.LINK_ROUTE_BUILTIN) return 0;
        Site site = VodConfig.get().getSite(VodConfig.OPEN_DRIVE_KEY);
        Spider spider = site.spider();
        return spider instanceof OpenDriveSpider openDrive ? openDrive.rewriteExternalEpisodes(vod) : 0;
    }

    @NonNull
    public static Result searchContent(@NonNull Site site, @NonNull String keyword, boolean quick, @NonNull String page) throws Exception {
        SpiderDebug.log("search", "site=%s,keyword=%s,quick=%s,page=%s", site.getName(), keyword, quick, page);
        boolean hasPage = !page.equals("1");
        if (isSpider(site)) {
            if (Prefers.getBoolean("crash")) return Result.empty();
            String searchContent = hasPage ? site.recent().spider().searchContent(keyword, quick, page) : site.recent().spider().searchContent(keyword, quick);
            SpiderDebug.log("search", searchContent);
            Result result = Result.fromJson(searchContent);
            for (Vod vod : result.getList()) vod.setSite(site);
            return VideoRankManager.apply(site.getKey(), result);
        } else {
            ArrayMap<String, String> params = new ArrayMap<>();
            params.put("wd", keyword);
            params.put("quick", String.valueOf(quick));
            params.put("extend", "");
            if (hasPage) params.put("pg", page);
            String searchContent = call(site, params);
            SpiderDebug.log("search", searchContent);
            Result result = fetchPic(site, Result.fromType(site.getType(), searchContent));
            for (Vod vod : result.getList()) vod.setSite(site);
            return VideoRankManager.apply(site.getKey(), result);
        }
    }

    @NonNull
    public static Result action(@NonNull String key, @NonNull String action) throws Exception {
        return Result.fromJson(actionRaw(key, action));
    }

    /**
     * Runs a provider-defined action without discarding provider-specific fields.
     *
     * <p>Most actions return a normal {@link Result}. Cloud-storage management uses
     * a versioned JSON envelope which must be parsed by its caller, so it needs the
     * original response.</p>
     */
    @NonNull
    public static String actionRaw(@NonNull String key, @NonNull String action) throws Exception {
        Site site = VodConfig.get().getSite(key);
        SpiderDebug.log("action", "key=%s,action=%s", key, action);
        if (site.getType() == 3) {
            if (Prefers.getBoolean("crash")) return "";
            /*
             * Provider actions may open account, QR, or verification dialogs.
             * They require the Activity-backed app process just like player QR
             * challenges; RemoteSpider has no UI context and crashes on mobile.
             */
            Spider spider = BaseLoader.get().getInteractiveSpider(site.getKey(), site.getApi(), site.getExt(), site.getJar());
            String response = spider.action(action);
            return response == null ? "" : response;
        }
        if (site.getType() == 4) return OkHttp.string(action);
        return "";
    }

    static boolean hasLoginAction(@NonNull Result result) {
        return result.getList().stream().anyMatch(vod -> LOGIN_ACTION.equals(vod.getAction()));
    }

    @NonNull
    public static Result fetchPic(@NonNull Site site, @NonNull Result result) throws Exception {
        if (site.getType() > 2 || result.getList().isEmpty() || !result.getVod().getPic().isEmpty()) return result;
        ArrayList<String> ids = new ArrayList<>();
        boolean empty = site.getCategories().isEmpty();
        for (Vod item : result.getList()) if (empty || site.getCategories().contains(item.getTypeName())) ids.add(item.getId());
        if (ids.isEmpty()) return result.clear();
        ArrayMap<String, String> params = new ArrayMap<>();
        params.put("ac", ac(site.getType()));
        params.put("ids", TextUtils.join(",", ids));
        try (Response response = OkHttp.newCall(site.getApi(), site.getHeader(), params).execute()) {
            okhttp3.ResponseBody body = response.body();
            result.setList(Result.fromType(site.getType(), body != null ? body.string() : "").getList());
            return result;
        }
    }

    private static void setTypes(@NonNull Site site, @NonNull Result result) {
        result.getTypes().stream().filter(type -> result.getFilters().containsKey(type.getTypeId())).forEach(type -> type.setFilters(result.getFilters().get(type.getTypeId())));
        if (site.getCategories().isEmpty()) return;
        List<Class> types = result.getTypes();
        Map<String, Class> typeByName = new HashMap<>((int)(types.size() / 0.75f) + 1);
        types.forEach(type -> typeByName.put(type.getTypeName(), type));
        List<String> orderedNames = CatalogTypeSelectionPolicy.orderNames(
                types.stream().map(Class::getTypeName).toList(), site.getCategories());
        result.setTypes(orderedNames.stream().map(typeByName::get).filter(Objects::nonNull).toList());
    }
}
