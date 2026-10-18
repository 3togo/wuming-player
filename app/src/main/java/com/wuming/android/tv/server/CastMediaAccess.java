package com.wuming.android.tv.server;

import android.net.Uri;

import com.wuming.android.tv.ProductCore;
import com.wuming.android.tv.utils.Hex;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CastMediaAccess {

    private static final long TOKEN_LIFETIME_MS = 6 * 60 * 60 * 1000L;
    static final int MAX_RELAY_CHILDREN = 8192;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Map<String, Authorization> authorizations = new ConcurrentHashMap<>();
    private static final Map<String, Relay> relays = new ConcurrentHashMap<>();
    private static volatile long lastAccessAt;

    private CastMediaAccess() {
    }

    public static synchronized String authorize(String url) {
        if (ProductCore.getDeviceType() != 1 || url == null || url.isEmpty()) return url;
        Uri uri = Uri.parse(url);
        Uri local = Uri.parse(Server.get().getAddress());
        if (!sameServer(uri, local)) return url;
        String path = uri.getEncodedPath();
        if (path == null || path.isEmpty()) path = "/";
        // Relay links already carry their own expiring token. Wrapping them in
        // another /cast token obscures the media path from stricter renderers.
        if (path.startsWith("/cast-relay/")) return url;
        long now = System.currentTimeMillis();
        return uri.buildUpon().encodedPath(registerAuthorization(path, now)).build().toString();
    }

    /**
     * Exposes a remote live stream through the phone's HTTP server. DLNA
     * renderers cannot apply the private headers used by the in-app player, so
     * the relay applies them upstream and also gives HTTPS-only streams a plain
     * LAN HTTP URL.
     */
    public static synchronized String relay(String url, Map<String, String> headers) {
        return relay(url, headers, false);
    }

    /**
     * Relays a live-TV URL and advertises unknown/proxied sources as HLS. The
     * in-app player often exposes a local /proxy URL with no extension even
     * when its response is an HLS playlist; several TV renderers rely on the
     * .m3u8 suffix and otherwise try to extract it as a regular media file.
     */
    public static synchronized String relayLive(String url, Map<String, String> headers) {
        return relay(url, headers, true);
    }

    /**
     * Creates a credential-hiding relay for the paired desktop client. Unlike the DLNA helper,
     * this is intentionally available when the companion is a TV device. The returned URL has an
     * unguessable, expiring token and never exposes private upstream headers.
     */
    public static synchronized String relayDesktop(String url, Map<String, String> headers,
                                                    boolean assumeHls) {
        return relay(url, headers, assumeHls, true);
    }

    private static String relay(String url, Map<String, String> headers, boolean assumeHls) {
        return relay(url, headers, assumeHls, ProductCore.getDeviceType() == 1);
    }

    private static String relay(String url, Map<String, String> headers, boolean assumeHls,
                                boolean enabled) {
        if (!enabled || !isHttp(url)) return url;
        long now = System.currentTimeMillis();
        cleanupExpired(now);
        String token = registerRelay(new Relay(
                url, headers, now + TOKEN_LIFETIME_MS));
        lastAccessAt = now;
        return Server.get().getAddress() + "/cast-relay/" + token + streamSuffix(url, assumeHls);
    }

    public static synchronized String resolve(String path) {
        if (path != null && path.startsWith("/cast-relay/")) {
            return getRelay(path) == null ? null : path;
        }
        if (path == null || !path.startsWith("/cast/")) return null;
        String token = authorizationToken(path);
        Authorization authorization = token == null ? null : authorizations.get(token);
        if (authorization == null) return null;
        long now = System.currentTimeMillis();
        if (now >= authorization.expiresAt
                || !path.equals("/cast/" + token + authorization.targetPath)) {
            authorizations.remove(token);
            return null;
        }
        lastAccessAt = now;
        return authorization.targetPath;
    }

    public static synchronized void clear() {
        authorizations.clear();
        relays.clear();
        lastAccessAt = 0L;
    }

    static synchronized String registerAuthorization(String targetPath, long now) {
        cleanupExpired(now);
        String token = randomToken();
        authorizations.put(token,
                new Authorization(targetPath, now + TOKEN_LIFETIME_MS));
        lastAccessAt = now;
        return "/cast/" + token + targetPath;
    }

    /** Last creation or access of a cast relay, used to retire abandoned foreground sessions. */
    public static long lastAccessAt() {
        return lastAccessAt;
    }

    public static Relay getRelay(String path) {
        String token = relayToken(path);
        Relay relay = token == null ? null : relays.get(token);
        if (relay == null) return null;
        if (System.currentTimeMillis() >= relay.expiresAt()) {
            relays.remove(token);
            return null;
        }
        lastAccessAt = System.currentTimeMillis();
        return relay;
    }

    public static String getRelayTarget(String path) {
        Relay relay = getRelay(path);
        if (relay == null) return null;
        String marker = "/u/";
        int markerAt = path.indexOf(marker, "/cast-relay/".length());
        if (markerAt < 0) return relay.url();
        String encoded = path.substring(markerAt + marker.length());
        try {
            String target = new String(Base64.getUrlDecoder().decode(encoded),
                    StandardCharsets.UTF_8);
            return isHttp(target) && relay.allowsChild(target) ? target : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String childUrl(String relayPath, String targetUrl) {
        String token = relayToken(relayPath);
        if (token == null || !isHttp(targetUrl)) return targetUrl;
        Relay relay = getRelay(relayPath);
        if (relay == null || !relay.allowChild(targetUrl)) return targetUrl;
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                targetUrl.getBytes(StandardCharsets.UTF_8));
        return Server.get().getAddress() + "/cast-relay/" + token + "/u/" + encoded;
    }

    private static String relayToken(String path) {
        if (path == null || !path.startsWith("/cast-relay/")) return null;
        int start = "/cast-relay/".length();
        int end = path.indexOf('/', start);
        return end < 0 ? path.substring(start) : path.substring(start, end);
    }

    private static void cleanupExpired(long now) {
        authorizations.entrySet().removeIf(entry -> now >= entry.getValue().expiresAt);
        relays.entrySet().removeIf(entry -> now >= entry.getValue().expiresAt());
    }

    private static String authorizationToken(String path) {
        int start = "/cast/".length();
        int end = path.indexOf('/', start);
        return end <= start ? null : path.substring(start, end);
    }

    private static Map<String, String> copyHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return Collections.emptyMap();
        Map<String, String> copy = new HashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null
                    && !isInternalPlaybackHeader(entry.getKey())) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    static boolean isInternalPlaybackHeader(String name) {
        return name != null && name.regionMatches(true, 0,
                "X-OpenDrive-", 0, "X-OpenDrive-".length());
    }

    private static boolean isHttp(String url) {
        if (url == null) return false;
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            return uri.getHost() != null && ("http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static String streamSuffix(String url, boolean assumeHls) {
        if (url == null) return assumeHls ? "/stream.m3u8" : "/stream";
        // Bilibili uses /proxy?...&type=mpd, not a .mpd path. Preserve an MPD suffix on
        // the relay URL: the Coocaa receiver did not fetch media with the old /stream URL
        // and generic DIDL metadata. CastMediaAccessTest covers query-based MPD detection.
        if (com.wuming.tv.core.playback.DashRelayPolicy.isManifest(url)) return "/stream.mpd";
        int query = url.indexOf('?');
        int fragment = url.indexOf('#');
        int end = query < 0 ? url.length() : query;
        if (fragment >= 0) end = Math.min(end, fragment);
        String lower = url.substring(0, end).toLowerCase(Locale.US);
        if (lower.endsWith(".m3u8") || lower.endsWith(".m3u")) return "/stream.m3u8";
        if (lower.endsWith(".mpd")) return "/stream.mpd";
        if (lower.endsWith(".mp4")) return "/stream.mp4";
        if (lower.endsWith(".ts")) return "/stream.ts";
        return assumeHls ? "/stream.m3u8" : "/stream";
    }

    private static boolean sameServer(Uri first, Uri second) {
        if (first.getHost() == null || second.getHost() == null) return false;
        return first.getHost().equalsIgnoreCase(second.getHost()) && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(Uri uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String randomToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Hex.encodeLower(bytes);
    }

    static String registerRelay(Relay relay) {
        String token = randomToken();
        relays.put(token, relay);
        return token;
    }

    public static final class Relay {

        private final String url;
        private final Map<String, String> headers;
        private final long expiresAt;
        private final com.wuming.tv.core.playback.PlaybackTransportPlan playbackPlan;
        private final LinkedHashMap<String, Boolean> allowedChildren =
                new LinkedHashMap<>(16, 0.75f, true);

        Relay(String url, Map<String, String> headers, long expiresAt) {
            this.url = url;
            this.playbackPlan = com.wuming.tv.core.playback.PlaybackTransportPlan.parse(url, null, headers);
            this.headers = copyHeaders(playbackPlan.networkHeaders());
            this.expiresAt = expiresAt;
        }

        public com.wuming.tv.core.playback.PlaybackTransportPlan playbackPlan() {
            return playbackPlan;
        }

        public String url() {
            return url;
        }

        public Map<String, String> headers() {
            return headers;
        }

        public long expiresAt() {
            return expiresAt;
        }

        synchronized boolean allowChild(String target) {
            if (target == null) return false;
            allowedChildren.put(target, Boolean.TRUE);
            if (allowedChildren.size() > MAX_RELAY_CHILDREN) {
                String eldest = allowedChildren.keySet().iterator().next();
                allowedChildren.remove(eldest);
            }
            return true;
        }

        synchronized boolean allowsChild(String target) {
            return target != null && allowedChildren.get(target) != null;
        }

        synchronized int allowedChildCount() {
            return allowedChildren.size();
        }
    }

    private static final class Authorization {
        private final String targetPath;
        private final long expiresAt;

        private Authorization(String targetPath, long expiresAt) {
            this.targetPath = targetPath;
            this.expiresAt = expiresAt;
        }
    }
}
