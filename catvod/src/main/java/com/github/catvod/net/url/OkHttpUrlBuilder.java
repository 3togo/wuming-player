package com.github.catvod.net.url;

import android.util.Log;

import androidx.collection.ArrayMap;

import java.net.IDN;
import java.util.LinkedHashMap;
import java.util.Map;

import okhttp3.HttpUrl;

/** HttpUrl construction with IDN/punycode handling for internationalized domain names. */
public final class OkHttpUrlBuilder {

    private static final int IDN_CACHE_MAX = 256;
    private static final Map<String, String> idnCache = new LinkedHashMap<String, String>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > IDN_CACHE_MAX;
        }
    };

    private OkHttpUrlBuilder() {
    }

    public static HttpUrl buildHttpUrl(String url) {
        if (url == null || url.isEmpty()) return null;
        if (!url.startsWith("http")) {
            return HttpUrl.parse(url);
        }
        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost();
            if (host == null) return HttpUrl.parse(url);
            String ascii;
            if (host.contains(":")) {
                ascii = "[" + host + "]";
                Log.i("OkHttp", "IPv6 host detected in buildHttpUrl: [" + host + "] from URL: " + url);
            } else {
                synchronized (idnCache) {
                    ascii = idnCache.computeIfAbsent(host, h -> IDN.toASCII(h));
                }
            }
            HttpUrl.Builder builder = new HttpUrl.Builder()
                    .scheme(uri.getScheme() != null ? uri.getScheme() : "http")
                    .host(ascii);
            if (uri.getPort() != -1) builder.port(uri.getPort());
            if (uri.getPath() != null && !uri.getPath().isEmpty()) builder.encodedPath(uri.getPath());
            if (uri.getQuery() != null) builder.encodedQuery(uri.getQuery());
            if (uri.getFragment() != null) builder.fragment(uri.getFragment());
            return builder.build();
        } catch (Exception e) {
            return HttpUrl.parse(url);
        }
    }

    public static HttpUrl buildUrl(HttpUrl base, ArrayMap<String, String> params) {
        HttpUrl.Builder builder = base.newBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) builder.addQueryParameter(entry.getKey(), entry.getValue());
        return builder.build();
    }

    public static void clearCache() {
        synchronized (idnCache) { idnCache.clear(); }
    }
}
