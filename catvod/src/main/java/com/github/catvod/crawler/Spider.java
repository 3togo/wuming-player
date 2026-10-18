package com.github.catvod.crawler;

import android.content.Context;

import com.github.catvod.net.OkHttp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Dns;
import okhttp3.OkHttpClient;

// JAVA-RETENTION: catvod-spider-api — compatibility hold; see docs/java-retention.json.
public abstract class Spider {

    public String siteKey;

    public static Dns safeDns() {
        return OkHttp.dns();
    }

    public static OkHttpClient client() {
        return OkHttp.client(30000); // 30 second timeout
    }

    /**
     * Returns a new {@link OkHttpClient.Builder} pre-configured with the app's shared
     * CookieJar, interceptors, DNS, SSL, and proxy settings.
     * Spiders that need a customized OkHttpClient (e.g., different timeouts) MUST use
     * this instead of {@code new OkHttpClient.Builder()} to ensure cookies from the
     * PersistentCookieJar (synced from WebView login) are included in requests.
     *
     * Example:
     * {@code OkHttpClient myClient = Spider.newClientBuilder().readTimeout(60, TimeUnit.SECONDS).build();}
     */
    public static OkHttpClient.Builder newClientBuilder() {
        return OkHttp.newClientBuilder();
    }

    /**
     * Returns the Cookie header value for the given URL, read from the shared PersistentCookieJar.
     * This is useful for spiders that use their own HTTP client (not OkHttp) and need to
     * manually include session cookies in request headers.
     *
     * For example, a spider using java.net.HttpURLConnection can do:
     * {@code connection.setRequestProperty("Cookie", Spider.cookieHeader(url));}
     *
     * @param url the request URL
     * @return the Cookie header value (e.g., "__pus=xxx; __puus=yyy"), or empty string if none
     */
    public static String cookieHeader(String url) {
        return OkHttp.cookieHeader(url);
    }

    public void init(Context context) throws Exception {
    }

    public void init(Context context, String extend) throws Exception {
        init(context);
    }

    public String homeContent(boolean filter) throws Exception {
        return "";
    }

    public String homeVideoContent() throws Exception {
        return "";
    }

    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        return "";
    }

    public String detailContent(List<String> ids) throws Exception {
        return "";
    }

    public String searchContent(String key, boolean quick) throws Exception {
        return "";
    }

    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return "";
    }

    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        return "";
    }

    public String liveContent(String url) throws Exception {
        return "";
    }

    public boolean manualVideoCheck() throws Exception {
        return false;
    }

    public boolean isVideoFormat(String url) throws Exception {
        return false;
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        return null;
    }

    public String action(String action) throws Exception {
        return null;
    }

    public void destroy() {
    }
}
