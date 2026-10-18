package com.wuming.android.tv.utils;

import com.wuming.android.tv.App;
import com.wuming.android.tv.R;
import com.wuming.android.tv.bean.BeanUtils;
import com.wuming.android.tv.bean.GitHubProxy;
import com.github.catvod.utils.Prefers;
import com.wuming.tv.core.source.GitHubMirror;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manager for GitHub proxy list
 * Supports both hardcoded fallback and external dynamic list
 */
public class GitHubProxyList {

    private static final String TAG = "GitHubProxyList";
    private static final String PREF_PROXY_LIST = "github_proxy_list_json";
    private static final String PREF_PROXY_LIST_TIME = "github_proxy_list_time";
    private static final long CACHE_DURATION = 24 * 60 * 60 * 1000L; // 24 hours

    // External list URL (can be configured)
    private static final String DEFAULT_EXTERNAL_URL = "https://raw.githubusercontent.com/3togo/TV/refs/heads/release/github_proxies.json";

    // Default test URL
    private static final String DEFAULT_TEST_URL = "https://raw.githubusercontent.com/amule-project/amule/refs/heads/master/README.md";

    private static volatile GitHubProxyList instance;
    private final List<GitHubProxy> proxies = new CopyOnWriteArrayList<>();
    private String testUrl = DEFAULT_TEST_URL;
    private boolean initialized = false;

    private GitHubProxyList() {
    }

    public static GitHubProxyList get() {
        if (instance == null) {
            synchronized (GitHubProxyList.class) {
                if (instance == null) {
                    instance = new GitHubProxyList();
                }
            }
        }
        return instance;
    }

    /**
     * Initialize the proxy list
     * Loads cached list first, then fetches external list in background
     */
    public void init() {
        if (initialized) return;
        initialized = true;

        // Load cached list or fallback
        if (!loadCachedList()) {
            loadFallbackList();
        }

        // Fetch external list in background (non-blocking)
        fetchExternalListAsync();
    }

    /**
     * Get all proxies (thread-safe)
     */
    public List<GitHubProxy> getProxies() {
        if (proxies.isEmpty()) {
            loadFallbackList();
        }
        return Collections.unmodifiableList(new ArrayList<>(proxies));
    }

    /**
     * Get proxy by index
     */
    public GitHubProxy getProxy(int index) {
        List<GitHubProxy> list = getProxies();
        if (index >= 0 && index < list.size()) {
            return list.get(index);
        }
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * Get test URL
     */
    public String getTestUrl() {
        return testUrl;
    }

    /**
     * Get proxy names for UI
     */
    public String[] getProxyNames() {
        List<GitHubProxy> list = getProxies();
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            names[i] = list.get(i).getName();
        }
        return names;
    }

    /**
     * Get proxy hosts for URL building
     */
    public String[] getProxyHosts() {
        List<GitHubProxy> list = getProxies();
        String[] hosts = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            hosts[i] = list.get(i).getHost() != null ? list.get(i).getHost() : "";
        }
        return hosts;
    }

    /**
     * Where a mirror sits in the current list, or -1 when this list does not carry it. The choice is
     * remembered by host, never by position, because an external list may reorder underneath it.
     */
    public int indexOfHost(String host) {
        List<GitHubProxy> list = getProxies();
        for (int i = 0; i < list.size(); i++) {
            if (equalsHost(host, list.get(i).getHost())) return i;
        }
        return -1;
    }

    /** The display name a list carries for a mirror, or null when the list does not know it. */
    public String nameOf(String host) {
        int index = indexOfHost(host);
        return index < 0 ? null : getProxies().get(index).getName();
    }

    private static boolean equalsHost(String left, String right) {
        String a = left == null ? "" : left;
        String b = right == null ? "" : right;
        return a.equals(b);
    }

    /**
     * Load the built-in list: the mirrors {@link GitHubMirror} knows, labeled by the localized
     * {@code github_proxy_name} array, which must stay the same length as that list.
     */
    private void loadFallbackList() {
        String[] names = ResUtil.getStringArray(R.array.github_proxy_name);

        List<GitHubProxy> loaded = new ArrayList<>();
        for (int i = 0; i < GitHubMirror.HOSTS.size(); i++) {
            String host = GitHubMirror.HOSTS.get(i);
            loaded.add(new GitHubProxy(i < names.length ? names[i] : host, host));
        }

        proxies.clear();
        proxies.addAll(loaded);

        AppLog.d(TAG, "Loaded fallback proxy list: " + proxies.size() + " proxies");
    }

    /**
     * Load cached list from SharedPreferences
     */
    private boolean loadCachedList() {
        String json = Prefers.getString(PREF_PROXY_LIST);
        if (BeanUtils.isEmpty(json)) {
            return false;
        }

        try {
            GitHubProxy.List list = App.gson().fromJson(json, GitHubProxy.List.class);
            if (list != null && list.getProxies() != null && !list.getProxies().isEmpty()) {
                proxies.clear();
                proxies.addAll(list.getProxies());
                if (BeanUtils.isNotEmpty(list.getTestUrl())) {
                    testUrl = list.getTestUrl();
                }
                AppLog.d(TAG, "Loaded cached proxy list: " + proxies.size() + " proxies");
                return true;
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Failed to parse cached proxy list", e);
        }
        return false;
    }

    /**
     * Save list to cache
     */
    private void saveCachedList(GitHubProxy.List list) {
        try {
            String json = App.gson().toJson(list);
            Prefers.put(PREF_PROXY_LIST, json);
            Prefers.put(PREF_PROXY_LIST_TIME, System.currentTimeMillis());
        } catch (Exception e) {
            AppLog.e(TAG, "Failed to save proxy list cache", e);
        }
    }

    /**
     * Fetch external list asynchronously
     */
    private void fetchExternalListAsync() {
        // Check if cache is still valid
        long lastFetch = Prefers.getLong(PREF_PROXY_LIST_TIME, 0);
        if (System.currentTimeMillis() - lastFetch < CACHE_DURATION) {
            AppLog.d(TAG, "Proxy list cache is still valid, skipping fetch");
            return;
        }

        Task.execute(() -> {
            try {
                String json = fetchUrl(DEFAULT_EXTERNAL_URL);
                if (BeanUtils.isEmpty(json)) {
                    AppLog.w(TAG, "Empty response from external proxy list URL");
                    return;
                }

                GitHubProxy.List list = App.gson().fromJson(json, GitHubProxy.List.class);
                if (list != null && list.getProxies() != null && !list.getProxies().isEmpty()) {
                    // Update proxies
                    proxies.clear();
                    proxies.addAll(list.getProxies());

                    // Update test URL if provided
                    if (BeanUtils.isNotEmpty(list.getTestUrl())) {
                        testUrl = list.getTestUrl();
                    }

                    // Save to cache
                    saveCachedList(list);

                    AppLog.i(TAG, "Updated proxy list from external source: " + proxies.size()
                            + " proxies, version " + list.getVersion());
                }
            } catch (Exception e) {
                AppLog.e(TAG, "Failed to fetch external proxy list", e);
            }
        });
    }

    /**
     * Simple URL fetch
     */
    private String fetchUrl(String url) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");

            int code = conn.getResponseCode();
            if (code == 200) {
                try (java.io.InputStream in = conn.getInputStream();
                     java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    int len;
                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                    }
                    return out.toString("UTF-8");
                }
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Failed to fetch proxy list URL", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    /**
     * Force refresh from external source
     */
    public void forceRefresh() {
        Prefers.put(PREF_PROXY_LIST_TIME, 0);
        fetchExternalListAsync();
    }

    /**
     * Reset to fallback list
     */
    public void reset() {
        Prefers.remove(PREF_PROXY_LIST);
        Prefers.remove(PREF_PROXY_LIST_TIME);
        loadFallbackList();
    }
}