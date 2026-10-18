package com.wuming.android.tv.utils;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.wuming.android.tv.App;
import com.wuming.android.tv.R;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Response;
import okhttp3.ResponseBody;

public class DepotManager {

    private static final String TAG = "DepotManager";

    /**
     * READMEs scraped for VOD/live links. MUST stay in sync with README_URLS in update_depot.py and with
     * the accept/reject rules below, otherwise the shipped asset and the device copy drift apart.
     */
    private static final String[] README_URLS = {
            "https://raw.githubusercontent.com/youhunwl/TVAPP/main/README.md",
            "https://raw.githubusercontent.com/ngo5/IPTV/main/README.md",
            "https://raw.githubusercontent.com/Zhou-Li-Bin/Tvbox-QingNing/main/README.md",
    };

    private static final Pattern LINK_PATTERN = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
    private static final Pattern URL_PATTERN = Pattern.compile("(https?://[^\\s\\)\\]\\>\\#\\|]+)");

    private static final int TIMEOUT = 15000;
    private static final int VALIDATE_TIMEOUT = 8000;
    private static final int VALIDATE_THREADS = 8;

    /**
     * Sources that used to be hardcoded in isValidUrl(). They are migrated into the entry itself
     * (noCheck=true) the first time the entry is checked, so the list stops depending on code.
     */
    private static final String[] LEGACY_NO_CHECK = {"饭太硬", "fty.888484", "我不是.摸鱼儿"};

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    public static String[] getReadmeUrls() {
        return README_URLS.clone();
    }

    public interface Callback {
        void onResult(String message);

        default void onProgress(int done, int total) {
        }
    }

    public static class DepotInfo {
        public final int vodCount;
        public final int liveCount;

        public DepotInfo(int vodCount, int liveCount) {
            this.vodCount = vodCount;
            this.liveCount = liveCount;
        }
    }

    public static class SourceInfo {
        public final String name;
        public final String url;
        public final String type;
        public final long lastVisit;
        public final int count;
        public final long ping;
        public final int failCount;

        public SourceInfo(String name, String url, String type, long lastVisit, int count, long ping, int failCount) {
            this.name = name;
            this.url = url;
            this.type = type;
            this.lastVisit = lastVisit;
            this.count = count;
            this.ping = ping;
            this.failCount = failCount;
        }
    }

    public static DepotInfo getDepotInfo(Context context) {
        try {
            JSONObject obj = loadDepot(context.getApplicationContext());
            JSONArray vodArray = obj.optJSONArray("vod");
            JSONArray liveArray = obj.optJSONArray("live");
            int vodCount = vodArray != null ? vodArray.length() : 0;
            int liveCount = liveArray != null ? liveArray.length() : 0;
            return new DepotInfo(vodCount, liveCount);
        } catch (Exception ignored) {
            return new DepotInfo(0, 0);
        }
    }

    public static void updateFromGitHub(Context context, Callback callback) {
        Context appContext = context.getApplicationContext();
        final AtomicReference<Callback> callbackRef = new AtomicReference<>(callback);
        EXECUTOR.execute(() -> {
            Callback cb = callbackRef.get();
            try {
                okhttp3.OkHttpClient noProxyClient = OkHttp.client(TIMEOUT).newBuilder()
                    .proxySelector(new java.net.ProxySelector() {
                        @Override
                        public List<java.net.Proxy> select(URI uri) {
                            return Collections.singletonList(java.net.Proxy.NO_PROXY);
                        }

                        @Override
                        public void connectFailed(URI uri, java.net.SocketAddress sa, IOException ioe) {
                        }
                    })
                    .build();

                List<Source> vodSources = new ArrayList<>();
                List<Source> liveSources = new ArrayList<>();
                int fetched = 0;

                for (String readmeUrl : README_URLS) {
                    String readme = fetch(noProxyClient, readmeUrl);
                    if (readme == null) continue;
                    ++fetched;
                    parseReadme(readme, vodSources, liveSources);
                }

                if (fetched == 0) throw new IOException("No README could be downloaded");

                    JSONObject depot = loadDepot(appContext);
                    JSONArray existingVod = depot.optJSONArray("vod");
                    JSONArray existingLive = depot.optJSONArray("live");
                    if (existingVod == null) existingVod = new JSONArray();
                    if (existingLive == null) existingLive = new JSONArray();

                    int newVod = mergeSources(existingVod, vodSources);
                    int newLive = mergeSources(existingLive, liveSources);

                    depot.put("vod", existingVod);
                    depot.put("live", existingLive);
                    saveDepot(appContext, depot);

                    String message = appContext.getString(R.string.setting_depot_updated, existingVod.length(), existingLive.length());
                    if (cb != null) cb.onResult(message);
            } catch (Exception e) {
                if (cb != null) cb.onResult("Update failed: " + e.getMessage());
            } finally {
                callbackRef.set(null);
            }
        });
    }

    public static void validateSources(Context context, Callback callback) {
        Context appContext = context.getApplicationContext();
        final AtomicReference<Callback> callbackRef = new AtomicReference<>(callback);
        EXECUTOR.execute(() -> {
            Callback cb = callbackRef.get();
            try {
                JSONObject depot = loadDepot(appContext);

                JSONArray vodArray = depot.optJSONArray("vod");
                if (vodArray == null) vodArray = new JSONArray();
                JSONArray liveArray = depot.optJSONArray("live");
                if (liveArray == null) liveArray = new JSONArray();

                AtomicInteger done = new AtomicInteger();
                int total = vodArray.length() + liveArray.length();
                ValidateResult vod = validateArray(vodArray, done, total, cb);
                ValidateResult live = validateArray(liveArray, done, total, cb);

                depot.put("vod", vod.array);
                depot.put("live", live.array);
                saveDepot(appContext, depot);

                int passed = vod.passed + live.passed;
                int failed = vod.failed + live.failed;
                String message = appContext.getString(R.string.setting_depot_validated, passed, failed)
                        + (vod.pruned + live.pruned > 0 ? " (-" + (vod.pruned + live.pruned) + ")" : "");
                if (cb != null) cb.onResult(message);
            } catch (Exception e) {
                if (cb != null) cb.onResult("Validation failed: " + e.getMessage());
            } finally {
                callbackRef.set(null);
            }
        });
    }

    private static class Source {
        final String url;
        final String name;

        Source(String url, String name) {
            this.url = url;
            this.name = name;
        }
    }

    private static void parseReadme(String readme, List<Source> vodSources, List<Source> liveSources) {
        String section = null;

        for (String line : readme.split("\n")) {
            // Only markdown headers switch section. Matching keywords on any line made a VOD entry
            // whose URL merely contained "TV" flip every following link into the live bucket.
            String header = line.trim();
            if (header.startsWith("#")) {
                if (containsAny(header, "点播", "VOD", "影视")) section = "vod";
                else if (containsAny(header, "直播", "Live", "TV", "电视")) section = "live";
                else if (containsAny(header, "EPG", "推荐", "软件", "壁纸", "加速", "缩短", "声明", "免责", "赞助", "写在前面", "资源获取", "更新记录", "项目需知")) section = "skip";
                else section = null;
            }

            Matcher link = LINK_PATTERN.matcher(line);
            while (link.find()) add(link.group(2), link.group(1), section, vodSources, liveSources);

            Matcher plain = URL_PATTERN.matcher(line);
            while (plain.find()) add(plain.group(1), null, section, vodSources, liveSources);
        }
    }

    private static void add(String raw, String label, String section, List<Source> vodSources, List<Source> liveSources) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty() || "skip".equals(section) || isMirrored(url) || !isSourceUrl(url)) return;
        // Live wins over the section, matching update_depot.py: an .m3u under a VOD heading is still live.
        boolean live = isLiveUrl(url) || "live".equals(section);
        (live ? liveSources : vodSources).add(new Source(url, nameOf(url, label)));
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }

    private static String fetch(okhttp3.OkHttpClient client, String url) {
        try (Response res = OkHttp.newCall(client, url).execute()) {
            ResponseBody body = res.body();
            return body == null ? null : body.string();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Accelerator/mirror links (ghproxy.net/https://..., ghp.ci/https://...) embed the canonical URL.
     * They duplicate entries we already store and send every user request through an unrelated third
     * party, so they are dropped on ingest.
     */
    private static boolean isMirrored(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) return false;
        return url.indexOf("http://", scheme + 1) >= 0 || url.indexOf("https://", scheme + 1) >= 0;
    }

    private static boolean isLiveUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.endsWith(".m3u") || lower.endsWith(".m3u8");
    }

    /** Badges, screenshots and other README assets are never config endpoints. */
    private static final String[] ASSET_EXTENSIONS = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".bmp", ".css", ".js", ".html", ".woff", ".woff2", ".ttf", ".eot", ".mp4", ".apk"};

    private static boolean isSourceUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        for (String extension : ASSET_EXTENSIONS) if (lower.endsWith(extension)) return false;
        if (isLiveUrl(lower) || lower.endsWith(".json") || lower.endsWith(".txt")) return true;
        // A bare "tvbox" substring anywhere is too weak: every badge and self-link of a repo whose name
        // contains "Tvbox" (img.shields.io/.../Tvbox-QingNing, github.com/.../Tvbox-QingNing/pulls) matches.
        String segment = lastSegment(url);
        return segment.contains("tvbox") && segment.indexOf('.') > 0;
    }

    private static String lastSegment(String url) {
        String path = url.replaceAll("https?://", "");
        int slash = path.indexOf('/');
        String tail = slash < 0 ? "" : path.substring(slash + 1);
        int query = tail.indexOf('?');
        if (query >= 0) tail = tail.substring(0, query);
        tail = tail.replaceAll("/+$", "");
        int last = tail.lastIndexOf('/');
        return (last < 0 ? tail : tail.substring(last + 1)).toLowerCase(Locale.ROOT);
    }

    private static String nameOf(String url, String label) {
        if (label != null && !label.trim().isEmpty()) return label.trim();
        String path = url.replaceAll("https?://", "");
        int slash = path.indexOf('/');
        String host = slash < 0 ? path : path.substring(0, slash);
        int last = path.lastIndexOf('/');
        String file = last < 0 ? "" : path.substring(last + 1);
        int query = file.indexOf('?');
        if (query >= 0) file = file.substring(0, query);
        int dot = file.lastIndexOf('.');
        if (dot > 0) file = file.substring(0, dot);
        return file.isEmpty() ? host : file;
    }

    public static JSONObject loadDepot(Context context) throws Exception {
        java.io.File file = getDepotFile(context.getApplicationContext());

        if (file.exists()) {
            try (InputStream is = new java.io.FileInputStream(file)) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] data = new byte[1024];
                int nRead;
                while ((nRead = is.read(data, 0, data.length)) != -1) {
                    buffer.write(data, 0, nRead);
                }
                return new JSONObject(buffer.toString(StandardCharsets.UTF_8.name()));
            }
        }

        try {
            AssetManager assets = context.getApplicationContext().getAssets();
            try (InputStream is = assets.open("depot.json")) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] data = new byte[1024];
                int nRead;
                while ((nRead = is.read(data, 0, data.length)) != -1) {
                    buffer.write(data, 0, nRead);
                }
                return new JSONObject(buffer.toString(StandardCharsets.UTF_8.name()));
            }
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /**
     * Write via tmp + rename. A truncating write leaves half a JSON document if the process dies
     * mid-flush, and loadDepot() then throws for good — the dialog silently renders empty.
     */
    private static void saveDepot(Context context, JSONObject depot) throws Exception {
        File file = getDepotFile(context);
        File dir = file.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp); Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                writer.write(depot.toString(2));
                writer.flush();
                out.getFD().sync();
            }
            if (!tmp.renameTo(file)) throw new IOException("Cannot replace " + file);
        } finally {
            if (tmp.exists() && !tmp.delete()) Log.w(TAG, "Cannot delete " + tmp);
        }
    }

    /**
     * Test/debug only: replace depot.json with a single known VOD source so the "Use" flow can be
     * exercised deterministically. Uses the app context (App.get()) so the file lands where the
     * app actually reads it -- an instrumentation test cannot rely on Path.tv() because
     * Init.context() resolves to the test package there.
     */
    public static void writeTestDepot(String url) throws Exception {
        JSONObject depot = new JSONObject();
        JSONArray vod = new JSONArray();
        JSONObject item = new JSONObject();
        item.put("name", "SeedVod");
        item.put("url", url);
        item.put("type", "VOD");
        item.put("count", 1);
        item.put("ping", 0);
        item.put("failCount", 0);
        vod.put(item);
        depot.put("vod", vod);
        saveDepot(App.get(), depot);
    }

    /**
     * Single writer for usage stats. SourceTracker used to open and truncate the same file from
     * whatever thread it was called on, racing the update/validate path.
     */
    public static void trackUsage(String url, String name, String type, long pingTime) {
        EXECUTOR.execute(() -> {
            try {
                Context context = App.get();
                JSONObject depot = loadDepot(context);
                String key = "VOD".equalsIgnoreCase(type) ? "vod" : "live";
                JSONArray array = depot.optJSONArray(key);
                if (array == null) array = new JSONArray();

                boolean found = false;
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    if (!url.equals(item.optString("url"))) continue;
                    item.put("lastVisit", System.currentTimeMillis());
                    item.put("count", item.optInt("count", 0) + 1);
                    if (pingTime > 0) item.put("ping", pingTime);
                    found = true;
                    break;
                }

                if (!found && name != null && !name.isEmpty()) {
                    JSONObject item = new JSONObject();
                    item.put("url", url);
                    item.put("name", name);
                    item.put("lastVisit", System.currentTimeMillis());
                    item.put("count", 1);
                    if (pingTime > 0) item.put("ping", pingTime);
                    array.put(item);
                }

                depot.put(key, array);
                saveDepot(context, depot);
            } catch (Exception e) {
                Log.e(TAG, "Error tracking source", e);
            }
        });
    }

    private static java.io.File getDepotFile(Context context) {
        java.io.File internalFile = new java.io.File(Path.tv(), "depot.json");
        if (!internalFile.exists()) {
            java.io.File oldExternalFile = new java.io.File(android.os.Environment.getExternalStorageDirectory(), "TV/depot.json");
            if (oldExternalFile.exists()) {
                Path.copy(oldExternalFile, internalFile);
            } else {
                java.io.File oldInternalFile = new java.io.File(context.getFilesDir(), "depot.json");
                if (oldInternalFile.exists()) {
                    Path.copy(oldInternalFile, internalFile);
                }
            }
        }
        return internalFile;
    }

    private static int mergeSources(JSONArray existing, List<Source> newSources) throws Exception {
        java.util.Set<String> existingUrls = new java.util.HashSet<>();
        for (int i = 0; i < existing.length(); i++) {
            JSONObject item = existing.getJSONObject(i);
            existingUrls.add(item.getString("url"));
        }

        int added = 0;
        for (Source source : newSources) {
            if (!existingUrls.contains(source.url)) {
                JSONObject item = new JSONObject();
                item.put("url", source.url);
                item.put("name", source.name);
                existing.put(item);
                existingUrls.add(source.url);
                added++;
            }
        }
        return added;
    }

    private static class ValidateResult {
        final JSONArray array = new JSONArray();
        int passed;
        int failed;
        int pruned;
    }

    private static ValidateResult validateArray(JSONArray array, AtomicInteger done, int total, Callback cb) throws Exception {
        List<JSONObject> items = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) items.add(array.getJSONObject(i));

        boolean[] skip = new boolean[items.size()];
        List<Future<Boolean>> futures = new ArrayList<>(items.size());
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(VALIDATE_THREADS, items.size())));

        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.get(i);
            String url = item.optString("url", "");
            if (url.isEmpty() || resolveNoCheck(item, url)) {
                skip[i] = true;
                futures.add(null);
                continue;
            }
            final String target = url;
            futures.add(pool.submit(() -> isValidUrl(target)));
        }
        pool.shutdown();

        ValidateResult result = new ValidateResult();
        try {
            for (int i = 0; i < items.size(); i++) {
                JSONObject item = items.get(i);
                boolean ok;
                if (skip[i]) {
                    ok = true;
                } else {
                    try {
                        ok = futures.get(i).get();
                    } catch (Exception e) {
                        ok = false;
                    }
                }

                int fails = DepotPrune.nextFailCount(item.optInt("failCount", 0), ok);
                item.put("failCount", fails);
                if (ok) {
                    result.passed++;
                } else {
                    result.failed++;
                    if (!DepotPrune.keep(fails)) {
                        result.pruned++;
                        result.failed--;
                        continue;
                    }
                }
                item.put("lastCheck", System.currentTimeMillis());
                result.array.put(item);
                if (cb != null) cb.onProgress(done.incrementAndGet(), total);
            }
        } finally {
            pool.shutdownNow();
        }
        return result;
    }

    /**
     * Some sources can never be probed reliably (IDN hosts, endpoints that block datacenter IPs).
     * The decision is persisted as noCheck=true so it stops being a code-level special case.
     */
    private static boolean resolveNoCheck(JSONObject item, String url) {
        if (item.optBoolean("noCheck", false)) return true;
        boolean skip = containsAny(url, LEGACY_NO_CHECK) || !isAsciiHost(url);
        if (skip) {
            try {
                item.put("noCheck", true);
            } catch (Exception ignored) {
                // Persisting the flag is best-effort; skipping the probe still applies this run.
            }
        }
        return skip;
    }

    private static boolean isAsciiHost(String url) {
        int scheme = url.indexOf("://");
        String rest = scheme < 0 ? url : url.substring(scheme + 3);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        String host = rest.substring(0, end);
        int at = host.lastIndexOf('@');
        if (at >= 0) host = host.substring(at + 1);
        for (int i = 0; i < host.length(); i++) if (host.charAt(i) > 0x7F) return false;
        return true;
    }

    private static boolean isValidUrl(String url) {
        try {
            try (Response res = OkHttp.newCall(OkHttp.client(VALIDATE_TIMEOUT), url).execute()) {
                ResponseBody body = res.body();
                if (body == null) return false;
                String content = body.string();

                if (content.startsWith("<!DOCTYPE") || content.startsWith("<html")) {
                    return false;
                }

                try {
                    new JSONObject(content);
                    return true;
                } catch (Exception e1) {
                    return UrlUtil.isHttp(content) || content.length() > 100;
                }
            }
        } catch (Exception e) {
            try {
                okhttp3.OkHttpClient noProxyClient = OkHttp.client(VALIDATE_TIMEOUT).newBuilder()
                    .proxySelector(new java.net.ProxySelector() {
                        @Override
                        public List<java.net.Proxy> select(URI uri) {
                            return Collections.singletonList(java.net.Proxy.NO_PROXY);
                        }

                        @Override
                        public void connectFailed(URI uri, java.net.SocketAddress sa, IOException ioe) {
                        }
                    })
                    .build();

                try (Response res = OkHttp.newCall(noProxyClient, url).execute()) {
                    ResponseBody body = res.body();
                    if (body == null) return false;
                    String content = body.string();

                    if (content.startsWith("<!DOCTYPE") || content.startsWith("<html")) {
                        return false;
                    }

                    try {
                        new JSONObject(content);
                        return true;
                    } catch (Exception e1) {
                        return UrlUtil.isHttp(content) || content.length() > 100;
                    }
                }
            } catch (Exception e2) {
                return false;
            }
        }
    }
}