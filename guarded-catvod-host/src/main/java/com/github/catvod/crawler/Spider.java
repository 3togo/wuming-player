package com.github.catvod.crawler;

import android.content.Context;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

/** Stable CatVod provider contract supplied by the isolated desktop compatibility host. */
// JAVA-RETENTION: catvod-spider-api — compatibility hold; see docs/java-retention.json.
public abstract class Spider {
    public String siteKey;

    private static final MemoryCookieJar COOKIE_JAR = new MemoryCookieJar();
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .cookieJar(COOKIE_JAR)
            .build();

    public static OkHttpClient client() {
        return CLIENT;
    }

    public static OkHttpClient.Builder newClientBuilder() {
        return CLIENT.newBuilder();
    }

    public static Dns safeDns() {
        return Dns.SYSTEM;
    }

    /** Mirrors Leanback's effective Cookie header without exposing unrelated provider cookies. */
    public static String cookieHeader(String url) {
        try {
            HttpUrl target = HttpUrl.get(url);
            StringBuilder value = new StringBuilder();
            for (Cookie cookie : COOKIE_JAR.loadForRequest(target)) {
                if (value.length() > 0) value.append("; ");
                value.append(cookie.name()).append('=').append(cookie.value());
            }
            return value.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    public void init(Context context) throws Exception {
    }

    public void init(Context context, String extend) throws Exception {
        init(context);
    }

    public String homeContent(boolean filter) throws Exception { return ""; }
    public String homeVideoContent() throws Exception { return ""; }
    public String categoryContent(String tid, String page, boolean filter, HashMap<String, String> extend) throws Exception { return ""; }
    public String detailContent(List<String> ids) throws Exception { return ""; }
    public String searchContent(String key, boolean quick) throws Exception { return ""; }
    public String searchContent(String key, boolean quick, String page) throws Exception { return searchContent(key, quick); }
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception { return ""; }
    public String liveContent(String url) throws Exception { return ""; }
    public boolean manualVideoCheck() throws Exception { return false; }
    public boolean isVideoFormat(String url) throws Exception { return false; }
    public Object[] proxy(Map<String, String> params) throws Exception { return null; }
    public String action(String action) throws Exception { return null; }
    public void destroy() { }

    private static final class MemoryCookieJar implements CookieJar {
        private final CopyOnWriteArrayList<Cookie> cookies = new CopyOnWriteArrayList<>();

        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> received) {
            long now = System.currentTimeMillis();
            for (Cookie replacement : received) {
                cookies.removeIf(existing -> sameIdentity(existing, replacement)
                        || existing.expiresAt() <= now);
                if (replacement.expiresAt() > now) cookies.add(replacement);
            }
        }

        @Override
        public List<Cookie> loadForRequest(HttpUrl url) {
            long now = System.currentTimeMillis();
            cookies.removeIf(cookie -> cookie.expiresAt() <= now);
            return cookies.stream().filter(cookie -> cookie.matches(url)).toList();
        }

        private static boolean sameIdentity(Cookie first, Cookie second) {
            return first.name().equals(second.name())
                    && first.domain().equals(second.domain())
                    && first.path().equals(second.path());
        }
    }
}
