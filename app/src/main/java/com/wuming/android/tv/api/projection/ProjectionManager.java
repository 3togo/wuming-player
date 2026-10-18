package com.wuming.android.tv.api.projection;


import com.wuming.android.tv.App;
import com.wuming.android.tv.R;
import com.wuming.android.tv.api.SiteApi;
import com.wuming.tv.core.playback.CastQueue;
import com.wuming.android.tv.bean.CastVideo;
import com.wuming.android.tv.bean.Device;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.dlna.DlnaCastBackend;
import com.wuming.android.tv.server.CastMediaAccess;
import com.github.catvod.Proxy;
import com.wuming.android.tv.utils.AppLog;
import com.wuming.android.tv.utils.Notify;
import com.wuming.android.tv.utils.Task;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

import okhttp3.HttpUrl;

public class ProjectionManager {

    private static final String TAG = "ProjectionManager";

    private static ProjectionManager instance;

    private final CastBackend castBackend;
    private final CastBackend chromecastBackend;
    private final Object lock = new Object();
    private final Set<CastSessionListener> sessionListeners = new CopyOnWriteArraySet<>();
    private WeakReference<DiscoveryCallback> discoveryCallbackRef;
    private long discoveryGeneration;
    private Device connectedDevice;
    private CastBackend.Session currentCast;
    private CastSession castSession;
    private CastSessionState sessionState = CastSessionState.idle();
    private long castGeneration;
    private String remoteCastSessionId;
    private Runnable remoteCastDisconnect;
    private boolean remoteCastRelayDependent;

    /** Relay playlist URL of the most recent HLS cast, for one-shot aggregated fallback. */
    private String lastHlsCastUrl;
    private CastVideo lastHlsVideo;
    private boolean aggregatedFallbackUsed;
    /** True while the current cast is an aggregated progressive TS stream. */
    private boolean aggregatedCastInFlight;
    /** True once the current cast session has used the aggregated TS compatibility stream. */
    private boolean sessionCompatCast;

    private ProjectionManager() {
        this(new DlnaCastBackend(), null);
    }

    ProjectionManager(CastBackend castBackend) {
        this(castBackend, null);
    }

    ProjectionManager(CastBackend castBackend, CastBackend chromecastBackend) {
        this.castBackend = Objects.requireNonNull(castBackend);
        this.chromecastBackend = chromecastBackend;
    }

    public static synchronized ProjectionManager get() {
        if (instance == null) instance = new ProjectionManager();
        return instance;
    }

    public synchronized void discover(DiscoveryCallback callback) {
        if (callback == null) return;
        AppLog.i(TAG, "discover: starting discovery");
        long generation = ++discoveryGeneration;
        discoveryCallbackRef = new WeakReference<>(callback);
        CastBackend.DiscoveryListener listener = new CastBackend.DiscoveryListener() {
            @Override
            public void onDeviceAdded(Device device) {
                AppLog.i(TAG, "onDeviceAdded callback: " + device.getName());
                dispatchDiscovery(generation, device, true);
            }

            @Override
            public void onDeviceRemoved(Device device) {
                AppLog.i(TAG, "onDeviceRemoved callback: " + device.getName());
                dispatchDiscovery(generation, device, false);
            }
        };
        castBackend.startDiscovery(App.get(), listener);
        if (chromecastBackend != null) {
            chromecastBackend.startDiscovery(App.get(), listener);
        }
    }

    public synchronized void stopDiscovery() {
        AppLog.i(TAG, "stopDiscovery");
        discoveryGeneration++;
        castBackend.stopDiscovery();
        if (chromecastBackend != null) {
            chromecastBackend.stopDiscovery();
        }
        if (discoveryCallbackRef != null) discoveryCallbackRef.clear();
        discoveryCallbackRef = null;
    }

    private void dispatchDiscovery(long generation, Device device, boolean found) {
        App.post(() -> {
            DiscoveryCallback callback;
            synchronized (ProjectionManager.this) {
                if (generation != discoveryGeneration || discoveryCallbackRef == null) return;
                callback = discoveryCallbackRef.get();
            }
            if (callback == null) return;
            if (found) callback.onDeviceFound(device);
            else callback.onDeviceLost(device);
        });
    }

    public void cast(Device device, CastVideo video, Runnable onCasted) {
        cast(device, video, null, onCasted);
    }

    public void cast(Device device, CastVideo video, CastQueue queue, Runnable onCasted) {
        disconnect();
        long generation;
        synchronized (lock) {
            connectedDevice = device;
            castSession = queue != null && queue.hasCurrent() ? new CastSession(queue) : null;
            generation = ++castGeneration;
            updateSessionStateLocked(new CastSessionState(CastState.RESOLVING,
                    castSession == null ? -1 : castSession.index, video.name(), device.getName()));
        }
        com.wuming.tv.core.playback.BilibiliCastPlayback.Stream progressive = BilibiliCastResolver.resolve(video);
        synchronized (lock) {
            if (generation != castGeneration || connectedDevice != device) return;
            startCastLocked(video, progressive, onCasted);
        }
    }

    public void disconnect() {
        Runnable disconnectRemote;
        synchronized (lock) {
            castGeneration++;
            if (currentCast != null) currentCast.stop();
            releaseCurrentCastLocked();
            disconnectRemote = remoteCastDisconnect;
            remoteCastSessionId = null;
            remoteCastDisconnect = null;
            remoteCastRelayDependent = false;
            CastMediaAccess.clear();
            connectedDevice = null;
            castSession = null;
            sessionCompatCast = false;
            updateSessionStateLocked(CastSessionState.idle());
        }
        if (disconnectRemote != null) {
            try {
                disconnectRemote.run();
            } catch (RuntimeException e) {
                AppLog.w(TAG, "Unable to stop remote app cast", e);
            }
        }
    }

    /** Sends a seek command to the TV during cast. No-op if not casting. */
    public void seek(long positionMs) {
        synchronized (lock) {
            if (currentCast != null) currentCast.seek(positionMs);
        }
    }

    /** Returns the TV's current playback position in ms, or -1 if not casting. */
    public long getCastPosition() {
        synchronized (lock) {
            if (currentCast == null) return -1;
            return currentCast.getCurrentPositionMs();
        }
    }

    /** Starts a paired app-to-app session before its control request leaves the phone. */
    public RemoteCastHandle beginRemoteCast(Device device, String episodeName,
                                            boolean relayDependent,
                                            Consumer<String> disconnectAction) {
        disconnect();
        synchronized (lock) {
            String sessionId = UUID.randomUUID().toString();
            long generation = ++castGeneration;
            connectedDevice = device;
            castSession = null;
            remoteCastSessionId = sessionId;
            remoteCastRelayDependent = relayDependent;
            remoteCastDisconnect = disconnectAction == null
                    ? null : () -> disconnectAction.accept(sessionId);
            updateSessionStateLocked(new CastSessionState(
                    CastState.CONNECTING, -1, episodeName, device.getName()));
            return new RemoteCastHandle(generation, sessionId);
        }
    }

    public void confirmRemoteCast(RemoteCastHandle handle) {
        synchronized (lock) {
            if (!matchesRemoteCastLocked(handle) || connectedDevice == null) return;
            updateSessionStateLocked(new CastSessionState(CastState.PLAYING, -1,
                    sessionState.episodeName(), connectedDevice.getName()));
        }
    }

    public boolean isRemoteCast(RemoteCastHandle handle) {
        synchronized (lock) {
            return matchesRemoteCastLocked(handle);
        }
    }

    public void failRemoteCast(RemoteCastHandle handle) {
        synchronized (lock) {
            if (!matchesRemoteCastLocked(handle)) return;
            finishRemoteCastLocked();
        }
    }

    /** Called by the foreground service after a relay has stopped receiving media requests. */
    public boolean expireInactiveRemoteCast(long now, long idleMillis) {
        synchronized (lock) {
            if (remoteCastSessionId == null || !remoteCastRelayDependent) return false;
            long lastAccess = CastMediaAccess.lastAccessAt();
            if (lastAccess > 0L && now >= lastAccess && now - lastAccess < idleMillis) {
                return false;
            }
            finishRemoteCastLocked();
            return true;
        }
    }

    private boolean matchesRemoteCastLocked(RemoteCastHandle handle) {
        return handle != null && handle.generation == castGeneration
                && handle.sessionId.equals(remoteCastSessionId);
    }

    private void finishRemoteCastLocked() {
        castGeneration++;
        remoteCastSessionId = null;
        remoteCastDisconnect = null;
        remoteCastRelayDependent = false;
        CastMediaAccess.clear();
        connectedDevice = null;
        castSession = null;
        sessionCompatCast = false;
        updateSessionStateLocked(CastSessionState.idle());
    }

    public Device getConnectedDevice() {
        synchronized (lock) {
            return connectedDevice;
        }
    }

    /** True when the current cast session streams the aggregated TS compatibility format. */
    public boolean isCompatCastActive() {
        synchronized (lock) {
            return sessionCompatCast;
        }
    }

    public boolean isHlsUnsupportedDevice(Device device) {
        return com.wuming.android.tv.cast.RendererCapabilityProfiles.hlsUnsupported(device);
    }

    public void addCastSessionListener(CastSessionListener listener) {
        if (listener == null) return;
        sessionListeners.add(listener);
        CastSessionState state;
        synchronized (lock) {
            state = sessionState;
        }
        App.post(() -> {
            if (sessionListeners.contains(listener)) listener.onCastSessionChanged(state);
        });
    }

    public void removeCastSessionListener(CastSessionListener listener) {
        if (listener != null) sessionListeners.remove(listener);
    }

    private void releaseCurrentCastLocked() {
        if (currentCast != null) {
            currentCast.release();
            currentCast = null;
        }
    }

    private void startCastLocked(CastVideo video,
                                 com.wuming.tv.core.playback.BilibiliCastPlayback.Stream progressive,
                                 Runnable onCasted) {
        Device device = connectedDevice;
        if (device == null) return;

        lastHlsCastUrl = null;
        lastHlsVideo = null;

        video = backfillMissingMetadata(video);
        if (progressive != null) {
            dispatchCastLocked(BilibiliCastResolver.prepare(video, progressive), onCasted);
            return;
        }
        video = resolveQuarkCompatibleVideo(video);

        boolean directMode = determineCastMode(video);
        video = prepareCastUrl(video, device, directMode);

        dispatchCastLocked(video, onCasted);
    }

    /** Backfills siteKey/flag from the cast session queue when the CastVideo lacks them. */
    private CastVideo backfillMissingMetadata(CastVideo video) {
        if ((video.siteKey() != null && video.flag() != null)
                || castSession == null || castSession.queue == null) {
            return video;
        }
        String sessionSiteKey = castSession.queue.siteKey();
        String sessionFlag = castSession.queue.flag();
        if (sessionSiteKey == null || sessionFlag == null) {
            return video;
        }
        if (video.siteKey() != null && video.flag() != null) {
            return video;
        }
        AppLog.w(TAG, "[metadata] backfilling siteKey/flag from castSession queue");
        return new CastVideo(video.name(), video.url(), video.position(), video.duration(),
                video.headers(), video.nextName(), video.nextUrl(),
                video.siteKey() != null ? video.siteKey() : sessionSiteKey,
                video.flag() != null ? video.flag() : sessionFlag,
                video.audioTrackFormat());
    }

    /**
     * Resolves Quark smart/HLS playback to original-quality MP4 when casting to a
     * built-in DLNA renderer, and marks Quark original proxy streams for parallel cast.
     */
    private CastVideo resolveQuarkCompatibleVideo(CastVideo video) {
        String url = video.url();
        boolean quarkOriginalProxy = preferRelayForOriginalQuark(video.flag(), isPhoneProxyUrl(url));

        if (!quarkOriginalProxy && shouldResolveOriginalForBuiltinDlna(video.flag(), url)) {
            AppLog.w(TAG, "[quark] resolving smart/HLS playback with original quality");
            CastVideo originalVideo = resolveWithOriginalQuality(video);
            if (originalVideo != null && !isHlsUrl(originalVideo.url())) {
                AppLog.w(TAG, "[quark] resolved DLNA-compatible original stream: " + originalVideo.url());
                video = originalVideo;
                url = video.url();
                quarkOriginalProxy = preferRelayForOriginalQuark(video.flag(), isPhoneProxyUrl(url));
            } else {
                AppLog.w(TAG, "[quark] could not resolve original quality; retaining smart stream fallback");
            }
        }

        if (quarkOriginalProxy) {
            AppLog.w(TAG, "[quark] original proxy detected; preserving normalized stream for DLNA relay");
            url = markParallelQuarkCast(url);
            video = new CastVideo(video.name(), url, video.position(), video.duration(),
                    video.headers(), video.nextName(), video.nextUrl(), video.siteKey(),
                    video.flag(), video.audioTrackFormat());
        }

        return video;
    }

    /** Decides whether to use direct (CDN URL to TV) or relay (phone proxy) mode. */
    private boolean determineCastMode(CastVideo video) {
        String url = video.url();
        boolean quarkOriginalProxy = preferRelayForOriginalQuark(video.flag(), isPhoneProxyUrl(url));
        if (quarkOriginalProxy) {
            return false;
        }
        int castMode = com.wuming.android.tv.setting.DisplaySettings.getDlnaCastMode();
        if (castMode == 2) {
            boolean direct = shouldUseDirectMode(video) && canResolveDirectUrl(video);
            AppLog.w(TAG, "[mode] auto: direct=" + direct
                    + " (shouldUseDirect=" + shouldUseDirectMode(video)
                    + ", canResolve=" + canResolveDirectUrl(video) + ")");
            return direct;
        }
        boolean direct = castMode == 1;
        AppLog.w(TAG, "[mode] " + (direct ? "DIRECT" : "RELAY") + ", url=" + video.url());
        return direct;
    }

    /**
     * Prepares the final cast URL based on the selected mode:
     * - Direct: resolves phone-proxy URLs to CDN URLs
     * - Relay: wraps with /cast/ authorization or /cast-relay/ relay (with HLS/DASH rewriting)
     * - HLS fallback: switches to aggregated TS/MP4 for renderers without HLS support
     */
    private CastVideo prepareCastUrl(CastVideo video, Device device, boolean directMode) {
        String url = video.url();
        boolean isPhoneProxy = isPhoneProxyUrl(url);

        if (directMode && isPhoneProxy) {
            AppLog.w(TAG, "[direct] proxy URL detected, resolving synchronously");
            video = resolveOrUseDirect(video);
            String resolvedUrl = video.url();
            AppLog.w(TAG, "[direct] resolved URL: " + resolvedUrl
                    + ", isPhoneProxy=" + isPhoneProxyUrl(resolvedUrl));
            if (isPhoneProxyUrl(resolvedUrl)) {
                AppLog.w(TAG, "[direct] resolution failed, falling back to relay mode");
                directMode = false;
            }
        }

        if (directMode && !isPhoneProxyUrl(video.url())) {
            AppLog.w(TAG, "[direct] already direct CDN URL, casting immediately");
            return video;
        }

        if (!directMode) {
            return prepareRelayUrl(video, device);
        }

        return video;
    }

    /** Prepares the relay URL with appropriate authorization, HLS/DASH rewriting, and fallback. */
    private CastVideo prepareRelayUrl(CastVideo video, Device device) {
        AppLog.w(TAG, "[relay] streaming through phone proxy");
        String authorizedUrl = CastMediaAccess.authorize(video.url());

        if (shouldUseGenericRelay(video.url(), authorizedUrl, isPhoneProxyUrl(video.url()))) {
            authorizedUrl = CastMediaAccess.relay(video.url(), video.headers());
        } else if (isPhoneProxyUrl(video.url()) && isDashUrl(video.url())) {
            AppLog.w(TAG, "[relay] phone-proxy DASH (MPD), routing through generic relay for child URL rewriting");
            authorizedUrl = CastMediaAccess.relay(video.url(), video.headers());
        } else if (isPhoneProxyUrl(video.url()) && isHlsUrl(video.url())) {
            String cdnUrl = extractUpstreamFromProxy(video.url());
            String relayTarget = cdnUrl != null && !cdnUrl.isEmpty() ? cdnUrl : video.url();
            if (!relayTarget.equals(video.url())) {
                AppLog.w(TAG, "[relay] unwrapped phone-proxy to direct CDN: " + relayTarget);
            }
            AppLog.w(TAG, "[relay] phone-proxy HLS, routing through generic relay for child URL rewriting");
            authorizedUrl = CastMediaAccess.relayLive(relayTarget, video.headers());
        }

        AppLog.w(TAG, "[relay] original=" + video.url() + ", authorized=" + authorizedUrl);

        // HLS compatibility fallback for renderers that cannot parse m3u8 playlists
        if (authorizedUrl != null && authorizedUrl.endsWith("/stream.m3u8")
                && com.wuming.android.tv.cast.RendererCapabilityProfiles.hlsUnsupported(device)) {
            String aggregatedUrl = toAggregatedUrl(authorizedUrl);
            AppLog.w(TAG, "[relay] renderer '" + device.getName()
                    + "' lacks HLS support; using aggregated fallback: " + aggregatedUrl);
            authorizedUrl = aggregatedUrl;
            video = new CastVideo(video.name(), authorizedUrl, 0, 0, video.headers(),
                    video.nextName(), video.nextUrl(), video.siteKey(), video.flag(),
                    video.audioTrackFormat());
        } else if (authorizedUrl != null && authorizedUrl.endsWith("/stream.m3u8")) {
            lastHlsCastUrl = authorizedUrl;
            lastHlsVideo = video;
            aggregatedFallbackUsed = false;
        }

        if (authorizedUrl != null && !authorizedUrl.equals(video.url())) {
            video = withUrl(video, authorizedUrl);
        }

        return video;
    }

    private CastVideo withUrl(CastVideo video, String url) {
        return new CastVideo(video.name(), url, video.position(), video.duration(), video.headers(),
                video.nextName(), video.nextUrl(), video.siteKey(), video.flag(), video.audioTrackFormat());
    }

    /** Cast URL path of the aggregated progressive variant of a relay playlist (MP4 when remuxable). */
    private static String toAggregatedUrl(String playlistUrl) {
        return playlistUrl.replace("/stream.m3u8", "/aggregated.mp4");
    }

    private void dispatchCastLocked(CastVideo video, Runnable onCasted) {
        Device device = connectedDevice;
        if (device == null) return;
        aggregatedCastInFlight = video.url().endsWith("/aggregated.ts")
                || video.url().endsWith("/aggregated.mp4");
        if (aggregatedCastInFlight && video.url().endsWith("/aggregated.ts")) {
            // Raw aggregated TS: the dongle's basic player can neither report duration nor
            // seek within it. Warn once per cast session so the user knows why those
            // controls are unavailable. Remuxed MP4 casts keep full duration/seek, so no
            // warning is shown for /aggregated.mp4.
            boolean firstCompatCast = !sessionCompatCast;
            sessionCompatCast = true;
            if (firstCompatCast) {
                App.post(() -> Notify.show(R.string.cast_compat_mode));
            }
        }
        long generation = ++castGeneration;
        int episodeIndex = castSession == null ? -1 : castSession.index;
        String episodeName = castSession == null ? video.name() : castSession.current().name();
        updateSessionStateLocked(new CastSessionState(CastState.CONNECTING, episodeIndex, episodeName, device.getName()));
        Runnable onEnded = castSession != null && castSession.hasNext()
                ? () -> onPlaybackEnded(generation)
                : () -> {};
        Runnable onPlaying = () -> onCastPlaying(generation, episodeIndex, episodeName, onCasted);
        Runnable onFailed = () -> onCastFailed(generation, episodeIndex, episodeName);
        CastBackend backend = connectedDevice != null && connectedDevice.isChromecast()
                && chromecastBackend != null ? chromecastBackend : castBackend;
        currentCast = backend.createSession(video, onPlaying, onEnded, onFailed);
        currentCast.start(device);
    }

    /**
     * In auto mode, decide whether to use direct mode based on whether auth headers are needed.
     * Direct mode is safe when the CDN doesn't require custom auth headers (Cookie, Authorization, etc.).
     */
    private boolean shouldUseDirectMode(CastVideo video) {
        if (video.headers() == null || video.headers().isEmpty()) {
            AppLog.w(TAG, "shouldUseDirectMode: no headers needed -> DIRECT");
            return true;
        }
        boolean hasOnlyStandardHeaders = true;
        for (Map.Entry<String, String> entry : video.headers().entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (!key.equals("user-agent") && !key.equals("referer")
                    && !key.equals("range") && !key.equals("accept")) {
                hasOnlyStandardHeaders = false;
                break;
            }
        }
        AppLog.w(TAG, "shouldUseDirectMode: hasOnlyStandardHeaders=" + hasOnlyStandardHeaders
                + " -> " + (hasOnlyStandardHeaders ? "DIRECT" : "RELAY"));
        return hasOnlyStandardHeaders;
    }

    /**
     * In direct mode, either resolve a phone proxy URL to the actual CDN URL,
     * or return the URL as-is if it's already a direct CDN URL.
     */
    private CastVideo resolveOrUseDirect(CastVideo video) {
        String url = video.url();
        if (url == null || url.isEmpty()) return video;

        AppLog.w(TAG, "resolveOrUseDirect: input url=" + url);
        boolean isPhoneProxy = isPhoneProxyUrl(url);
        AppLog.w(TAG, "resolveOrUseDirect: isPhoneProxy=" + isPhoneProxy);
        if (!isPhoneProxy) {
            AppLog.w(TAG, "resolveOrUseDirect: already direct CDN URL, using as-is");
            return video;
        }

        String directUrl = resolveFromQueue(video);
        if (directUrl != null && !directUrl.equals(url)) {
            // DLNA TVs typically cannot play HLS (m3u8) URLs directly.
            // If the resolved URL is m3u8, fall back to relay mode (phone proxy)
            // which converts HLS to a direct stream the TV can play.
            if (isHlsUrl(directUrl)) {
                AppLog.w(TAG, "Resolved URL is HLS (m3u8), falling back to relay mode: " + directUrl);
                return video;
            }
            AppLog.w(TAG, "Resolved from queue to direct CDN: " + directUrl);
            return new CastVideo(video.name(), directUrl, video.position(), video.duration(), video.headers(),
                    video.nextName(), video.nextUrl(), video.siteKey(), video.flag(), video.audioTrackFormat());
        }

        AppLog.w(TAG, "Could not resolve proxy URL, using as-is: " + url);
        return video;
    }

    /**
     * Resolve proxy URL to direct CDN URL using queue data.
     * Returns null if resolution fails.
     */
    private String resolveFromQueue(CastVideo video) {
        String siteKey = null;
        String flag = null;
        if (castSession != null && castSession.queue != null) {
            siteKey = castSession.queue.siteKey();
            flag = castSession.queue.flag();
        }
        if (siteKey == null || flag == null) {
            AppLog.w(TAG, "resolveFromQueue: missing siteKey or flag (queue=" + (castSession != null ? castSession.queue : "null") + ")");
            return null;
        }
        String episodeUrl = null;
        if (castSession != null && castSession.current() != null) {
            episodeUrl = castSession.current().url();
            AppLog.w(TAG, "resolveFromQueue: episodeUrl from queue=" + episodeUrl);
        }
        if (episodeUrl == null || episodeUrl.isEmpty()) {
            AppLog.w(TAG, "resolveFromQueue: no episode URL available");
            return null;
        }
        try {
            Result result = SiteApi.playerContent(siteKey, flag, episodeUrl);
            if (result != null && !result.hasMsg()) {
                String realUrl = result.getRealUrl();
                if (realUrl != null && !realUrl.isEmpty() && !isPhoneProxyUrl(realUrl)) {
                    return realUrl;
                }
                // Spider returned a phone-proxy URL (e.g. http://127.0.0.1:9978/proxy?do=m3u8&url=<cdn>&headers=<b64>).
                // Extract the upstream CDN URL from the "url" query param so direct mode can cast it
                // without routing through the phone proxy. The proxy adds ~7s of m3u8 fetch + ad
                // detection latency which causes the TV's DLNA renderer to time out and send STOPPED.
                if (realUrl != null && !realUrl.isEmpty() && isPhoneProxyUrl(realUrl)) {
                    String extracted = extractUpstreamFromProxy(realUrl);
                    if (extracted != null) {
                        AppLog.w(TAG, "resolveFromQueue: extracted direct CDN from proxy URL: " + extracted);
                        return extracted;
                    }
                }
            }
        } catch (Exception e) {
            AppLog.w(TAG, "resolveFromQueue: failed to resolve: " + e.getMessage());
        }
        return null;
    }

    /**
     * Extract the upstream CDN URL from a phone-proxy URL of the form
     * http://<host>:<port>/proxy?do=m3u8&url=<cdn-url>&headers=<base64>.
     * Returns null if the URL cannot be parsed or has no "url" query parameter.
     */
    private static String extractUpstreamFromProxy(String proxyUrl) {
        try {
            HttpUrl parsed = HttpUrl.parse(proxyUrl);
            if (parsed == null) return null;
            String upstream = parsed.queryParameter("url");
            if (upstream == null || upstream.isEmpty()) return null;
            return upstream;
        } catch (Exception e) {
            AppLog.w(TAG, "extractUpstreamFromProxy: failed: " + e.getMessage());
            return null;
        }
    }

    private boolean canResolveDirectUrl(CastVideo video) {
        if (castSession == null || castSession.queue == null) return false;
        if (castSession.queue.siteKey() == null || castSession.queue.flag() == null) return false;
        if (castSession.current() == null || castSession.current().url() == null) return false;
        return true;
    }

    /**
     * Check if a URL is an HLS (M3U8) stream.
     * DLNA TVs typically do not support HLS format.
     */
    public static boolean preferRelayForOriginalQuark(String flag, boolean phoneProxy) {
        return phoneProxy && isQuarkOriginalQuality(flag);
    }

    public static String markParallelQuarkCast(String url) {
        if (url == null || url.isEmpty()) return url;
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null || "parallel".equals(parsed.queryParameter("quarkCast"))) return url;
        return parsed.newBuilder().addQueryParameter("quarkCast", "parallel").build().toString();
    }

    private static boolean isQuarkOriginalQuality(String flag) {
        if (flag == null || flag.isEmpty()) return false;
        return flag.contains("夸父原") || flag.contains("夸克原");
    }

    private static boolean isQuarkSmartQuality(String flag) {
        if (flag == null || flag.isEmpty()) return false;
        return flag.contains("夸父智") || flag.contains("夸克智");
    }

    private static boolean isHlsUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains(".m3u8") || lower.contains("/m3u8");
    }

    private static boolean isDashUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains(".mpd") || lower.contains("type=mpd") || lower.contains("/mpd");
    }

    static boolean shouldResolveOriginalForBuiltinDlna(String flag, String playbackUrl) {
        return isQuarkSmartQuality(flag) || isHlsUrl(playbackUrl);
    }

    static boolean shouldUseGenericRelay(String playbackUrl, String authorizedUrl,
                                         boolean phoneProxy) {
        return Objects.equals(authorizedUrl, playbackUrl)
                && !phoneProxy;
    }

    /**
     * Try to resolve the video with original quality flag (e.g., "夸父原" instead of "夸父智")
     * to get a direct MP4 file instead of an HLS stream.
     */
    private CastVideo resolveWithOriginalQuality(CastVideo video) {
        String siteKey = video.siteKey();
        String flag = video.flag();
        String queuedEpisodeUrl = castSession != null && castSession.current() != null
                ? castSession.current().url() : null;
        String episodeUrl = resolutionSourceUrl(video.url(), queuedEpisodeUrl);
        if (siteKey == null || flag == null) {
            AppLog.w(TAG, "resolveWithOriginalQuality: missing siteKey or flag from video");
            return null;
        }
        if (episodeUrl == null || episodeUrl.isEmpty()) {
            AppLog.w(TAG, "resolveWithOriginalQuality: no episode URL available from video");
            return null;
        }

        // Try to construct an original quality flag
        // e.g., "夸父智" -> "夸父原", "夸克智" -> "夸克原"
        String originalFlag = flag;
        if (flag.contains("智")) {
            originalFlag = flag.replace("智", "原");
        } else if (flag.contains("普画")) {
            originalFlag = flag.replace("普画", "原");
        }
        if (originalFlag.equals(flag)) {
            AppLog.w(TAG, "resolveWithOriginalQuality: flag already appears to be original: " + flag);
            return null;
        }

        AppLog.w(TAG, "resolveWithOriginalQuality: trying flag=" + originalFlag + " (was " + flag + ")");
        try {
            Result result = SiteApi.playerContent(siteKey, originalFlag, episodeUrl);
            if (result != null && !result.hasMsg()) {
                String realUrl = result.getRealUrl();
                if (realUrl != null && !realUrl.isEmpty() && !isHlsUrl(realUrl)) {
                    AppLog.w(TAG, "resolveWithOriginalQuality: success with flag=" + originalFlag + ", url=" + realUrl);
                    Map<String, String> headers = result.getHeader();
                    return new CastVideo(video.name(), realUrl, video.position(), video.duration(),
                            headers == null ? Collections.emptyMap() : headers,
                            video.nextName(), video.nextUrl(), siteKey, originalFlag, video.audioTrackFormat());
                }
            }
        } catch (Exception e) {
            AppLog.w(TAG, "resolveWithOriginalQuality: failed: " + e.getMessage());
        }
        return null;
    }

    static String resolutionSourceUrl(String playbackUrl, String queuedEpisodeUrl) {
        return queuedEpisodeUrl == null || queuedEpisodeUrl.isEmpty()
                ? playbackUrl : queuedEpisodeUrl;
    }

    /**
     * Check if a URL goes through the phone's proxy server.
     * Proxy URLs use /proxy or /cast-relay paths on either the local (127.0.0.1)
     * or LAN IP address of the phone.
     */
    private boolean isPhoneProxyUrl(String url) {
        if (url == null) return false;
        android.net.Uri uri = android.net.Uri.parse(url);
        String path = uri.getPath();
        if (path == null) return false;
        boolean isProxyPath = path.startsWith("/proxy") || path.startsWith("/cast-relay/");
        if (!isProxyPath) return false;
        int serverPort = Proxy.getPort();
        int urlPort = uri.getPort() != -1 ? uri.getPort() : 80;
        boolean portMatches = urlPort == serverPort;
        AppLog.w(TAG, "isPhoneProxyUrl: url=" + url + ", path=" + path + ", urlPort=" + urlPort + ", serverPort=" + serverPort + ", portMatches=" + portMatches);
        return portMatches;
    }

    private void onCastPlaying(long generation, int episodeIndex, String episodeName, Runnable onCasted) {
        synchronized (lock) {
            if (generation != castGeneration || connectedDevice == null) return;
            if (aggregatedCastInFlight) {
                // A Play acknowledgement is not proof that HLS is unsupported.
                aggregatedCastInFlight = false;
            }
            updateSessionStateLocked(new CastSessionState(
                    CastState.PLAYING, episodeIndex, episodeName, connectedDevice.getName()));
        }
        if (onCasted != null) onCasted.run();
    }

    private void onCastFailed(long generation, int episodeIndex, String episodeName) {
        synchronized (lock) {
            if (generation != castGeneration || connectedDevice == null) return;
            updateSessionStateLocked(new CastSessionState(
                    CastState.ERROR, episodeIndex, episodeName, connectedDevice.getName()));
        }
    }

    private void onPlaybackEnded(long generation) {
        CastQueue queue;
        CastQueue.Item next;
        int nextIndex;
        synchronized (lock) {
            if (generation != castGeneration || castSession == null || castSession.transitioning) return;
            // Renderers without HLS support stop right after PLAYING (playlist fetched, a few
            // segments buffered, black screen, STOPPED). Before advancing the queue, retry the
            // same episode once through the aggregated progressive TS endpoint.
            if (!aggregatedFallbackUsed && lastHlsCastUrl != null && lastHlsVideo != null) {
                aggregatedFallbackUsed = true;
                String aggregatedUrl = toAggregatedUrl(lastHlsCastUrl);
                AppLog.w(TAG, "HLS cast ended prematurely; retrying with aggregated TS: " + aggregatedUrl);
                CastVideo video = new CastVideo(lastHlsVideo.name(), aggregatedUrl, 0, 0,
                        lastHlsVideo.headers(), lastHlsVideo.nextName(), lastHlsVideo.nextUrl(),
                        lastHlsVideo.siteKey(), lastHlsVideo.flag(), lastHlsVideo.audioTrackFormat());
                lastHlsCastUrl = null;
                lastHlsVideo = null;
                dispatchCastLocked(video, null);
                return;
            }
            nextIndex = castSession.index + 1;
            if (nextIndex >= castSession.queue.items().size()) return;
            castSession.transitioning = true;
            queue = castSession.queue;
            next = queue.items().get(nextIndex);
            updateSessionStateLocked(new CastSessionState(
                    CastState.RESOLVING, nextIndex, next.name(), connectedDevice == null ? "" : connectedDevice.getName()));
        }
        final int resolvedIndex = nextIndex;
        Task.execute(() -> resolveAndCastNext(generation, queue, next, resolvedIndex));
    }

    /**
     * Switch to the next episode in the cast queue (user-initiated via swipe up).
     */
    public void castNext() {
        long generation;
        CastQueue queue;
        CastQueue.Item next;
        int nextIndex;
        synchronized (lock) {
            if (castSession == null || castSession.transitioning) return;
            nextIndex = castSession.index + 1;
            if (nextIndex >= castSession.queue.items().size()) {
                App.post(() -> Notify.show(R.string.error_play_next));
                return;
            }
            castSession.transitioning = true;
            generation = castGeneration;
            queue = castSession.queue;
            next = queue.items().get(nextIndex);
            updateSessionStateLocked(new CastSessionState(
                    CastState.RESOLVING, nextIndex, next.name(), connectedDevice == null ? "" : connectedDevice.getName()));
        }
        final int resolvedIndex = nextIndex;
        Task.execute(() -> resolveAndCastNext(generation, queue, next, resolvedIndex));
    }

    /**
     * Switch to the previous episode in the cast queue (user-initiated via swipe down).
     */
    public void castPrev() {
        long generation;
        CastQueue queue;
        CastQueue.Item prev;
        int prevIndex;
        synchronized (lock) {
            if (castSession == null || castSession.transitioning) return;
            prevIndex = castSession.index - 1;
            if (prevIndex < 0) {
                App.post(() -> Notify.show(R.string.error_play_prev));
                return;
            }
            castSession.transitioning = true;
            generation = castGeneration;
            queue = castSession.queue;
            prev = queue.items().get(prevIndex);
            updateSessionStateLocked(new CastSessionState(
                    CastState.RESOLVING, prevIndex, prev.name(), connectedDevice == null ? "" : connectedDevice.getName()));
        }
        final int resolvedIndex = prevIndex;
        Task.execute(() -> resolveAndCastNext(generation, queue, prev, resolvedIndex));
    }

    private void resolveAndCastNext(long generation, CastQueue queue, CastQueue.Item next, int nextIndex) {
        try {
            Result result = SiteApi.playerContent(queue.siteKey(), queue.flag(), next.url());
            String url = result == null ? null : result.getRealUrl();
            if (result == null || result.hasMsg() || url == null || url.isEmpty()) {
                throw new IllegalStateException(result != null && result.hasMsg() ? result.getMsg() : "Empty playback URL");
            }
            Map<String, String> headers = result.getHeader();
            CastVideo video = new CastVideo(next.name(), url, 0, 0,
                    headers == null ? Collections.emptyMap() : headers,
                    null, null, queue.siteKey(), queue.flag(), queue.audioTrackFormat());
            com.wuming.tv.core.playback.BilibiliCastPlayback.Stream progressive = BilibiliCastResolver.resolve(video);
            synchronized (lock) {
                if (generation != castGeneration || castSession == null || castSession.queue != queue) return;
                releaseCurrentCastLocked();
                castSession.index = nextIndex;
                castSession.transitioning = false;
                startCastLocked(video, progressive, null);
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Unable to resolve next cast episode: " + next.name(), e);
            synchronized (lock) {
                if (generation == castGeneration && castSession != null && castSession.queue == queue) {
                    castSession.transitioning = false;
                    updateSessionStateLocked(new CastSessionState(
                            CastState.ERROR, nextIndex, next.name(), connectedDevice == null ? "" : connectedDevice.getName()));
                }
            }
            App.post(() -> Notify.show(R.string.dlna_next_episode_failed));
        }
    }

    private static final class CastSession {

        private final CastQueue queue;
        private int index;
        private boolean transitioning;

        private CastSession(CastQueue queue) {
            this.queue = queue;
            this.index = queue.currentIndex();
        }

        private boolean hasNext() {
            return index + 1 < queue.items().size();
        }

        private CastQueue.Item current() {
            return queue.items().get(index);
        }
    }

    private void updateSessionStateLocked(CastSessionState state) {
        if (sessionCompatCast && !state.compatMode()) {
            state = new CastSessionState(state.state(), state.episodeIndex(),
                    state.episodeName(), state.deviceName(), true);
        }
        final CastSessionState published = state;
        sessionState = published;
        CastSessionNotifier.publish(published, remoteCastRelayDependent);
        for (CastSessionListener listener : sessionListeners) {
            App.post(() -> {
                if (sessionListeners.contains(listener)) listener.onCastSessionChanged(published);
            });
        }
    }

    public enum CastState {
        IDLE,
        CONNECTING,
        RESOLVING,
        PLAYING,
        ERROR
    }

    public record CastSessionState(CastState state, int episodeIndex, String episodeName,
                                    String deviceName, boolean compatMode) {

        public CastSessionState {
            episodeName = episodeName == null ? "" : episodeName;
            deviceName = deviceName == null ? "" : deviceName;
        }

        public CastSessionState(CastState state, int episodeIndex, String episodeName, String deviceName) {
            this(state, episodeIndex, episodeName, deviceName, false);
        }

        public static CastSessionState idle() {
            return new CastSessionState(CastState.IDLE, -1, "", "", false);
        }

        public boolean isActive() {
            return state != CastState.IDLE;
        }

        public String getDisplayText() {
            if (episodeName.isEmpty()) return deviceName;
            return deviceName + " - " + episodeName;
        }
    }

    public record RemoteCastHandle(long generation, String sessionId) {
    }

    public interface CastSessionListener {
        void onCastSessionChanged(CastSessionState state);
    }

    public interface DiscoveryCallback {
        void onDeviceFound(Device device);
        void onDeviceLost(Device device);
    }
}
