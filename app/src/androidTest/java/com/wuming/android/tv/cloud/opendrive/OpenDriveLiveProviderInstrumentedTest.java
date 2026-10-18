package com.wuming.android.tv.cloud.opendrive;

import com.wuming.tv.core.cloud.*;

import static org.junit.Assert.assertNotNull;

import android.os.SystemClock;
import android.util.Log;
import android.webkit.CookieManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wuming.android.tv.api.SiteApi;
import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.bean.Episode;
import com.wuming.android.tv.bean.Flag;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.bean.Vod;
import com.wuming.tv.core.media.MediaSourceDescriptor;
import com.wuming.tv.core.media.OpenDriveUri;
import com.wuming.tv.core.media.catalog.MediaManifest;
import com.wuming.tv.core.playback.OpenDrivePlaybackHints;
import com.wuming.android.tv.media.catalog.RoomMediaCatalog;
import com.wuming.android.tv.server.Server;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Cookie;
import okhttp3.HttpUrl;
import okhttp3.Response;

/** Device-only smoke tests. Missing credentials are reported as skipped, never fabricated. */
@RunWith(AndroidJUnit4.class)
public class OpenDriveLiveProviderInstrumentedTest {

    private static final String TAG = "OpenDriveLiveAudit";

    @Test
    public void aliOriginalWithoutProxyCanDecode() throws Exception {
        Assume.assumeTrue("Explicit direct playback diagnostic only", "true".equals(
                InstrumentationRegistry.getArguments().getString("fixture_no_proxy", "")));
        org.junit.Assert.assertEquals("alipan", InstrumentationRegistry.getArguments().getString("fixture_provider"));
        try (var direct = new OpenDriveNoProxyScope()) {
            bundledPublicSharesCanStreamOriginalAndSmartRoutes();
        }
    }

    @Test
    public void aliTrackedOriginalCopiesCanBeCleaned() throws Exception {
        Assume.assumeTrue("Explicit tracked-copy diagnostic only", "true".equals(
                InstrumentationRegistry.getArguments().getString("run_ali_cache_cleanup_probe", "")));
        OpenDriveHttpAudit.install();
        AliPanOpenDriveProvider provider = new AliPanOpenDriveProvider();
        org.junit.Assert.assertTrue(provider.authenticated());
        for (var record : com.wuming.android.tv.cloud.CloudStorageRepository.active(
                com.wuming.android.tv.cloud.CloudStorageProvider.ALIPAN)) {
            if (record.sourceSiteKey.startsWith(AliSharedOriginalCache.OWNER)) provider.deleteTracked(record);
        }
    }

    @Test
    public void aliOriginalSavedCopyCanStream() throws Exception {
        Assume.assumeTrue("Explicit disposable-copy diagnostic only", "true".equals(
                InstrumentationRegistry.getArguments().getString("run_ali_save_probe", "")));
        OpenDriveHttpAudit.install();
        AliPanOpenDriveProvider provider = new AliPanOpenDriveProvider();
        org.junit.Assert.assertTrue(provider.authenticated());
        provider.list("root"); // Existing account path refreshes before the diagnostic.
        String shareUrl = InstrumentationRegistry.getArguments().getString("fixture_url", "");
        String fileName = InstrumentationRegistry.getArguments().getString("fixture_file", "");
        var video = provider.listShare(shareUrl).stream().filter(x -> fileName.equals(x.name()))
                .findFirst().orElseThrow();
        java.lang.reflect.Method require = AliPanOpenDriveProvider.class.getDeclaredMethod("require", String.class);
        require.setAccessible(true);
        Object ref = require.invoke(provider, video.id());
        java.lang.reflect.Method fileId = ref.getClass().getDeclaredMethod("fileId");
        java.lang.reflect.Method shareId = ref.getClass().getDeclaredMethod("shareId");
        java.lang.reflect.Method shareToken = ref.getClass().getDeclaredMethod("shareToken");
        fileId.setAccessible(true); shareId.setAccessible(true); shareToken.setAccessible(true);
        JsonObject auth = JsonParser.parseString(OpenDriveTokenStore.get("alipan")).getAsJsonObject();
        String drive = OpenDriveHttp.string(auth, "default_drive_id");
        if (drive.isBlank()) drive = OpenDriveHttp.string(auth, "resource_drive_id");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + OpenDriveHttp.string(auth, "access_token"));
        headers.put("Referer", "https://www.alipan.com/");
        headers.put("X-Canary", "client=Android,app=adrive,version=v4.3.1");
        String folder = "";
        try {
            JsonObject created = OpenDriveHttp.postJson("https://api.aliyundrive.com/adrive/v2/file/createWithFolders",
                    OpenDriveHttp.json("drive_id", drive, "parent_file_id", "root", "type", "folder",
                            "name", "wuming-stream-probe-" + UUID.randomUUID(), "check_name_mode", "refuse"), headers);
            folder = OpenDriveHttp.string(created, "file_id");
            org.junit.Assert.assertFalse("No newly created diagnostic folder", folder.isBlank());
            JsonObject copy = OpenDriveHttp.json("share_id", shareId.invoke(ref), "file_id", fileId.invoke(ref),
                    "to_drive_id", drive, "to_parent_file_id", folder, "auto_rename", false);
            JsonObject request = OpenDriveHttp.json("id", "0", "method", "POST", "url", "/file/copy");
            request.add("body", copy);
            request.add("headers", OpenDriveHttp.json("Content-Type", "application/json"));
            com.google.gson.JsonArray requests = new com.google.gson.JsonArray(); requests.add(request);
            JsonObject batch = OpenDriveHttp.json("resource", "file"); batch.add("requests", requests);
            Map<String, String> copyHeaders = new LinkedHashMap<>(headers);
            copyHeaders.put("x-share-token", (String) shareToken.invoke(ref));
            JsonObject result = OpenDriveHttp.postJson("https://api.aliyundrive.com/adrive/v4/batch", batch, copyHeaders);
            JsonObject response = result.getAsJsonArray("responses").get(0).getAsJsonObject();
            org.junit.Assert.assertTrue("Copy status " + OpenDriveHttp.number(response, "status"),
                    OpenDriveHttp.number(response, "status") >= 200 && OpenDriveHttp.number(response, "status") < 300);
            String saved = OpenDriveHttp.string(OpenDriveHttp.object(response, "body"), "file_id");
            org.junit.Assert.assertFalse("Copy omitted destination", saved.isBlank());
            JsonObject download = OpenDriveHttp.postJson("https://api.aliyundrive.com/v2/file/get_download_url",
                    OpenDriveHttp.json("drive_id", drive, "file_id", saved, "expire_sec", 900), headers);
            assertMediaSample(AliPanOpenDriveProvider.downloadUrl(download, false),
                    Map.of("Referer", "https://www.alipan.com/"), "alipan:saved-original", false, 0);
        } finally {
            // This UUID folder was created by this invocation; never target an existing
            // user folder or clear the account recycle bin to make a diagnostic pass.
            if (!folder.isBlank()) OpenDriveHttp.postJson("https://api.aliyundrive.com/v2/recyclebin/trash",
                    OpenDriveHttp.json("drive_id", drive, "file_id", folder), headers);
        }
    }

    @Test
    public void aliyunAccountCanListRootWhenLoggedIn() throws Exception {
        auditAccount("alipan");
    }

    @Test
    public void baiduAccountCanListRootWhenLoggedIn() throws Exception {
        auditAccount("baidu");
    }

    @Test
    public void quarkAccountCanListRootWhenLoggedIn() throws Exception {
        auditAccount("quark");
    }

    @Test
    public void ucAccountCanListRootWhenLoggedIn() throws Exception {
        auditAccount("uc");
    }

    @Test
    public void ucAccountPlaybackPreservesAuthorizationResultWhenVideoExists() throws Exception {
        VodConfig.get().ensureLoaded();
        OpenDriveEngine engine = new OpenDriveEngine();
        OpenDriveProvider provider = engine.provider("uc");
        Assume.assumeTrue("No host-owned credentials for uc", provider.authenticated());
        OpenDriveItem video = firstVideo(engine, "uc", 256);
        String itemId = video == null ? storedItemId("uc") : video.id();
        Assume.assumeTrue("No persisted or discoverable UC video", !itemId.isBlank());
        OpenDrivePlayback playback = engine.resolve("uc", itemId);
        okhttp3.HttpUrl proxyUrl = okhttp3.HttpUrl.parse(playback.url());
        assertNotNull(proxyUrl);
        if (!"127.0.0.1".equals(proxyUrl.host())) {
            try (Response response = OpenDriveHttp.openExplicit(
                    playback.url(), "bytes=0-1023", playback.headers())) {
                org.junit.Assert.assertTrue("UC personal playback HTTP " + response.code(),
                        response.isSuccessful());
                assertNotNull(response.body());
                org.junit.Assert.assertTrue("UC personal playback returned no bytes",
                        response.body().bytes().length > 0);
            }
            return;
        }
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        for (String name : proxyUrl.queryParameterNames()) {
            params.put(name, proxyUrl.queryParameter(name));
        }
        params.put("range", "bytes=0-1023");
        OpenDriveProxyResponse response = provider.proxy(params);
        assertNotNull(response);
        org.junit.Assert.assertTrue("UC personal playback HTTP " + response.getStatus(),
                response.getStatus() >= 200 && response.getStatus() < 300);
        try (java.io.InputStream input = response.getStream()) {
            org.junit.Assert.assertTrue("UC personal playback returned no bytes",
                    input.readNBytes(1_024).length > 0);
        }
    }

    @Test
    public void ucDownloadHeadersMatchLiveLinkSwiftBehavior() throws Exception {
        VodConfig.get().ensureLoaded();
        OpenDriveEngine engine = new OpenDriveEngine();
        OpenDriveProvider provider = engine.provider("uc");
        Assume.assumeTrue("No host-owned credentials for uc", provider.authenticated());
        String storedUri = OpenDriveDeviceFixtures.mediaUri("uc");
        Assume.assumeTrue(OpenDriveUri.isValid(storedUri));
        MediaManifest manifest = new RoomMediaCatalog().find(OpenDriveUri.parse(storedUri))
                .orElse(null);
        Assume.assumeNotNull(manifest);
        MediaSourceDescriptor source = manifest.sources().stream()
                .filter(item -> "org.opendrive.provider".equals(item.adapterId())
                        && "uc".equals(item.locator().get("provider")))
                .findFirst().orElse(null);
        Assume.assumeNotNull(source);
        String itemId = source.locator().get("handle");

        String desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) uc-cloud-drive/2.5.20 "
                + "Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 "
                + "Safari/537.36 Channel/pckk_other_ch";
        LinkedHashMap<String, Map<String, String>> variants = new LinkedHashMap<>();
        variants.put("linkswift-browser", Map.of(
                "Accept", "*/*", "Origin", "https://drive.uc.cn",
                "Referer", "https://drive.uc.cn/", "User-Agent", desktopUa));
        variants.put("linkswift-external", Map.of(
                "Accept", "*/*", "Referer", "https://drive.uc.cn/",
                "User-Agent", desktopUa, "Cookie", "{stream-cookie}"));
        variants.put("linkswift-full", Map.of(
                "Accept", "*/*", "Origin", "https://drive.uc.cn",
                "Referer", "https://drive.uc.cn/", "User-Agent", desktopUa,
                "Cookie", "{stream-cookie}"));
        variants.put("opendrive-legacy", Map.of(
                "Referer", "https://drive.uc.cn", "User-Agent", desktopUa,
                "Cookie", "{stream-cookie}"));

        LinkedHashMap<String, Integer> statuses = new LinkedHashMap<>();
        boolean accepted = false;
        for (Map.Entry<String, Map<String, String>> variant : variants.entrySet()) {
            OpenDrivePlayback playback = engine.resolve("uc", itemId);
            StreamDiagnostic stream = streamDiagnostic(provider, playback.url());
            LinkedHashMap<String, String> headers = new LinkedHashMap<>(variant.getValue());
            if ("{stream-cookie}".equals(headers.get("Cookie"))) {
                headers.put("Cookie", stream.cookie());
            }
            try (Response response = headers.containsKey("Cookie")
                    ? OpenDriveHttp.openExplicit(stream.url(), "bytes=0-1023", headers)
                    : OpenDriveHttp.open(stream.url(), "bytes=0-1023", headers)) {
                statuses.put(variant.getKey(), response.code());
                Log.i(TAG, "provider=uc,headerVariant=" + variant.getKey()
                        + ",downloadHost=" + response.request().url().host()
                        + ",http=" + response.code());
                if (response.isSuccessful()) {
                    assertNotNull(response.body());
                    org.junit.Assert.assertTrue(response.body().byteStream()
                            .readNBytes(1_024).length > 0);
                    accepted = true;
                }
            }
        }
        org.junit.Assert.assertTrue("No LinkSwift header variant was accepted: " + statuses,
                accepted);
    }

    @Test
    public void ucCookieStoresMatchLinkSwiftBrowserSession() {
        VodConfig.get().ensureLoaded();
        OpenDriveProvider provider = new OpenDriveEngine().provider("uc");
        Assume.assumeTrue("No host-owned credentials for uc", provider.authenticated());

        Map<String, String> token = cookiePairs(OpenDriveTokenStore.get("uc"));
        Map<String, String> jar = new LinkedHashMap<>();
        HttpUrl api = HttpUrl.parse("https://pc-api.uc.cn/");
        assertNotNull(api);
        for (Cookie cookie : com.github.catvod.net.PersistentCookieJar.get()
                .loadForStorageDomain("uc.cn")) {
            jar.put(cookie.name(), cookie.value());
        }

        AtomicReference<String> driveHeader = new AtomicReference<>("");
        AtomicReference<String> broccoliHeader = new AtomicReference<>("");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CookieManager manager = CookieManager.getInstance();
            driveHeader.set(manager.getCookie("https://drive.uc.cn/") == null
                    ? "" : manager.getCookie("https://drive.uc.cn/"));
            broccoliHeader.set(manager.getCookie("https://broccoli.uc.cn/") == null
                    ? "" : manager.getCookie("https://broccoli.uc.cn/"));
        });
        Map<String, String> drive = cookiePairs(driveHeader.get());
        Map<String, String> broccoli = cookiePairs(broccoliHeader.get());

        Log.i(TAG, "provider=uc,cookieSource=token,names=" + new TreeSet<>(token.keySet()));
        Log.i(TAG, "provider=uc,cookieSource=jar,names=" + new TreeSet<>(jar.keySet()));
        Log.i(TAG, "provider=uc,cookieSource=webview-drive,names="
                + new TreeSet<>(drive.keySet()));
        Log.i(TAG, "provider=uc,cookieSource=webview-broccoli,names="
                + new TreeSet<>(broccoli.keySet()));
        for (String name : List.of("__pus", "__puus")) {
            Log.i(TAG, "provider=uc,cookie=" + name
                    + ",tokenEqualsJar=" + sameCookie(token, jar, name)
                    + ",tokenEqualsDrive=" + sameCookie(token, drive, name)
                    + ",tokenEqualsBroccoli=" + sameCookie(token, broccoli, name));
        }
        org.junit.Assert.assertTrue("Persisted UC token lacks __pus", token.containsKey("__pus"));
    }

    @Test
    public void ucDownloadCookieRotationKeepsAUsableBinding() throws Exception {
        VodConfig.get().ensureLoaded();
        OpenDriveEngine engine = new OpenDriveEngine();
        OpenDriveProvider provider = engine.provider("uc");
        Assume.assumeTrue("No host-owned credentials for uc", provider.authenticated());
        String itemId = storedItemId("uc");
        Assume.assumeTrue("No persisted UC video", !itemId.isBlank());

        Map<String, String> before = cookiePairs(OpenDriveTokenStore.get("uc"));
        OpenDrivePlayback playback = engine.resolve("uc", itemId);
        StreamDiagnostic stream = streamDiagnostic(provider, playback.url());
        Map<String, String> after = cookiePairs(OpenDriveTokenStore.get("uc"));
        Map<String, String> bound = cookiePairs(stream.cookie());

        boolean rotated = !java.util.Objects.equals(before.get("__puus"), after.get("__puus"));
        int beforeStatus = ucRangeStatus(stream.url(), cookieHeader(before));
        int afterStatus = ucRangeStatus(stream.url(), cookieHeader(after));
        Log.i(TAG, "provider=uc,downloadCookieRotated=" + rotated
                + ",streamMatchesBefore=" + sameCookie(bound, before, "__puus")
                + ",streamMatchesAfter=" + sameCookie(bound, after, "__puus")
                + ",beforeHttp=" + beforeStatus + ",afterHttp=" + afterStatus);
        org.junit.Assert.assertTrue("Neither UC cookie snapshot authorizes the signed URL",
                beforeStatus >= 200 && beforeStatus < 300
                        || afterStatus >= 200 && afterStatus < 300);
    }

    private static int ucRangeStatus(String url, String cookie) throws Exception {
        Map<String, String> headers = Map.of(
                "Accept", "*/*",
                "Referer", "https://drive.uc.cn/",
                "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) uc-cloud-drive/2.5.20 "
                        + "Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 "
                        + "Safari/537.36 Channel/pckk_other_ch",
                "Cookie", cookie);
        try (Response response = OpenDriveHttp.openExplicit(url, "bytes=0-1023", headers)) {
            return response.code();
        }
    }

    private static String cookieHeader(Map<String, String> cookies) {
        StringBuilder result = new StringBuilder();
        cookies.forEach((name, value) -> {
            if (result.length() > 0) result.append("; ");
            result.append(name).append('=').append(value);
        });
        return result.toString();
    }

    private static boolean sameCookie(Map<String, String> left, Map<String, String> right,
            String name) {
        return left.containsKey(name) && left.get(name).equals(right.get(name));
    }

    private static Map<String, String> cookiePairs(String header) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (header == null || header.isBlank()) return result;
        for (String pair : header.split(";")) {
            int separator = pair.indexOf('=');
            if (separator <= 0) continue;
            String name = pair.substring(0, separator).trim();
            String value = pair.substring(separator + 1).trim();
            if (!name.isBlank()) result.put(name, value);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static StreamDiagnostic streamDiagnostic(OpenDriveProvider provider,
            String proxyAddress) throws Exception {
        okhttp3.HttpUrl proxyUrl = okhttp3.HttpUrl.parse(proxyAddress);
        assertNotNull(proxyUrl);
        String handle = proxyUrl.queryParameter("stream");
        java.lang.reflect.Field streamsField = provider.getClass().getDeclaredField("streams");
        streamsField.setAccessible(true);
        Map<String, Object> streams = (Map<String, Object>) streamsField.get(provider);
        Object stream = handle == null ? null : streams.get(handle);
        if (stream == null && handle == null) {
            for (Object candidate : streams.values()) {
                java.lang.reflect.Field candidateUrl = candidate.getClass().getDeclaredField("url");
                candidateUrl.setAccessible(true);
                if (proxyAddress.equals(candidateUrl.get(candidate))) {
                    stream = candidate;
                    break;
                }
            }
        }
        assertNotNull(stream);
        java.lang.reflect.Field urlField = stream.getClass().getDeclaredField("url");
        java.lang.reflect.Field cookieField = stream.getClass().getDeclaredField("cookie");
        java.lang.reflect.Field savedFidField = stream.getClass().getDeclaredField("savedFid");
        java.lang.reflect.Field transcodedField = stream.getClass().getDeclaredField("transcoded");
        urlField.setAccessible(true);
        cookieField.setAccessible(true);
        savedFidField.setAccessible(true);
        transcodedField.setAccessible(true);
        long expectedSize = 0L;
        try {
            // UC records the expected download size; Quark's original proxy instead
            // keeps a media offset. The byte-read fixture only needs this diagnostic
            // when the provider exposes it, so do not couple their stream layouts.
            java.lang.reflect.Field expectedSizeField = stream.getClass()
                    .getDeclaredField("expectedSize");
            expectedSizeField.setAccessible(true);
            expectedSize = expectedSizeField.getLong(stream);
        } catch (NoSuchFieldException ignored) {
        }
        return new StreamDiagnostic((String) urlField.get(stream), (String) cookieField.get(stream),
                (String) savedFidField.get(stream), expectedSize,
                transcodedField.getBoolean(stream));
    }

    private record StreamDiagnostic(String url, String cookie, String savedFid,
                                    long expectedSize, boolean transcoded) {
    }

    private static String storedItemId(String providerId) {
        String storedUri = OpenDriveDeviceFixtures.mediaUri(providerId);
        if (!OpenDriveUri.isValid(storedUri)) return "";
        MediaManifest manifest = new RoomMediaCatalog().find(OpenDriveUri.parse(storedUri))
                .orElse(null);
        if (manifest == null) return "";
        return manifest.sources().stream()
                .filter(item -> "org.opendrive.provider".equals(item.adapterId())
                        && providerId.equals(item.locator().get("provider")))
                .map(item -> item.locator().get("handle"))
                .filter(item -> item != null && !item.isBlank())
                .findFirst().orElse("");
    }

    @Test
    public void ucHistoryShareSavesBeforePlayback() throws Exception {
        OpenDriveDeviceFixtures.ExternalFixture fixture =
                OpenDriveDeviceFixtures.externalEpisode("uc");
        Assume.assumeNotNull(fixture);
        assertUcDirectPlayback(fixture.flag(), fixture.payload());
    }

    @Test
    public void ucConfiguredSourceSavesBeforePlayback() throws Exception {
        android.os.Bundle arguments = InstrumentationRegistry.getArguments();
        String sourceName = arguments.getString("source_name", "玩偶哥哥");
        String query = arguments.getString("query", "野狗骨头");
        String routeName = arguments.getString("route_name", "优汐无限");
        Site source = waitForSite(sourceName);
        Assume.assumeNotNull("Configured source was not found", source);

        Result search = SiteApi.searchContent(source, query, false, "1");
        List<Vod> matches = search.getList().stream()
                .filter(vod -> vod.getVodName().contains(query) || query.contains(vod.getVodName()))
                .toList();
        Assume.assumeTrue("Configured source did not return the requested title",
                !matches.isEmpty());
        Flag route = null;
        List<String> inspectedLabels = new java.util.ArrayList<>();
        for (Vod match : matches) {
            Vod detail = SiteApi.detailContent(source.getKey(), match.getVodId()).getVod();
            if (detail == null) continue;
            inspectedLabels.addAll(detail.getFlags().stream().map(Flag::getFlag).toList());
            route = detail.getFlags().stream()
                    .filter(flag -> flag.getFlag().contains(routeName))
                    .findFirst().orElse(null);
            if (route == null) route = detail.getFlags().stream()
                    .filter(flag -> "uc".equals(ExternalEpisodePayload.labeledProviderId(flag.getFlag()))
                            || flag.getEpisodes().stream().anyMatch(episode ->
                            episode.getUrl().startsWith("opendrive-deferred:uc:")))
                    .findFirst().orElse(null);
            if (route != null) break;
        }
        Assume.assumeNotNull("Requested UC route was not found; titleMatches="
                + matches.size() + ", routeLabels=" + inspectedLabels, route);
        Episode episode = route.getEpisodes().isEmpty() ? null : route.getEpisodes().get(0);
        Assume.assumeNotNull(episode);

        assertUcDirectPlayback(route.getFlag(), episode.getUrl());
    }

    private static Site waitForSite(String name) {
        VodConfig.get().ensureLoaded();
        for (int retry = 0; retry < 20; retry++) {
            Site site = VodConfig.get().getSites().stream()
                    .filter(item -> item.getName().equals(name))
                    .findFirst().orElse(null);
            if (site == null) site = VodConfig.get().getSites().stream()
                    .filter(item -> item.getName().contains(name))
                    .findFirst().orElse(null);
            if (site != null) return site;
            SystemClock.sleep(250);
        }
        return null;
    }

    private static void assertUcDirectPlayback(String flag, String payload) throws Exception {
        OpenDriveEngine engine = new OpenDriveEngine();
        OpenDriveSpider spider = new OpenDriveSpider(engine);
        // Routing requires an authenticated, available provider; treat a momentarily
        // unauthenticated UC account as an unsatisfied precondition, consistent with
        // the credential assumptions used by the other live tests in this class.
        Assume.assumeTrue("No authenticated provider available for routing",
                engine.provider("uc").authenticated());
        String routed = OpenDriveSpider.isRoutedId(payload)
                ? payload : spider.routeExternalEpisode(flag, payload);
        assertNotNull("UC payload could not be routed", routed);
        OpenDriveProvider provider;
        OpenDrivePlayback playback;
        if (OpenDriveSpider.isDeferredId(routed)) {
            String remaining = routed.substring("opendrive-deferred:".length());
            int separator = remaining.indexOf(':');
            org.junit.Assert.assertTrue("Invalid deferred UC route", separator > 0);
            String providerId = remaining.substring(0, separator);
            String cacheHandle = new String(java.util.Base64.getUrlDecoder().decode(
                    remaining.substring(separator + 1)), java.nio.charset.StandardCharsets.UTF_8);
            OpenDriveExternalEpisodeCache.Materialized materialized =
                    OpenDriveExternalEpisodeCache.materialize(providerId, cacheHandle);
            provider = materialized.provider();
            playback = provider.resolve(materialized.itemId());
        } else {
            JsonObject player = JsonParser.parseString(
                    spider.playerContent(flag, routed, List.of())).getAsJsonObject();
            provider = engine.provider("uc");
            playback = new OpenDrivePlayback(player.get("url").getAsString(),
                    java.util.Collections.emptyMap(), System.currentTimeMillis() + 60_000L);
        }
        StreamDiagnostic stream = streamDiagnostic(provider, playback.url());
        org.junit.Assert.assertNotNull("UC share was not saved into the account",
                stream.savedFid());
        HttpUrl proxyUrl = HttpUrl.parse(playback.url());
        assertNotNull(proxyUrl);
        Log.i(TAG, "provider=uc,playbackHost=" + proxyUrl.host()
                + ",playbackPath=" + proxyUrl.encodedPath());
        OpenDriveProxyResponse response = null;
        try {
            // Deferred external episodes own a provider instance in the external-episode
            // cache. Its in-memory stream handle is therefore valid only on that same
            // provider, not on the separate engine constructed by this test.
            org.junit.Assert.assertTrue("UC share did not use its personal-drive transcode",
                    stream.transcoded());
            if (!"127.0.0.1".equals(proxyUrl.host())) {
                try (Response direct = OpenDriveHttp.openExplicit(
                        playback.url(), "bytes=0-1023", playback.headers())) {
                    org.junit.Assert.assertTrue("UC playback HTTP " + direct.code(),
                            direct.isSuccessful());
                    assertNotNull(direct.body());
                    org.junit.Assert.assertTrue("UC share playback returned no bytes",
                            direct.body().bytes().length > 0);
                    Log.i(TAG, "provider=uc,shareSavedPlaybackHttp=" + direct.code()
                            + ",personalTranscode=true,directPlayback=true");
                }
            } else {
                LinkedHashMap<String, String> params = new LinkedHashMap<>();
                for (String name : proxyUrl.queryParameterNames()) {
                    params.put(name, proxyUrl.queryParameter(name));
                }
                params.put("range", "bytes=0-1023");
                response = provider.proxy(params);
                assertNotNull(response);
                org.junit.Assert.assertTrue("UC playback HTTP " + response.getStatus(),
                        response.getStatus() >= 200 && response.getStatus() < 300);
                try (java.io.InputStream input = response.getStream()) {
                    org.junit.Assert.assertTrue("UC share playback returned no bytes",
                            input.readNBytes(1_024).length > 0);
                }
                Log.i(TAG, "provider=uc,shareSavedPlaybackHttp=" + response.getStatus()
                        + ",personalTranscode=true,directPlayback=false");
            }
        } finally {
            java.lang.reflect.Method cleanup = provider.getClass().getDeclaredMethod(
                    "cleanupSaved", String.class);
            cleanup.setAccessible(true);
            cleanup.invoke(provider, stream.savedFid());
        }
    }

    @Test
    public void baiduCanAuthorizeARealPlaybackRangeWhenVideoExists() throws Exception {
        OpenDriveEngine engine = new OpenDriveEngine();
        OpenDriveProvider provider = engine.provider("baidu");
        Assume.assumeTrue("No host-owned credentials for baidu", provider.authenticated());

        OpenDriveItem video = firstVideo(engine, "baidu", 32);
        Assume.assumeNotNull(video);
        OpenDrivePlayback playback = engine.resolve("baidu", video.id());
        assertNotNull(playback);

        try (Response response = OpenDriveHttp.open(
                playback.url(), "bytes=0-1023", playback.headers())) {
            org.junit.Assert.assertTrue("Baidu playback HTTP " + response.code(),
                    response.isSuccessful());
            assertNotNull(response.body());
            Log.i(TAG, "provider=baidu,playbackHttp=" + response.code());
        }
    }

    @Test
    public void pan123AccountCanListRootWhenLoggedIn() throws Exception {
        auditAccount("123pan");
    }

    @Test
    public void tianyiAccountCanListRootWhenLoggedIn() throws Exception {
        auditAccount("tianyi");
    }

    @Test
    public void suppliedPublicShareCanListAndResolveWhenAuthenticated() throws Exception {
        String providerId = InstrumentationRegistry.getArguments().getString("provider", "");
        String shareUrl = InstrumentationRegistry.getArguments().getString("share_url", "");
        List<OpenDriveDeviceFixtures.ShareFixture> fixtures = new java.util.ArrayList<>();
        if (!providerId.isBlank() && !shareUrl.isBlank()) {
            fixtures.add(new OpenDriveDeviceFixtures.ShareFixture(providerId, shareUrl));
        } else {
            fixtures.addAll(OpenDriveDeviceFixtures.publicShares());
        }
        Assume.assumeTrue("No public share URL stored in app history or provider vault",
                !fixtures.isEmpty());

        Exception lastFailure = null;
        for (OpenDriveDeviceFixtures.ShareFixture fixture : fixtures) {
            try {
                if (auditShare(fixture)) return;
            } catch (Exception error) {
                lastFailure = error;
                Log.i(TAG, "provider=" + fixture.providerId() + ",shareFixture=invalid");
            }
        }
        throw new AssertionError("No valid public share among " + fixtures.size()
                + " app-owned candidate(s)", lastFailure);
    }

    @Test
    public void bundledPublicSharesCanStreamOriginalAndSmartRoutes() throws Exception {
        if ("true".equals(InstrumentationRegistry.getArguments().getString("http_audit", "")))
            OpenDriveHttpAudit.install();
        List<OpenDriveDeviceFixtures.ShareFixture> fixtures =
                OpenDriveDeviceFixtures.bundledPublicShares();
        String selectedProvider = InstrumentationRegistry.getArguments()
                .getString("fixture_provider", "");
        String suppliedUrl = InstrumentationRegistry.getArguments().getString("fixture_url", "");
        String suppliedName = InstrumentationRegistry.getArguments().getString("fixture_file", "");
        if (!suppliedUrl.isBlank()) {
            org.junit.Assert.assertFalse("fixture_provider is required", selectedProvider.isBlank());
            org.junit.Assert.assertFalse("fixture_file is required", suppliedName.isBlank());
            fixtures = List.of(new OpenDriveDeviceFixtures.ShareFixture(
                    selectedProvider, suppliedUrl, suppliedName));
        }
        if (!selectedProvider.isBlank()) {
            fixtures = fixtures.stream()
                    .filter(item -> selectedProvider.equals(item.providerId())).toList();
        }
        org.junit.Assert.assertEquals("Unexpected public fixture count",
                selectedProvider.isBlank() ? 4 : 1, fixtures.size());
        Server.get().start();
        List<String> failures = new ArrayList<>();
        Throwable firstFailure = null;
        for (OpenDriveDeviceFixtures.ShareFixture fixture : fixtures) {
            try {
                auditFixturePlaybackRoutes(fixture);
            } catch (Throwable error) {
                failures.add(fixture.providerId() + ":" + error.getClass().getSimpleName());
                if (firstFailure == null) firstFailure = error;
                Log.i(TAG, "provider=" + fixture.providerId() + ",fixtureRoutes=failed,type="
                        + error.getClass().getSimpleName() + ",detail="
                        + safeFailure(error));
            }
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("Public fixture route failures: " + failures, firstFailure);
        }
    }

    private static void auditFixturePlaybackRoutes(
            OpenDriveDeviceFixtures.ShareFixture fixture) throws Exception {
        OpenDriveEngine engine = OpenDriveRuntime.engine();
        OpenDriveProvider provider = engine.provider(fixture.providerId());
        org.junit.Assert.assertTrue("Fixture account is not authenticated: "
                + fixture.providerId(), provider.authenticated());
        Set<String> existingCopies = disposableCopies(provider);
        String smartReference = "";
        String sourceReference = "";
        String stage = "list-share";
        AssertionError primaryFailure = null;
        try {
            logFixtureStage(fixture.providerId(), stage);
            List<OpenDriveItem> items = provider.listShare(fixture.url());
            OpenDriveItem video = items.stream()
                    .filter(item -> fixture.fileName().equals(item.name()))
                    .findFirst().orElse(null);
            assertNotNull("Fixture video is missing for " + fixture.providerId(), video);

            String route = InstrumentationRegistry.getArguments().getString("fixture_route", "both");
            org.junit.Assert.assertTrue("Unknown fixture_route",
                    List.of("both", "original", "smart").contains(route));
            OpenDriveItem playbackItem = video;
            List<String> routeFailures = new ArrayList<>();
            // Run each route independently so an original failure cannot hide smart evidence.
            if (!"smart".equals(route)) {
                try {
                    stage = "resolve-original";
                    logFixtureStage(fixture.providerId(), stage);
                    OpenDrivePlayback original = provider.resolve(playbackItem.id());
                    if ("true".equals(InstrumentationRegistry.getArguments().getString("fixture_transport_probe", ""))) {
                        OpenDriveLiveTransportProbe.inspect(original);
                    }
                    if ("true".equals(InstrumentationRegistry.getArguments().getString("fixture_range_compare", ""))) {
                        com.wuming.android.tv.player.exo.AliRangeTransportComparison.inspect(original);
                    }
                    if ("true".equals(InstrumentationRegistry.getArguments().getString("fixture_proxy_compare", ""))) {
                        OpenDriveLiveTransportProbe.compareProxy(original);
                    }
                    if ("true".equals(InstrumentationRegistry.getArguments().getString("fixture_connection_ramp", ""))) {
                        org.junit.Assert.assertEquals("alipan", fixture.providerId());
                        AliConnectionRampProbe.inspect(original);
                    }
                    stage = "read-original";
                    logFixtureStage(fixture.providerId(), stage);
                    assertPlaybackBytes(provider, original, fixture.providerId() + ":original");
                    if ("true".equals(InstrumentationRegistry.getArguments().getString("fixture_decode", ""))) {
                        stage = "decode-original";
                        logFixtureStage(fixture.providerId(), stage);
                        OpenDriveLiveDecoder.verify(original, fixture.providerId(), "original");
                    }
                    reportFixtureRoute("original", true);
                } catch (Exception | AssertionError error) {
                    reportFixtureRoute("original", false);
                    routeFailures.add("original at " + stage + ": " + safeFailure(error));
                }
                if ("original".equals(route)) {
                    if (!routeFailures.isEmpty()) throw new AssertionError(routeFailures.toString());
                    return;
                }
            }

            // Quark replays recent completed save tasks. Deleting between these two
            // routes gave smart the original test's already-deleted destination (404/21001).
            // Keep the copy until both independent route results have been recorded.
            try {
                stage = "prepare-smart";
                logFixtureStage(fixture.providerId(), stage);
                org.junit.Assert.assertTrue("Unable to persist fixture handle for "
                        + fixture.providerId(), provider.persistHandle(playbackItem.id()));
                String reference = OpenDriveHandleVault.reference(
                        fixture.providerId(), playbackItem.id());
                sourceReference = reference;
                JsonObject metadata = JsonParser.parseString(OpenDriveTokenVault.get(reference))
                        .getAsJsonObject();
                applySmartMode(fixture.providerId(), metadata);
                String smartId = "fixture-smart-" + UUID.randomUUID();
                smartReference = OpenDriveHandleVault.reference(fixture.providerId(), smartId);
                // Store a separate opaque handle so this live test catches accidental
                // original/smart merging without adding a test-only production API.
                OpenDriveHandleVault.put(fixture.providerId(), smartId, metadata);
                org.junit.Assert.assertNotEquals("Original and smart handles were merged for "
                        + fixture.providerId(), playbackItem.id(), smartId);
                stage = "resolve-smart";
                logFixtureStage(fixture.providerId(), stage);
                OpenDrivePlayback smart = provider.resolve(smartId);
                stage = "read-smart";
                logFixtureStage(fixture.providerId(), stage);
                assertPlaybackBytes(provider, smart, fixture.providerId() + ":smart");
                if ("true".equals(InstrumentationRegistry.getArguments().getString("fixture_decode", ""))) {
                    stage = "decode-smart";
                    logFixtureStage(fixture.providerId(), stage);
                    OpenDriveLiveDecoder.verify(smart, fixture.providerId(), "smart");
                }
                reportFixtureRoute("smart", true);
            } catch (Exception | AssertionError error) {
                reportFixtureRoute("smart", false);
                routeFailures.add("smart at " + stage + ": " + safeFailure(error));
            }
            if (!routeFailures.isEmpty()) throw new AssertionError(routeFailures.toString());
        } catch (Throwable error) {
            primaryFailure = new AssertionError(fixture.providerId() + " fixture failed at " + stage, error);
            throw primaryFailure;
        } finally {
            if (!smartReference.isBlank()) OpenDriveHandleVault.remove(smartReference);
            if (!sourceReference.isBlank()) OpenDriveHandleVault.remove(sourceReference);
            try {
                cleanupNewDisposableCopies(provider, existingCopies);
            } catch (AssertionError cleanupFailure) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    private static void reportFixtureRoute(String route, boolean passed) {
        android.os.Bundle status = new android.os.Bundle();
        status.putString("fixture" + ("smart".equals(route) ? "Smart" : "Original"),
                passed ? "passed" : "failed");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }

    private static void logFixtureStage(String providerId, String stage) {
        Log.i(TAG, "provider=" + providerId + ",fixtureStage=" + stage);
    }

    private static String safeFailure(Throwable error) {
        String value = error.getMessage() == null ? "" : error.getMessage();
        value = value.replaceAll("https?://\\S+", "[url]")
                .replaceAll("[\\r\\n]+", " ").trim();
        return value.length() <= 160 ? value : value.substring(0, 160);
    }

    private static void applySmartMode(String providerId, JsonObject metadata) {
        switch (providerId) {
            case "quark" -> metadata.addProperty("transcodeResolution", "auto");
            case "uc", "alipan" -> metadata.addProperty("smart", true);
            case "baidu" -> metadata.addProperty("quality", "preview");
            default -> throw new IllegalArgumentException("No smart fixture route");
        }
    }

    private static void assertPlaybackBytes(OpenDriveProvider provider,
            OpenDrivePlayback playback, String label) throws Exception {
        HttpUrl url = HttpUrl.parse(playback.url());
        assertNotNull(label + " returned an invalid playback URL", url);
        if ("127.0.0.1".equals(url.host())) {
            StreamDiagnostic stream = streamDiagnostic(provider, playback.url());
            Map<String, String> headers = OpenDrivePlaybackHints.without(playback.headers(),
                    OpenDrivePlaybackHints.CONTENT_LENGTH,
                    OpenDrivePlaybackHints.PARALLEL_RANGE_PROFILE,
                    OpenDrivePlaybackHints.HLS,
                    OpenDrivePlaybackHints.EXPLICIT_COOKIE,
                    OpenDrivePlaybackHints.REFRESH_URI,
                    OpenDrivePlaybackHints.EXPIRES_AT,
                    OpenDrivePlaybackHints.QUARK_SMART_ORIGINAL_FALLBACK,
                    OpenDrivePlaybackHints.FALLBACK_REASON, OpenDrivePlaybackHints.COPY_LEASE);
            LinkedHashMap<String, String> upstreamHeaders = new LinkedHashMap<>(headers);
            if (!stream.cookie().isBlank()) upstreamHeaders.put("Cookie", stream.cookie());
            assertMediaSample(stream.url(), upstreamHeaders, label, true, 0);
            return;
        }
        assertMediaSample(playback.url(), com.wuming.tv.core.playback.PlaybackTransportPlan.parse(
                playback.url(), null, playback.headers()).networkHeaders(), label, false, 0);
    }

    private static void assertMediaSample(String url, Map<String, String> headers,
            String label, boolean explicit, int depth) throws Exception {
        org.junit.Assert.assertTrue(label + " playlist nesting exceeded", depth <= 3);
        byte[] prefix;
        try (Response response = explicit ? OpenDriveHttp.openExplicit(url, "bytes=0-1023", headers)
                : OpenDriveHttp.open(url, "bytes=0-1023", headers)) {
            org.junit.Assert.assertTrue(label + " HTTP " + response.code(), response.isSuccessful());
            assertNotNull(response.body());
            prefix = response.body().byteStream().readNBytes(1024);
        }
        org.junit.Assert.assertTrue(label + " returned too few bytes", prefix.length >= 16);
        String text = new String(prefix, java.nio.charset.StandardCharsets.UTF_8).trim();
        // A 200 JSON error page is not media, and a playlist alone does not prove its
        // segments are reachable. Check one actual segment before declaring a pass.
        org.junit.Assert.assertFalse(label + " returned a text error body",
                text.startsWith("{") || text.startsWith("[") || text.startsWith("<"));
        if (!text.startsWith("#EXTM3U")) {
            if (depth > 0) Log.i(TAG, label + ",fixtureHlsSegment=bytes");
            return;
        }
        HttpUrl base;
        String playlist;
        try (Response response = explicit ? OpenDriveHttp.openExplicit(url, "", headers)
                : OpenDriveHttp.open(url, "", headers)) {
            org.junit.Assert.assertTrue(label + " playlist HTTP " + response.code(), response.isSuccessful());
            assertNotNull(response.body());
            base = response.request().url();
            byte[] body = response.body().byteStream().readNBytes(262145);
            org.junit.Assert.assertTrue(label + " playlist too large", body.length <= 262144);
            playlist = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        }
        String child = java.util.Arrays.stream(playlist.split("\\r?\\n"))
                .map(String::trim).filter(line -> !line.isBlank() && !line.startsWith("#"))
                .findFirst().orElse("");
        HttpUrl next = base.resolve(child);
        org.junit.Assert.assertTrue(label + " playlist has no HTTPS media reference",
                !child.isBlank() && next != null && next.isHttps());
        assertMediaSample(next.toString(), headers, label, explicit, depth + 1);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> disposableCopies(OpenDriveProvider provider) {
        if (provider instanceof AliPanOpenDriveProvider ali) return ali.createdCopyIds();
        String fieldName = switch (provider.id()) {
            case "quark" -> "savedFileIds";
            case "uc" -> "savedSourceFids";
            default -> "";
        };
        if (fieldName.isBlank()) return Set.of();
        try {
            java.lang.reflect.Field field = provider.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return new HashSet<>(((Map<String, String>) field.get(provider)).values());
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private static void cleanupNewDisposableCopies(OpenDriveProvider provider,
            Set<String> existingCopies) {
        Set<String> created = new HashSet<>(disposableCopies(provider));
        created.removeAll(existingCopies);
        if (created.isEmpty()) { reportFixtureCleanup("not-needed"); return; }
        String methodName = "quark".equals(provider.id()) ? "cleanup"
                : List.of("uc", "alipan").contains(provider.id()) ? "cleanupSaved" : "";
        if (methodName.isBlank()) return;
        try {
            java.lang.reflect.Method cleanup = provider.getClass()
                    .getDeclaredMethod(methodName, String.class);
            cleanup.setAccessible(true);
            for (String fid : created) cleanup.invoke(provider, fid);
            reportFixtureCleanup("passed");
        } catch (Exception error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            Log.i(TAG, "provider=" + provider.id() + ",fixtureCleanup=failed,type="
                    + cause.getClass().getSimpleName() + ",detail=" + safeFailure(cause));
            reportFixtureCleanup("failed");
            throw new AssertionError("Fixture cleanup failed", cause);
        }
    }

    private static void reportFixtureCleanup(String result) {
        android.os.Bundle status = new android.os.Bundle();
        status.putString("fixtureCleanup", result);
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }

    private static boolean auditShare(OpenDriveDeviceFixtures.ShareFixture fixture) throws Exception {
        OpenDriveEngine engine = new OpenDriveEngine();
        List<OpenDriveItem> items = engine.listShare(fixture.providerId(), fixture.url());
        if (items.isEmpty()) return false;
        Log.i(TAG, "provider=" + fixture.providerId() + ",shareVideos=" + items.size());

        OpenDriveProvider provider = engine.provider(fixture.providerId());
        if (!provider.authenticated()) {
            Log.i(TAG, "provider=" + fixture.providerId() + ",shareResolve=login_required");
            return true;
        }
        OpenDrivePlayback playback = engine.resolve(fixture.providerId(), items.get(0).id());
        assertNotNull(playback);
        Log.i(TAG, "provider=" + fixture.providerId() + ",shareResolve=authorized");
        return true;
    }

    private static void auditAccount(String providerId) throws Exception {
        OpenDriveEngine engine = new OpenDriveEngine();
        OpenDriveProvider provider = engine.provider(providerId);
        boolean authenticated = provider.authenticated();
        Log.i(TAG, "provider=" + providerId + ",authenticated=" + authenticated);
        Assume.assumeTrue("No host-owned credentials for " + providerId, authenticated);

        List<OpenDriveItem> items = engine.list(providerId, "root");
        assertNotNull(items);
        Log.i(TAG, "provider=" + providerId + ",rootItems=" + items.size());
    }

    private static OpenDriveItem firstVideo(OpenDriveEngine engine, String providerId,
            int maxFolders) throws Exception {
        ArrayDeque<String> folders = new ArrayDeque<>();
        folders.add("root");
        int visited = 0;
        while (!folders.isEmpty() && visited++ < maxFolders) {
            for (OpenDriveItem item : engine.list(providerId, folders.removeFirst())) {
                if (item.directory()) folders.addLast(item.id());
                else return item;
            }
        }
        return null;
    }
}
