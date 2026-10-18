package com.wuming.android.tv.cloud.opendrive;

import com.wuming.tv.core.cloud.*;

import com.wuming.tv.core.playback.OpenDrivePlaybackHints;

import com.github.catvod.crawler.Spider;
import com.wuming.android.tv.bean.Episode;
import com.wuming.android.tv.bean.Flag;
import com.wuming.android.tv.bean.Vod;
import com.wuming.android.tv.cloud.CloudStorageProvider;
import com.wuming.tv.core.media.OpenDriveUri;
import com.wuming.android.tv.media.OpenDriveUriResolver;
import com.wuming.tv.core.media.OpenDriveProviderIdentityCodec;
import com.wuming.android.tv.media.AndroidMediaPipelineFactory;
import com.wuming.tv.core.media.PlaybackTarget;
import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import okhttp3.HttpUrl;

/** Current-TV CatVod adapter for the bundled OpenDrive engine. */
public final class OpenDriveSpider extends Spider {

    private static final String ACCOUNTS = "opendrive-accounts";
    private static final String FOLDER_PREFIX = "opendrive-folder:";
    private static final String FILE_PREFIX = "opendrive-file:";
    private static final String DEFERRED_PREFIX = "opendrive-deferred:";
    private static final String SHARE_PREFIX = "opendrive-share:";
    private static final String LOGIN_PREFIX = "OpenDriveLogin:";
    private static final String LOGOUT_PREFIX = "OpenDriveLogout:";
    private static final Base64.Encoder ID_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder ID_DECODER = Base64.getUrlDecoder();
    private static final Gson GSON = new Gson();

    private final OpenDriveEngine engine;
    private final OpenDriveUriResolver uriResolver;
    private final OpenDriveProviderIdentityCodec providerIdentityCodec;
    private final boolean includeManagementCatalog;

    public OpenDriveSpider() {
        this(OpenDriveRuntime.engine(), true);
    }

    OpenDriveSpider(OpenDriveEngine engine) {
        this(engine, false);
    }

    private OpenDriveSpider(OpenDriveEngine engine, boolean includeManagementCatalog) {
        this(engine, OpenDriveUriResolver.createDefault(engine),
                AndroidMediaPipelineFactory.providerIdentityCodec(), includeManagementCatalog);
    }

    OpenDriveSpider(OpenDriveEngine engine, OpenDriveUriResolver uriResolver) {
        this(engine, uriResolver, AndroidMediaPipelineFactory.providerIdentityCodec(), false);
    }

    OpenDriveSpider(OpenDriveEngine engine, OpenDriveUriResolver uriResolver,
                    OpenDriveProviderIdentityCodec providerIdentityCodec) {
        this(engine, uriResolver, providerIdentityCodec, false);
    }

    private OpenDriveSpider(OpenDriveEngine engine, OpenDriveUriResolver uriResolver,
                            OpenDriveProviderIdentityCodec providerIdentityCodec,
                            boolean includeManagementCatalog) {
        this.engine = engine;
        this.uriResolver = uriResolver;
        this.providerIdentityCodec = providerIdentityCodec;
        this.includeManagementCatalog = includeManagementCatalog;
    }

    public static boolean supportsShare(String url) {
        return shareProvider(url) != null;
    }

    public static String shareVodId(String url) {
        ShareTarget target = shareProvider(url);
        if (target == null) throw new IllegalArgumentException("Unsupported cloud share link");
        return encode(SHARE_PREFIX, target.providerId(), target.canonicalUrl());
    }

    public static boolean isFileId(String id) {
        if (id == null || !id.startsWith(FILE_PREFIX)) return false;
        try {
            decode(id, FILE_PREFIX);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static boolean isDeferredId(String id) {
        if (id == null || !id.startsWith(DEFERRED_PREFIX)) return false;
        try {
            decode(id, DEFERRED_PREFIX);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static boolean isRoutedId(String id) {
        return isFileId(id) || isDeferredId(id);
    }

    /** Returns the provider encoded in a validated routed ID, or an empty string. */
    public static String routedProviderId(String id) {
        if (id == null) return "";
        try {
            if (id.startsWith(DEFERRED_PREFIX)) return decode(id, DEFERRED_PREFIX).providerId();
            if (id.startsWith(FILE_PREFIX)) return decode(id, FILE_PREFIX).providerId();
        } catch (IllegalArgumentException ignored) {
        }
        return "";
    }

    public static boolean isCanonicalId(String id) {
        return OpenDriveUri.isValid(id);
    }

    /** Caches parsed external episodes and replaces their payloads with opaque deferred IDs. */
    public int rewriteExternalEpisodes(Vod vod) {
        if (vod == null) return 0;
        List<OpenDriveProvider> activeProviders = activeProviders();
        if (activeProviders.isEmpty()) return 0;
        int rewritten = 0;
        for (Flag flag : vod.getFlags()) {
            for (Episode episode : flag.getEpisodes()) {
                String routed = deferExternalEpisode(activeProviders, flag.getFlag(), episode.getUrl());
                if (routed == null) continue;
                episode.setUrl(routed);
                rewritten++;
            }
        }
        if (rewritten > 0) vod.setPlayUrl(playUrl(vod.getFlags()));
        return rewritten;
    }

    /** Routes one episode selected from an already-open external detail page. */
    public String routeExternalEpisode(String flag, String payload) {
        List<OpenDriveProvider> activeProviders = activeProviders();
        return activeProviders.isEmpty() ? null
                : deferExternalEpisode(activeProviders, flag, payload);
    }

    private List<OpenDriveProvider> activeProviders() {
        List<OpenDriveProvider> activeProviders = new ArrayList<>();
        for (OpenDriveProvider provider : engine.providers()) {
            try {
                if (provider.available() && provider.authenticated()) activeProviders.add(provider);
            } catch (RuntimeException ignored) {
                OpenDriveLog.providerUnavailable(provider.id(), "activeProviders check failed");
            }
        }
        return activeProviders;
    }

    String importExternalEpisode(String flag, String payload) {
        for (OpenDriveProvider provider : engine.providers()) {
            if (!provider.available() || !provider.authenticated()) continue;
            String itemId = provider.importExternal(flag, payload);
            if (itemId != null && !itemId.isBlank()) return encode(FILE_PREFIX, provider.id(), itemId);
        }
        return null;
    }

    private static String deferExternalEpisode(List<OpenDriveProvider> providers,
                                                String flag, String payload) {
        OpenDriveExternalPayload external = OpenDriveExternalPayload.create(flag, payload);
        if (external == null) return null;
        String labeledProviderId = ExternalEpisodePayload.labeledProviderId(flag);
        if (labeledProviderId != null) {
            // A route label is the user's source choice and is therefore stronger evidence than
            // mixed or copied metadata inside an aggregator payload. Never let a recognized UC
            // label fall through to Quark (or vice versa) merely because that adapter probes first.
            for (OpenDriveProvider provider : providers) {
                if (!labeledProviderId.equals(provider.id())) continue;
                OpenDrivePreparedExternal prepared = provider.prepareExternal(external);
                if (prepared == null) return null;
                String handle = OpenDriveExternalEpisodeCache.put(provider, prepared,
                        OpenDriveExternalEpisodeCache.retainedBytes(flag, payload));
                return encode(DEFERRED_PREFIX, provider.id(), handle);
            }
            return null;
        }
        for (OpenDriveProvider provider : providers) {
            OpenDrivePreparedExternal prepared = provider.prepareExternal(external);
            if (prepared == null) continue;
            String handle = OpenDriveExternalEpisodeCache.put(provider, prepared,
                    OpenDriveExternalEpisodeCache.retainedBytes(flag, payload));
            return encode(DEFERRED_PREFIX, provider.id(), handle);
        }
        return null;
    }

    @Override
    public String homeContent(boolean filter) {
        Map<String, Object> type = map(
                "type_id", ACCOUNTS,
                "type_name", "OpenDrive accounts",
                "type_flag", "1");
        return json(map("class", List.of(type), "filters", Collections.emptyMap()));
    }

    @Override
    public String categoryContent(String tid, String page, boolean filter,
                                  HashMap<String, String> extend) throws Exception {
        if (page != null && !page.isBlank() && !"1".equals(page)) return emptyList();
        if (tid == null || tid.isBlank() || ACCOUNTS.equals(tid)) return accountList();
        ResourceId folder = decode(tid, FOLDER_PREFIX);
        List<Map<String, Object>> items = new ArrayList<>();
        for (OpenDriveItem item : engine.list(folder.providerId(), folder.itemId())) {
            LinkedHashMap<String, Object> vod = map(
                    "vod_id", encode(item.directory() ? FOLDER_PREFIX : FILE_PREFIX,
                            folder.providerId(), item.id()),
                    "vod_name", item.name(),
                    "vod_remarks", item.directory() ? "Folder" : size(item.size()));
            if (item.directory()) vod.put("vod_tag", "folder");
            items.add(vod);
        }
        return list(items);
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("OpenDrive file is required");
        if (OpenDriveUri.hasScheme(ids.get(0))) {
            if (!isCanonicalId(ids.get(0))) throw invalidId();
            Map<String, Object> vod = map(
                    "vod_id", ids.get(0),
                    "vod_name", "OpenDrive resource",
                    "vod_play_from", "OpenDrive",
                    "vod_play_url", "Play$" + ids.get(0));
            return list(List.of(vod));
        }
        if (ids.get(0).startsWith(SHARE_PREFIX)) {
            try {
                return shareDetail(ids.get(0));
            } catch (Exception error) {
                return message(safeShareError(error));
            }
        }
        ResourceId file = decode(ids.get(0), ids.get(0).startsWith(DEFERRED_PREFIX)
                ? DEFERRED_PREFIX : FILE_PREFIX);
        String source = engine.provider(file.providerId()).displayName();
        Map<String, Object> vod = map(
                "vod_id", ids.get(0),
                "vod_name", "Cloud file",
                "vod_play_from", source,
                "vod_play_url", "Play$" + ids.get(0));
        return list(List.of(vod));
    }

    private String shareDetail(String id) throws Exception {
        ResourceId share = decode(id, SHARE_PREFIX);
        List<OpenDriveItem> files = engine.listShare(share.providerId(), share.itemId());
        if (files.isEmpty()) throw new IllegalStateException("No playable video was found in this share");
        List<String> episodes = new ArrayList<>();
        for (OpenDriveItem file : files) {
            String name = file.name().replace('$', ' ').replace('#', ' ');
            episodes.add(name + "$" + encode(FILE_PREFIX, share.providerId(), file.id()));
        }
        Map<String, Object> vod = map(
                "vod_id", id,
                "vod_name", engine.provider(share.providerId()).displayName() + " share",
                "vod_play_from", engine.provider(share.providerId()).displayName(),
                "vod_play_url", String.join("#", episodes));
        return list(List.of(vod));
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        if (OpenDriveUri.hasScheme(id)) {
            if (!isCanonicalId(id)) throw invalidId();
            PlaybackTarget target = uriResolver.resolve(id);
            Map<String, String> playbackHeaders = OpenDrivePlaybackHints.refreshable(
                    target.headers(), id, target.expiresAtEpochMs());
            LinkedHashMap<String, Object> result = map(
                    "parse", 0, "url", target.url(), "header", playbackHeaders);
            if (requiresExplicitHlsFormat(target.url())) {
                result.put("format", "application/x-mpegURL");
            }
            return json(result);
        }
        ResourceId file;
        OpenDriveProvider preparedProvider = null;
        if (id != null && id.startsWith(DEFERRED_PREFIX)) {
            ResourceId deferred = decode(id, DEFERRED_PREFIX);
            OpenDriveExternalEpisodeCache.Materialized materialized =
                    OpenDriveExternalEpisodeCache.materialize(
                    deferred.providerId(), deferred.itemId());
            preparedProvider = materialized.provider();
            file = new ResourceId(deferred.providerId(), materialized.itemId());
        } else {
            file = decode(id, FILE_PREFIX);
        }
        OpenDrivePlayback playback = preparedProvider == null
                ? engine.resolve(file.providerId(), file.itemId())
                : resolvePrepared(preparedProvider, file.itemId());
        String mediaUri;
        try {
            boolean persisted = preparedProvider == null
                    ? engine.persistHandle(file.providerId(), file.itemId())
                    : preparedProvider.persistHandle(file.itemId());
            mediaUri = persisted
                    ? providerIdentityCodec.encode(file.providerId(), file.itemId(),
                    (preparedProvider == null ? engine.provider(file.providerId()) : preparedProvider)
                            .displayName() + " file") : "";
        } catch (RuntimeException catalogUnavailable) {
            OpenDriveLog.unexpected("catalog", "", catalogUnavailable);
            mediaUri = "";
        }
        Map<String, String> playbackHeaders = mediaUri.isEmpty() ? playback.headers()
                : OpenDrivePlaybackHints.refreshable(playback.headers(), mediaUri,
                playback.expiresAtEpochMillis());
        LinkedHashMap<String, Object> result = map(
                "parse", 0, "url", playback.url(), "header", playbackHeaders,
                "mediaUri", mediaUri);
        if (requiresExplicitHlsFormat(playback.url())
                || OpenDrivePlaybackHints.enabled(playback.headers(), OpenDrivePlaybackHints.HLS)) {
            result.put("format", "application/x-mpegURL");
        }
        return json(result);
    }

    private static OpenDrivePlayback resolvePrepared(OpenDriveProvider provider, String itemId)
            throws Exception {
        return OpenDriveFailures.resolve(provider, itemId);
    }

    @Override
    public String action(String action) throws Exception {
        if (action == null) return message("Unsupported OpenDrive action");
        if (action.startsWith(LOGIN_PREFIX)) return login(action.substring(LOGIN_PREFIX.length()));
        if (action.startsWith(LOGOUT_PREFIX)) {
            String providerId = action.substring(LOGOUT_PREFIX.length());
            engine.logout(providerId);
            return message(engine.provider(providerId).displayName() + " credentials cleared");
        }
        if ("OpenDriveStatus".equals(action)) return status();
        if ("LoginShow".equals(action)) return message("Choose an OpenDrive account to sign in");
        return message("Unsupported OpenDrive action");
    }

    @Override
    public Object[] proxy(Map<String, String> params) throws Exception {
        OpenDriveProxyResponse response = engine.proxy(params);
        return response == null ? null : response.toCatVodResponse();
    }

    private String accountList() {
        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> providerIds = new HashSet<>();
        for (OpenDriveProvider provider : engine.providers()) {
            providerIds.add(provider.id());
            boolean available = provider.available();
            boolean authenticated = available && provider.authenticated();
            LinkedHashMap<String, Object> vod = map(
                    "vod_id", "opendrive-provider:" + provider.id(),
                    "vod_name", provider.displayName());
            if (!available) {
                vod.put("vod_remarks", provider.availabilityMessage());
            } else if (!authenticated) {
                vod.put("vod_remarks", "Not logged in");
                vod.put("action", LOGIN_PREFIX + provider.id());
            } else {
                vod.put("vod_id", encode(FOLDER_PREFIX, provider.id(), "root"));
                vod.put("vod_remarks", "Logged in");
                vod.put("vod_tag", "folder");
            }
            items.add(vod);
            if (authenticated) {
                items.add(map(
                        "vod_id", "opendrive-logout:" + provider.id(),
                        "vod_name", "Log out of " + provider.displayName(),
                        "vod_remarks", "Remove saved login",
                        "action", LOGOUT_PREFIX + provider.id()));
            }
        }
        if (includeManagementCatalog) {
            for (CloudStorageProvider provider : CloudStorageProvider.values()) {
                if (providerIds.contains(provider.getId())) continue;
                items.add(map(
                        "vod_id", "opendrive-provider:" + provider.getId(),
                        "vod_name", provider.getDisplayName(),
                        "vod_remarks", "Web management only",
                        "action", LOGIN_PREFIX + provider.getId()));
            }
        }
        return list(items);
    }

    private String login(String providerId) throws Exception {
        OpenDriveProvider provider = engine.provider(providerId);
        OpenDriveLoginChallenge challenge = engine.beginLogin(providerId);
        return json(map(
                "msg", provider.displayName() + " authorization is ready",
                "cloud_auth", map(
                        "schema", 1,
                        "provider", provider.id(),
                        "session_id", challenge.id(),
                        "authorization_url", challenge.content(),
                        "expires_at", challenge.expiresAtEpochMillis())));
    }

    private String status() {
        List<Map<String, Object>> providers = new ArrayList<>();
        for (OpenDriveProvider provider : engine.providers()) {
            String status = !provider.available() ? "unavailable"
                    : provider.authenticated() ? "authenticated" : "login_required";
            providers.add(map("id", provider.id(), "status", status));
        }
        return json(map("msg", "OpenDrive provider status",
                "opendrive_status", map("schema", 1, "providers", providers)));
    }

    private static String list(List<Map<String, Object>> items) {
        return json(map("list", items, "page", 1, "pagecount", 1, "limit", items.size(),
                "total", items.size()));
    }

    private static String emptyList() {
        return list(Collections.emptyList());
    }

    private static String message(String value) {
        return json(map("msg", value));
    }

    private static String safeShareError(Exception error) {
        String value = error.getMessage();
        if (value != null && (value.startsWith("Aliyun Drive") || value.startsWith("Quark")
                || value.startsWith("UC") || value.startsWith("Baidu Netdisk")
                || value.startsWith("123 Netdisk") || value.startsWith("Tianyi Cloud")
                || value.startsWith("No playable video"))) return value;
        return "Unable to open this cloud share. It may be expired, restricted, or require login.";
    }

    private static ShareTarget shareProvider(String url) {
        String canonical = AliPanOpenDriveProvider.canonicalShareUrl(url);
        if (canonical != null) return new ShareTarget("alipan", canonical);
        canonical = QuarkOpenDriveProvider.canonicalShareUrl(url);
        if (canonical != null) return new ShareTarget("quark", AbstractOpenDriveProvider.shareUrlWithPasscode(canonical, url, "quark"));
        canonical = UcOpenDriveProvider.canonicalShareUrl(url);
        if (canonical != null) return new ShareTarget("uc", AbstractOpenDriveProvider.shareUrlWithPasscode(canonical, url, "uc"));
        canonical = BaiduOpenDriveProvider.canonicalShareUrl(url);
        if (canonical != null) return new ShareTarget("baidu", canonical);
        canonical = Pan123OpenDriveProvider.canonicalShareUrl(url);
        if (canonical != null) return new ShareTarget("123pan", canonical);
        canonical = TianyiOpenDriveProvider.canonicalShareUrl(url);
        return canonical == null ? null : new ShareTarget("tianyi", canonical);
    }

    private static String encode(String prefix, String providerId, String itemId) {
        if (!validProviderId(providerId)) {
            throw new IllegalArgumentException("OpenDrive provider ID is invalid");
        }
        String value = itemId == null || itemId.isBlank() ? "root" : itemId;
        String encoded = ID_ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
        return prefix + providerId + ":" + encoded;
    }

    private static ResourceId decode(String value, String prefix) {
        if (value == null || !value.startsWith(prefix)) throw invalidId();
        int providerStart = prefix.length();
        int separator = value.indexOf(':', providerStart);
        if (separator == providerStart || separator == value.length() - 1) throw invalidId();
        String providerId = value.substring(providerStart, separator);
        if (!validProviderId(providerId)) throw invalidId();
        try {
            String itemId = new String(ID_DECODER.decode(value.substring(separator + 1)),
                    StandardCharsets.UTF_8);
            if (itemId.isBlank()) throw invalidId();
            return new ResourceId(providerId, itemId);
        } catch (IllegalArgumentException error) {
            throw invalidId();
        }
    }

    /** Manual validation avoids compiling a regular expression for every listed episode. */
    private static boolean validProviderId(String value) {
        if (value == null || value.isEmpty() || value.length() > 32) return false;
        char first = value.charAt(0);
        if (first < 'a' || first > 'z') {
            if (first < '0' || first > '9') return false;
        }
        for (int index = 1; index < value.length(); index++) {
            char current = value.charAt(index);
            if ((current < 'a' || current > 'z') && (current < '0' || current > '9')
                    && current != '_' && current != '-') return false;
        }
        return true;
    }

    private static IllegalArgumentException invalidId() {
        return new IllegalArgumentException("OpenDrive resource ID is invalid");
    }

    static boolean requiresExplicitHlsFormat(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) return false;
        // Baidu share preview: /share/streaming with M3U8_* type parameter
        if ("pan.baidu.com".equalsIgnoreCase(parsed.host())
                && "/share/streaming".equals(parsed.encodedPath())) {
            String type = parsed.queryParameter("type");
            return type != null && type.startsWith("M3U8_");
        }
        // UC transcodes can use application/octet-stream, but file/download returns
        // progressive MP4 from another *.drive.uc.cn host. Never label every UC URL
        // as HLS: doing so makes Media3 parse MP4 bytes as an M3U manifest first.
        String host = parsed.host();
        String path = parsed.encodedPath();
        if (host != null && path != null
                && ("drive.uc.cn".equalsIgnoreCase(host)
                || host.toLowerCase(java.util.Locale.ROOT).endsWith(".drive.uc.cn"))
                && path.toLowerCase(java.util.Locale.ROOT).endsWith(".m3u8")) return true;
        return false;
    }

    private static String size(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return (bytes / 1024L) + " KB";
        if (bytes < 1024L * 1024L * 1024L) return (bytes / (1024L * 1024L)) + " MB";
        return (bytes / (1024L * 1024L * 1024L)) + " GB";
    }

    private static String playUrl(List<Flag> flags) {
        List<String> sources = new ArrayList<>();
        for (Flag flag : flags) {
            List<String> episodes = new ArrayList<>();
            for (Episode episode : flag.getEpisodes()) {
                String name = episode.getName().replace('$', ' ').replace('#', ' ');
                episodes.add(name + "$" + episode.getUrl());
            }
            sources.add(String.join("#", episodes));
        }
        return String.join("$$$", sources);
    }

    private static String json(Object value) {
        return GSON.toJson(value);
    }

    @SuppressWarnings("unchecked")
    private static <K, V> LinkedHashMap<K, V> map(Object... entries) {
        LinkedHashMap<K, V> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((K) entries[index], (V) entries[index + 1]);
        }
        return result;
    }

    private record ResourceId(String providerId, String itemId) {
    }

    private record ShareTarget(String providerId, String canonicalUrl) {
    }
}
