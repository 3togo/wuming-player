package com.wuming.android.tv.cloud.opendrive;

import com.wuming.tv.core.cloud.*;
import com.wuming.tv.core.media.MediaFileClassifier;

import com.wuming.tv.core.playback.OpenDrivePlaybackHints;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;

/** Aliyun Drive adapter that talks only to Aliyun/Alipan first-party endpoints. */
final class AliPanOpenDriveProvider implements OpenDriveProvider {

    AliPanOpenDriveProvider() {
        this(new com.wuming.tv.core.runtime.cloudlogin.AliPanCloudAccountProvider(
                new AndroidCloudCredentialStore(), new AndroidCloudLoginTransport()));
    }

    AliPanOpenDriveProvider(CloudAccountProvider loginDelegate) {
        this(loginDelegate, AliPanOpenDriveProvider::postNetworkJson);
    }

    AliPanOpenDriveProvider(AliTransport transport) {
        this(new com.wuming.tv.core.runtime.cloudlogin.AliPanCloudAccountProvider(
                new AndroidCloudCredentialStore(), new AndroidCloudLoginTransport()), transport);
    }

    AliPanOpenDriveProvider(CloudAccountProvider loginDelegate, AliTransport transport) {
        this(loginDelegate, transport, () -> OpenDriveTokenStore.get("alipan"),
                value -> OpenDriveTokenStore.put("alipan", value));
    }

    AliPanOpenDriveProvider(CloudAccountProvider loginDelegate, AliTransport transport,
            java.util.function.Supplier<String> readCredential,
            java.util.function.Consumer<String> writeCredential) {
        this(loginDelegate, transport, readCredential, writeCredential, null);
    }

    AliPanOpenDriveProvider(CloudAccountProvider loginDelegate, AliTransport transport,
            java.util.function.Supplier<String> readCredential,
            java.util.function.Consumer<String> writeCredential, AliSharedOriginalCache.Store copyStore) {
        this.loginDelegate = loginDelegate;
        this.transport = transport;
        this.readCredential = readCredential;
        this.writeCredential = writeCredential;
        AliSharedOriginalCache.Requester requester =
                (path, body, shareToken) -> postAccountJson(path, body, shareToken, true);
        originalCache = copyStore == null ? new AliSharedOriginalCache(requester)
                : new AliSharedOriginalCache(requester, copyStore, System::currentTimeMillis);
    }

    private static final String API = "https://api.aliyundrive.com/";
    private static final String AUTH = "https://auth.aliyundrive.com/v2/account/token";
    private final OpenDriveFileHandles<FileRef> files = new OpenDriveFileHandles<>(id(), FileRef.class);
    private final CloudAccountProvider loginDelegate;
    private final AliTransport transport;
    private final java.util.function.Supplier<String> readCredential;
    private final java.util.function.Consumer<String> writeCredential;
    private volatile Token token;
    private final AliSharedOriginalCache originalCache;

    @Override
    public String id() {
        return "alipan";
    }

    @Override
    public String displayName() {
        return "Aliyun Drive";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String availabilityMessage() {
        return "";
    }

    @Override
    public boolean authenticated() {
        return loginDelegate.authenticated();
    }

    @Override
    public boolean supportsQrLogin() {
        return true;
    }

    @Override
    public OpenDriveLoginChallenge beginLogin() throws Exception {
        return loginDelegate.beginLogin();
    }

    @Override
    public OpenDriveLoginState pollLogin(String sessionId) throws Exception {
        return loginDelegate.pollLogin(sessionId);
    }

    @Override
    public List<OpenDriveItem> list(String parentId) throws Exception {
        Token auth = accessToken();
        String parent = "root".equals(parentId) ? "root" : requireFile(parentId).fileId;
        JsonObject body = OpenDriveHttp.json("drive_id", auth.driveId, "parent_file_id", parent,
                "limit", 200, "order_by", "name", "order_direction", "ASC");
        JsonArray values = OpenDriveHttp.array(postAccountJson("adrive/v3/file/list", body, "", true), "items");
        return items(values, auth.driveId, null);
    }

    @Override
    public boolean supportsShare(String url) {
        return share(url) != null;
    }

    @Override
    public List<OpenDriveItem> listShare(String url) throws Exception {
        ShareLink link = share(url);
        if (link == null) throw new IllegalArgumentException("Invalid Aliyun Drive share link");
        JsonObject shareTokenRoot = transport.postJson(API + "v2/share_link/get_share_token",
                OpenDriveHttp.json("share_id", link.shareId, "share_pwd", link.passcode), publicHeaders());
        String shareToken = OpenDriveHttp.string(shareTokenRoot, "share_token");
        if (shareToken.isBlank()) throw new IOException("Aliyun Drive rejected the share link");

        String rootFolder = link.folderId;
        if (rootFolder.isBlank()) {
            JsonObject meta = transport.postJson(API + "adrive/v3/share_link/get_share_by_anonymous",
                    OpenDriveHttp.json("share_id", link.shareId), publicHeaders());
            JsonArray infos = OpenDriveHttp.array(meta, "file_infos");
            rootFolder = !infos.isEmpty() && infos.get(0).isJsonObject()
                    && "folder".equals(OpenDriveHttp.string(infos.get(0).getAsJsonObject(), "type"))
                    ? OpenDriveHttp.string(infos.get(0).getAsJsonObject(), "file_id") : "root";
        }

        List<OpenDriveItem> result = new ArrayList<>();
        ArrayDeque<FolderRef> folders = new ArrayDeque<>();
        folders.add(new FolderRef(rootFolder.isBlank() ? "root" : rootFolder, 0));
        int visited = 0;
        while (!folders.isEmpty()) {
            FolderRef folder = folders.removeFirst();
            if (++visited > 256) throw new IOException("Aliyun Drive share contains too many folders");
            String marker = "";
            for (int page = 0; page < 20; page++) {
                JsonObject body = OpenDriveHttp.json("share_id", link.shareId,
                        "parent_file_id", folder.fileId, "limit", 200,
                        "order_by", "name", "order_direction", "ASC");
                if (!marker.isBlank()) body.addProperty("marker", marker);
                JsonObject root = postAccountJson("adrive/v3/file/list", body, shareToken, false);
                JsonArray values = OpenDriveHttp.array(root, "items");
                for (JsonElement value : values) {
                    if (!value.isJsonObject()) continue;
                    JsonObject item = value.getAsJsonObject();
                    if ("folder".equals(OpenDriveHttp.string(item, "type"))) {
                        if (folder.depth < 20) folders.addLast(new FolderRef(OpenDriveHttp.required(item, "file_id", "Aliyun Drive"), folder.depth + 1));
                    } else if (OpenDriveHttp.isVideo(item, "category", "name")) {
                        long size = OpenDriveHttp.number(item, "size");
                        FileRef ref = new FileRef(OpenDriveHttp.required(item, "file_id", "Aliyun Drive"), "", link.shareId,
                                shareToken, false, size);
                        result.add(new OpenDriveItem(remember(ref), OpenDriveHttp.name(item, "name"), false,
                                size));
                    }
                }
                marker = OpenDriveHttp.string(root, "next_marker");
                if (marker.isBlank()) break;
            }
        }
        return result;
    }

    @Override
    public OpenDrivePreparedExternal prepareExternal(OpenDriveExternalPayload payload) {
        JsonObject item = payload.json(false);
        if (!ExternalEpisodePayload.belongsTo(payload.flag(), item, "alipan", "aliyun", "阿里")) return null;
        String shareId;
        String fileId;
        String shareToken = "";
        if (item != null) {
            shareId = ExternalEpisodePayload.first(item, "share_id", "shareId", "sId");
            fileId = ExternalEpisodePayload.first(item, "file_id", "fileId", "fid");
            shareToken = ExternalEpisodePayload.first(item, "share_token", "shareToken", "sToken");
        } else {
            String[] values = ExternalEpisodePayload.split(payload.raw(), "\\+", 2);
            if (values == null) return null;
            shareId = values[0];
            fileId = values[1];
        }
        if (!ExternalEpisodePayload.identifier(shareId)
                || !ExternalEpisodePayload.identifier(fileId)
                || (!shareToken.isBlank() && !ExternalEpisodePayload.capability(shareToken))) return null;
        FileRef file = new FileRef(fileId, "", shareId, shareToken,
                smartPlayback(payload.flag(), item), 0L);
        return OpenDrivePreparedExternal.of(() -> remember(file));
    }

    static boolean smartPlayback(String flag, JsonObject item) {
        return smartPlayback(flag) || smartPlayback(ExternalEpisodePayload.first(
                item, "flag", "quality", "qtype"));
    }

    static boolean smartPlayback(String flag) {
        if (flag == null) return false;
        String value = flag.toLowerCase(Locale.ROOT);
        return value.contains("阿里智") || value.contains("ali智")
                || value.contains("aliyun智") || value.contains("alipan智")
                || value.contains("ali smart") || value.contains("aliyun smart")
                || value.contains("alipan smart") || value.equals("smart")
                || value.equals("preview") || value.equals("live_transcoding");
    }

    @Override
    public OpenDrivePlayback resolve(String itemId) throws Exception {
        FileRef file = requireFile(itemId);
        JsonObject body;
        boolean shared = !file.shareId.isBlank();
        String shareToken = file.shareToken;
        if (shared) {
            if (shareToken.isBlank()) {
                JsonObject token = transport.postJson(API + "v2/share_link/get_share_token",
                        OpenDriveHttp.json("share_id", file.shareId, "share_pwd", ""), publicHeaders());
                shareToken = OpenDriveHttp.string(token, "share_token");
                if (shareToken.isBlank()) {
                    OpenDriveLog.resolveInvalid(id(), itemId, "share_token_blank", "Aliyun Drive rejected the shared file");
                    throw new IOException("Aliyun Drive rejected the shared file");
                }
            }
        }

        String endpoint;
        if (file.smart) {
            // Ali original and smart are separate source contracts. Smart uses the
            // documented preview API and must not silently resolve the original file.
            // Use the share-specific preview contract for public files. Listing
            // success does not establish that the personal preview contract is playable.
            endpoint = shared ? "v2/file/get_share_link_video_preview_play_info"
                    : "v2/file/get_video_preview_play_info";
            body = previewBody(file.driveId, file.shareId, file.fileId);
        } else {
            // The old share-link download endpoint returns HTTP 410. The PDS
            // get_download_url contract accepts share_id + x-share-token as well as
            // personal drive_id. Keep original on this download API, never preview.
            endpoint = "v2/file/get_download_url";
            body = shared
                    ? OpenDriveHttp.json("share_id", file.shareId, "file_id", file.fileId,
                    "expire_sec", 900)
                    : OpenDriveHttp.json("drive_id", file.driveId, "file_id", file.fileId,
                    "expire_sec", 900);
        }
        boolean savedOriginalFallback = false;
        JsonObject root;
        try {
            root = postAccountJson(endpoint, body, shareToken, !shared);
        } catch (OpenDriveHttp.StatusException error) {
            if (!shared || file.smart || error.code() != 403) throw error;
            OpenDriveLog.fallback(id(), itemId, "shared-original", "saved-copy", "403-forbidden");
            root = originalCache.resolve(accessToken().driveId, file.shareId,
                    file.fileId, shareToken, file.size);
            savedOriginalFallback = true;
        }
        String url = file.smart ? previewUrl(root) : downloadUrl(root, shared && !savedOriginalFallback);
        if (!OpenDriveHttp.validHttpsUrl(url)) {
            OpenDriveLog.resolveInvalid(id(), itemId, file.smart ? "smart_url_invalid" : "original_url_invalid", "");
            throw new com.wuming.tv.core.cloud.OpenDriveFailure(id(), file.smart
                    ? com.wuming.tv.core.cloud.OpenDriveFailure.Reason.TRANSCODE_UNAVAILABLE
                    : com.wuming.tv.core.cloud.OpenDriveFailure.Reason.PROVIDER_ERROR);
        }
        Map<String, String> baseHeaders = Map.of("Referer", "https://www.alipan.com/",
                "User-Agent", OpenDriveHttp.USER_AGENT);
        Map<String, String> playbackHeaders = file.smart
                ? OpenDrivePlaybackHints.hls(baseHeaders, false)
                : OpenDrivePlaybackHints.parallelRanges(baseHeaders,
                OpenDrivePlaybackHints.PROFILE_ALIPAN, file.size);
        String copyLease = OpenDriveHttp.string(root, "__copy_lease");
        if (!copyLease.isBlank()) {
            playbackHeaders = new java.util.LinkedHashMap<>(playbackHeaders);
            playbackHeaders.put(OpenDrivePlaybackHints.COPY_LEASE, copyLease);
        }
        if (savedOriginalFallback) playbackHeaders = OpenDrivePlaybackHints.fallback(playbackHeaders,
                OpenDrivePlaybackHints.FallbackReason.ALI_SHARED_ORIGINAL_FORBIDDEN);
        return new OpenDrivePlayback(url, playbackHeaders,
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10));
    }

    Set<String> createdCopyIds() { return originalCache.createdIds(); }

    void cleanupSaved(String fileId) throws Exception {
        originalCache.cleanupCreated(accessToken().driveId, fileId);
    }

    boolean deleteTracked(com.wuming.android.tv.cloud.CloudCacheRecord record) throws Exception {
        return originalCache.deleteTracked(accessToken().driveId, record);
    }

    static JsonObject previewBody(String driveId, String shareId, String fileId) {
        JsonObject body = OpenDriveHttp.json("file_id", fileId,
                "category", "live_transcoding", "url_expire_sec", 900);
        if (shareId == null || shareId.isBlank()) body.addProperty("drive_id", driveId);
        else {
            body.addProperty("share_id", shareId);
            body.addProperty("get_preview_url", true);
            body.addProperty("template_id", "");
        }
        return body;
    }

    static String previewUrl(JsonObject root) {
        JsonObject info = OpenDriveHttp.object(root, "video_preview_play_info");
        if (info.size() == 0) info = OpenDriveHttp.object(root, "video_preview_play_meta");
        JsonElement tasksValue = info.get("live_transcoding_task_list");
        JsonArray tasks = tasksValue != null && tasksValue.isJsonArray()
                ? tasksValue.getAsJsonArray() : new JsonArray();
        String selected = "";
        int selectedRank = -1;
        for (JsonElement value : tasks) {
            if (!value.isJsonObject()) continue;
            JsonObject task = value.getAsJsonObject();
            if (!"finished".equalsIgnoreCase(OpenDriveHttp.string(task, "status"))) continue;
            String url = OpenDriveHttp.string(task, "url");
            // Shared previews expose preview_url with SD/HD/FHD template IDs.
            // Ignoring either field made finished public-share renditions look absent.
            if (!OpenDriveHttp.validHttpsUrl(url)) url = OpenDriveHttp.string(task, "preview_url");
            if (OpenDriveHttp.number(task, "template_width") > 1920
                    || OpenDriveHttp.number(task, "template_height") > 1080) continue;
            int rank = previewRank(OpenDriveHttp.string(task, "template_id"));
            if (!OpenDriveHttp.validHttpsUrl(url) || rank < 0 || rank <= selectedRank) continue;
            selected = url;
            selectedRank = rank;
        }
        return selected;
    }

    private static int previewRank(String templateId) {
        String value = templateId == null ? "" : templateId.toLowerCase(Locale.ROOT);
        // Smart is bounded at 1080p. A 4K rendition remains an original-quality choice.
        if (value.contains("2160") || value.contains("4k")) return -1;
        if (value.equals("fhd")) return 3;
        if (value.equals("hd")) return 2;
        if (value.equals("sd")) return 1;
        if (value.equals("ld")) return 0;
        if (value.contains("1080")) return 3;
        if (value.contains("720")) return 2;
        if (value.contains("480")) return 1;
        if (value.contains("360")) return 0;
        return -1;
    }

    @Override
    public void credentialsRestored() {
        files.clear();
        token = null;
    }

    @Override
    public void logout() throws Exception {
        loginDelegate.logout();
        files.clearPersisted();
        token = null;
    }

    static String canonicalShareUrl(String value) {
        ShareLink link = share(value);
        if (link == null) return null;
        String canonical = "https://www.alipan.com/s/" + link.shareId
                + (link.folderId.isBlank() ? "" : "/folder/" + link.folderId);
        if (link.passcode.isBlank()) return canonical;
        HttpUrl parsed = HttpUrl.parse(canonical);
        return parsed == null ? null : parsed.newBuilder()
                .addQueryParameter("pwd", link.passcode).build().toString();
    }

    static String sharePasscode(String value) {
        return AbstractOpenDriveProvider.sharePasscode(value, "Aliyun Drive");
    }

    static String downloadUrl(JsonObject root, boolean shared) {
        String primary = OpenDriveHttp.string(root, shared ? "download_url" : "url");
        if (OpenDriveHttp.validHttpsUrl(primary)) return primary;
        return OpenDriveHttp.string(root, shared ? "url" : "download_url");
    }

    private static JsonObject postNetworkJson(String url, JsonObject body, Map<String, String> headers)
            throws Exception {
        return (API + "v2/recyclebin/trash").equals(url)
                ? OpenDriveHttp.postJsonAllowNoContent(url, body, headers)
                : OpenDriveHttp.postJson(url, body, headers);
    }

    private JsonObject postAccountJson(String endpoint, JsonObject body,
            String shareToken, boolean accountRequired) throws Exception {
        // Public-share paths used loadToken() and kept sending expired credentials.
        // Refresh before use, and retry a rejected account token once (clock skew or
        // early invalidation). Never turn 403/410 or a second 401 into a retry loop.
        Token auth = accountRequired || loadToken().valid() ? accessToken() : null;
        try {
            return transport.postJson(API + endpoint, body, headers(auth, shareToken));
        } catch (OpenDriveHttp.StatusException error) {
            if (error.code() != 401 || auth == null) throw error;
            return transport.postJson(API + endpoint, body,
                    headers(accessToken(true), shareToken));
        }
    }

    private Token accessToken() throws Exception {
        return accessToken(false);
    }

    private synchronized Token accessToken(boolean force) throws Exception {
        Token current = loadToken();
        if (!current.valid()) throw new IllegalStateException(
                "Open Settings > Cloud storage > Aliyun Drive and sign in first");
        if (!force && !current.expiring()) return current;
        JsonObject refreshed = transport.postJson(AUTH,
                OpenDriveHttp.json("refresh_token", current.refreshToken,
                        "grant_type", "refresh_token"), publicHeaders());
        // Persist an absolute deadline. Reinterpreting expires_in on every process
        // start otherwise makes an old token appear fresh indefinitely.
        if (OpenDriveHttp.string(refreshed, "expire_time").isBlank()) {
            long seconds = OpenDriveHttp.number(refreshed, "expires_in");
            if (seconds > 0 && seconds <= TimeUnit.DAYS.toSeconds(30))
                refreshed.addProperty("expire_time", java.time.Instant.now().plusSeconds(seconds).toString());
        }
        Token next = Token.parse(refreshed.toString());
        if (!next.valid()) throw new IOException("Aliyun Drive login expired");
        token = next;
        writeCredential.accept(refreshed.toString());
        return next;
    }

    private Token loadToken() {
        String stored = readCredential.get();
        if (token == null || !stored.equals(token.raw)) token = Token.parse(stored);
        return token;
    }

    private List<OpenDriveItem> items(JsonArray values, String driveId, ShareLink share) {
        List<OpenDriveItem> result = new ArrayList<>();
        for (JsonElement value : values) {
            if (!value.isJsonObject()) continue;
            JsonObject item = value.getAsJsonObject();
            boolean directory = "folder".equals(OpenDriveHttp.string(item, "type"));
            if (!directory && !OpenDriveHttp.isVideo(item, "category", "name")) continue;
            long size = OpenDriveHttp.number(item, "size");
            FileRef ref = new FileRef(OpenDriveHttp.required(item, "file_id", "Aliyun Drive"), driveId,
                    share == null ? "" : share.shareId, "", false, size);
            result.add(new OpenDriveItem(remember(ref), OpenDriveHttp.name(item, "name"), directory,
                    size));
        }
        return result;
    }

    private static Map<String, String> headers(Token token, String shareToken) {
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>(publicHeaders());
        if (token != null && token.valid()) values.put("Authorization", token.tokenType + " " + token.accessToken);
        if (!shareToken.isBlank()) values.put("x-share-token", shareToken);
        values.put("X-Canary", "client=Android,app=adrive,version=v4.3.1");
        return values;
    }

    private static Map<String, String> publicHeaders() {
        return Map.of("Content-Type", "application/json", "Referer", "https://www.alipan.com/");
    }

    private String remember(FileRef file) {
        return files.remember(file);
    }

    @Override
    public boolean persistHandle(String handle) {
        return files.persist(handle);
    }

    private FileRef requireFile(String handle) {
        return files.require(handle);
    }

    private static ShareLink share(String value) {
        if (value == null || value.length() > 2048) return null;
        try {
            URI uri = URI.create(value.trim());
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || uri.getPort() != -1 || host == null
                    || !(host.equalsIgnoreCase("www.alipan.com")
                    || host.equalsIgnoreCase("www.aliyundrive.com"))) return null;
            String[] parts = uri.getPath().split("/");
            if (parts.length < 3 || !"s".equals(parts[1])
                    || !parts[2].matches("[A-Za-z0-9_-]{6,128}")) return null;
            String folder = parts.length >= 5 && "folder".equals(parts[3])
                    && parts[4].matches("[A-Za-z0-9_-]{4,256}") ? parts[4] : "";
            return new ShareLink(parts[2], folder, sharePasscode(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private record FileRef(String fileId, String driveId, String shareId, String shareToken,
                           boolean smart, long size) {
    }

    private record FolderRef(String fileId, int depth) {
    }

    private record ShareLink(String shareId, String folderId, String passcode) {
    }

    private record Token(String raw, String tokenType, String accessToken, String refreshToken,
                         String driveId, long expiresAt) {
        static Token parse(String raw) {
            try {
                JsonObject value = JsonParser.parseString(raw).getAsJsonObject();
                long expiresAt = 0L;
                String expireTime = OpenDriveHttp.string(value, "expire_time");
                if (!expireTime.isBlank()) {
                    try {
                        expiresAt = java.time.Instant.parse(expireTime).toEpochMilli();
                    } catch (Exception ignored) {
                    }
                }
                String drive = OpenDriveHttp.string(value, "default_drive_id");
                if (drive.isBlank()) drive = OpenDriveHttp.string(value, "resource_drive_id");
                return new Token(raw, OpenDriveHttp.string(value, "token_type"),
                        OpenDriveHttp.string(value, "access_token"),
                        OpenDriveHttp.string(value, "refresh_token"), drive, expiresAt);
            } catch (Exception ignored) {
                return new Token(raw == null ? "" : raw, "", "", "", "", 0L);
            }
        }

        boolean valid() {
            return accessToken.length() >= 20 && refreshToken.length() >= 20 && !driveId.isBlank();
        }

        boolean expiring() {
            return expiresAt == 0L || expiresAt - System.currentTimeMillis() < TimeUnit.MINUTES.toMillis(5);
        }
    }

    interface AliTransport {
        JsonObject postJson(String url, JsonObject body, Map<String, String> headers)
                throws Exception;
    }
}
