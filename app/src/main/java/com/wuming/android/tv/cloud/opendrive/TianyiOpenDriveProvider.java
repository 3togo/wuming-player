package com.wuming.android.tv.cloud.opendrive;

import com.wuming.tv.core.cloud.*;
import com.wuming.tv.core.media.MediaFileClassifier;

import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.server.Server;
import com.github.catvod.net.PersistentCookieJar;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Cookie;
import okhttp3.HttpUrl;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Tianyi Cloud adapter using only first-party cloud.189.cn endpoints and cookies. */
final class TianyiOpenDriveProvider implements OpenDriveProvider {

    private static final String HOST = "https://cloud.189.cn";
    private static final String LOGIN = HOST + "/web/login.html";
    private static final String SHARE_API = HOST + "/api/open/share/";
    private static final int PAGE_SIZE = 200;
    private static final int MAX_PAGES = 20;
    private static final int MAX_DIRECTORIES = 256;
    private static final long STREAM_CACHE_MILLIS = TimeUnit.MINUTES.toMillis(15);

    private final OpenDriveFileHandles<FileRef> files = new OpenDriveFileHandles<>(id(), FileRef.class);
    private final OpenDriveMemoryCache<String, StreamRef> streams =
            new OpenDriveMemoryCache<>(OpenDriveHandleVault.MAX_HANDLES);
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "TianyiOpenDriveCleanup");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean cleanupScheduled = new AtomicBoolean();

    @Override
    public String id() {
        return "tianyi";
    }

    @Override
    public String displayName() {
        return "Tianyi Cloud";
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
        try {
            return PersistentCookieJar.get().hasLoginCookies(HOST + "/");
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    @Override
    public OpenDriveLoginChallenge beginLogin() {
        return new OpenDriveLoginChallenge(UUID.randomUUID().toString(), LOGIN,
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(15));
    }

    @Override
    public OpenDriveLoginState pollLogin(String sessionId) {
        return authenticated() ? OpenDriveLoginState.CONFIRMED : OpenDriveLoginState.WAITING;
    }

    @Override
    public List<OpenDriveItem> list(String parentId) throws Exception {
        String folderId = "root".equals(parentId) ? "-11" : requireFile(parentId).fileId;
        List<OpenDriveItem> result = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonObject root = OpenDriveHttp.get(HOST + "/api/open/file/listFiles.action", Map.of(
                    "pageSize", Integer.toString(PAGE_SIZE), "pageNum", Integer.toString(page),
                    "mediaType", "0", "folderId", folderId, "iconOption", "5",
                    "orderBy", "lastOpTime", "descending", "true"), headers());
            ensureSuccess(root, "Tianyi Cloud account listing failed");
            JsonObject list = OpenDriveHttp.object(root, "fileListAO");
            JsonArray folders = OpenDriveHttp.array(list, "folderList");
            JsonArray files = OpenDriveHttp.array(list, "fileList");
            addPersonal(result, folders, true);
            addPersonal(result, files, false);
            if (folders.size() + files.size() < PAGE_SIZE) break;
            if (page == MAX_PAGES) throw new IOException("Tianyi Cloud folder contains too many files");
        }
        return result;
    }

    @Override
    public boolean supportsShare(String url) {
        return share(url) != null;
    }

    @Override
    public List<OpenDriveItem> listShare(String url) throws Exception {
        ShareLink link = share(url);
        if (link == null) throw new IllegalArgumentException("Invalid Tianyi Cloud share link");
        JsonObject info = OpenDriveHttp.get(SHARE_API + "getShareInfoByCodeV2.action", Map.of(
                "shareCode", link.shareCode, "noCache", "0." + System.currentTimeMillis()), headers());
        ensureSuccess(info, "Tianyi Cloud rejected the share link");
        String rootId = value(info, "fileId");
        String shareId = shareId(info);
        int shareMode = (int) OpenDriveHttp.number(info, "shareMode");
        boolean folder = OpenDriveHttp.bool(info, "isFolder");
        if (!ExternalEpisodePayload.identifier(shareId) || !ExternalEpisodePayload.identifier(rootId)) {
            throw new IOException("Tianyi Cloud returned incomplete share metadata");
        }

        List<OpenDriveItem> result = new ArrayList<>();
        ArrayDeque<FolderRef> folders = new ArrayDeque<>();
        folders.add(new FolderRef(rootId, 0));
        int visited = 0;
        while (!folders.isEmpty()) {
            FolderRef current = folders.removeFirst();
            if (++visited > MAX_DIRECTORIES) throw new IOException("Tianyi Cloud share contains too many folders");
            for (int page = 1; page <= MAX_PAGES; page++) {
                Map<String, String> query = new LinkedHashMap<>();
                query.put("pageNum", Integer.toString(page));
                query.put("pageSize", Integer.toString(PAGE_SIZE));
                query.put("fileId", current.fileId);
                query.put("shareDirFileId", current.fileId);
                query.put("isFolder", Boolean.toString(folder));
                query.put("shareId", shareId);
                query.put("shareMode", Integer.toString(shareMode));
                query.put("iconOption", "5");
                query.put("orderBy", "filename");
                query.put("descending", "false");
                query.put("accessCode", link.accessCode);
                JsonObject root = OpenDriveHttp.get(SHARE_API + "listShareDir.action", query, headers());
                ensureSuccess(root, "Tianyi Cloud share listing failed");
                JsonObject list = OpenDriveHttp.object(root, "fileListAO");
                JsonArray fileValues = OpenDriveHttp.array(list, "fileList");
                JsonArray folderValues = OpenDriveHttp.array(list, "folderList");
                for (JsonElement value : fileValues) {
                    if (!value.isJsonObject()) continue;
                    JsonObject item = value.getAsJsonObject();
                    String name = OpenDriveHttp.name(item, "name");
                    if (OpenDriveHttp.number(item, "mediaType") != 3 && !isVideo(name)) continue;
                    FileRef file = new FileRef(requiredId(item), shareId, false);
                    result.add(new OpenDriveItem(remember(file), name, false,
                            OpenDriveHttp.number(item, "size")));
                }
                for (JsonElement value : folderValues) {
                    if (!value.isJsonObject() || current.depth >= 20) continue;
                    folders.addLast(new FolderRef(requiredId(value.getAsJsonObject()), current.depth + 1));
                }
                if (fileValues.size() + folderValues.size() < PAGE_SIZE) break;
                if (page == MAX_PAGES) throw new IOException("Tianyi Cloud share contains too many files");
            }
        }
        return result;
    }

    @Override
    public OpenDrivePreparedExternal prepareExternal(OpenDriveExternalPayload payload) {
        JsonObject item = payload.json(false);
        if (!ExternalEpisodePayload.belongsTo(payload.flag(), item,
                "tianyi", "天翼", "天意", "189云", "189盘")) {
            return null;
        }
        String fileId;
        String shareId;
        if (item != null) {
            fileId = ExternalEpisodePayload.first(item, "fileId", "file_id", "fid", "id");
            shareId = ExternalEpisodePayload.first(item, "shareId", "share_id", "sId");
        } else {
            String[] values = ExternalEpisodePayload.split(payload.raw(), "\\+\\+", 2);
            if (values == null) return null;
            fileId = values[0];
            shareId = values[1];
        }
        if (!ExternalEpisodePayload.identifier(fileId) || !ExternalEpisodePayload.identifier(shareId)) return null;
        FileRef file = new FileRef(fileId, shareId, false);
        return OpenDrivePreparedExternal.of(() -> remember(file));
    }

    @Override
    public OpenDrivePlayback resolve(String itemId) throws Exception {
        FileRef file = requireFile(itemId);
        JsonObject root;
        if (file.shareId.isBlank()) {
            root = OpenDriveHttp.get(HOST + "/api/portal/getFileInfo.action",
                    Map.of("fileId", file.fileId), headers());
        } else {
            root = OpenDriveHttp.get(HOST + "/api/portal/getNewVlcVideoPlayUrl.action", Map.of(
                    "shareId", file.shareId, "dt", "1", "fileId", file.fileId,
                    "type", "4", "key", "noCache"), headers());
        }
        ensureSuccess(root, "Tianyi Cloud could not authorize playback");
        String url = firstUrl(root);
        if (url.startsWith("//")) url = "https:" + url;
        else if (url.startsWith("http://")) url = "https://" + url.substring(7);
        if (!OpenDriveHttp.validHttpsUrl(url)) {
            OpenDriveLog.resolveInvalid(id(), itemId, "url_invalid", "");
            throw new IOException("Tianyi Cloud returned an invalid playback URL");
        }

        String stream = UUID.randomUUID().toString();
        streams.put(stream, new StreamRef(url));
        scheduleCleanup();
        String localUrl = HttpUrl.parse(Server.get().getAddress("/proxy")).newBuilder()
                .addQueryParameter("siteKey", VodConfig.OPEN_DRIVE_KEY)
                .addQueryParameter("provider", id())
                .addQueryParameter("stream", stream)
                .build().toString();
        return new OpenDrivePlayback(localUrl, Collections.emptyMap(),
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10));
    }

    @Override
    public OpenDriveProxyResponse proxy(Map<String, String> params) throws Exception {
        String handle = params.get("stream");
        StreamRef stream = handle == null ? null : streams.get(handle);
        if (stream == null) throw new IllegalArgumentException("Playback session expired; reopen the share");
        stream.touch();
        Response response = OpenDriveHttp.open(stream.url, params.get("range"), headers());
        ResponseBody body = response.body();
        if (!response.isSuccessful() || body == null) {
            response.close();
            throw new IOException("Tianyi Cloud playback request failed");
        }
        String mime = body.contentType() == null ? "application/octet-stream" : body.contentType().toString();
        Map<String, String> responseHeaders = OpenDriveProxyResponse.mediaHeaders(
                response.header("Content-Range"), body.contentLength());
        return new OpenDriveProxyResponse(response.code(), mime,
                new ResponseStream(body.byteStream(), response), responseHeaders);
    }

    @Override
    public void credentialsRestored() {
        files.clear();
        streams.clear();
    }

    @Override
    public void logout() {
        files.clearPersisted();
        streams.clear();
        try {
            PersistentCookieJar.get().clear("189.cn");
            PersistentCookieJar.get().clearWebView(HOST + "/");
        } catch (IllegalStateException ignored) {
        }
    }

    @Override
    public void shutdown() {
        cleanupExecutor.shutdownNow();
    }

    static String canonicalShareUrl(String value) {
        ShareLink link = share(value);
        if (link == null) return null;
        return HOST + "/web/share?code=" + link.shareCode
                + (link.accessCode.isBlank() ? "" : "&accessCode=" + link.accessCode);
    }

    static String shareId(JsonObject info) {
        String shareId = value(info, "shareId");
        return shareId.isBlank() ? value(info, "fileId") : shareId;
    }

    private void addPersonal(List<OpenDriveItem> result, JsonArray values, boolean directory) {
        for (JsonElement value : values) {
            if (!value.isJsonObject()) continue;
            JsonObject item = value.getAsJsonObject();
            String name = OpenDriveHttp.name(item, "name");
            if (!directory && !isVideo(name)) continue;
            result.add(new OpenDriveItem(remember(new FileRef(requiredId(item), "", true)),
                    name, directory, OpenDriveHttp.number(item, "size")));
        }
    }

    private Map<String, String> headers() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("Referer", HOST + "/");
        values.put("Accept", "application/json;charset=UTF-8");
        String cookie = cookieHeader();
        if (!cookie.isBlank()) values.put("Cookie", cookie);
        return values;
    }

    private static String cookieHeader() {
        try {
            HttpUrl url = HttpUrl.parse(HOST + "/");
            StringBuilder result = new StringBuilder();
            for (Cookie cookie : PersistentCookieJar.get().loadForRequest(url)) {
                if (result.length() > 0) result.append("; ");
                result.append(cookie.name()).append('=').append(cookie.value());
            }
            return result.toString();
        } catch (Exception ignored) {
            return "";
        }
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

    private void scheduleCleanup() {
        if (!cleanupScheduled.compareAndSet(false, true)) return;
        cleanupExecutor.schedule(this::cleanupStreams, STREAM_CACHE_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void cleanupStreams() {
        try {
            long cutoff = System.currentTimeMillis() - STREAM_CACHE_MILLIS;
            streams.removeEntriesIf((handle, stream) -> stream.lastAccess < cutoff);
        } finally {
            cleanupScheduled.set(false);
            if (!streams.isEmpty()) scheduleCleanup();
        }
    }

    private static void ensureSuccess(JsonObject root, String message) throws IOException {
        if (root.has("res_code") && OpenDriveHttp.number(root, "res_code") != 0) throw new IOException(message);
        if (!OpenDriveHttp.string(root, "errorCode").isBlank()) throw new IOException(message);
    }

    private static String value(JsonObject object, String key) {
        String value = OpenDriveHttp.string(object, key);
        if (!value.isBlank()) return value;
        return object != null && object.has(key) ? String.valueOf(OpenDriveHttp.number(object, key)) : "";
    }

    private static String requiredId(JsonObject item) {
        String id = value(item, "id");
        if (!ExternalEpisodePayload.identifier(id)) throw new IllegalArgumentException(
                "Tianyi Cloud response is missing file metadata");
        return id;
    }

    private static boolean isVideo(String name) {
        return MediaFileClassifier.isCloudVideoFileName(name);
    }

    private static String firstUrl(JsonObject root) {
        for (String key : new String[]{"downloadUrl", "fileDownloadUrl", "url"}) {
            String value = OpenDriveHttp.string(root, key);
            if (!value.isBlank()) return value;
        }
        JsonObject normal = OpenDriveHttp.object(root, "normal");
        return OpenDriveHttp.string(normal, "url");
    }

    private static ShareLink share(String value) {
        if (value == null || value.length() > 2048) return null;
        try {
            URI uri = URI.create(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || uri.getPort() != -1 || !"cloud.189.cn".equalsIgnoreCase(uri.getHost())) return null;
            String shareCode = "";
            String accessCode = "";
            String path = uri.getPath();
            if (path != null && path.matches("/t/[A-Za-z0-9_-]{4,128}/?")) {
                shareCode = path.substring(3).replace("/", "");
            }
            if (uri.getRawQuery() != null) {
                for (String part : uri.getRawQuery().split("&")) {
                    String[] pair = part.split("=", 2);
                    if (pair.length != 2) continue;
                    String decoded = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                    if ("code".equalsIgnoreCase(pair[0])) shareCode = decoded;
                    if ("accessCode".equalsIgnoreCase(pair[0]) || "pwd".equalsIgnoreCase(pair[0])) accessCode = decoded;
                }
            }
            if (!shareCode.matches("[A-Za-z0-9_-]{4,128}")
                    || !accessCode.matches("[A-Za-z0-9]{0,16}")) return null;
            return new ShareLink(shareCode, accessCode);
        } catch (Exception ignored) {
            return null;
        }
    }

    private record FileRef(String fileId, String shareId, boolean personal) {
    }

    private record FolderRef(String fileId, int depth) {
    }

    private record ShareLink(String shareCode, String accessCode) {
    }

    private static final class StreamRef {
        private final String url;
        private volatile long lastAccess = System.currentTimeMillis();

        private StreamRef(String url) {
            this.url = url;
        }

        private void touch() {
            lastAccess = System.currentTimeMillis();
        }
    }

    private static final class ResponseStream extends FilterInputStream {
        private final Response response;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ResponseStream(InputStream input, Response response) {
            super(input);
            this.response = response;
        }

        @Override
        public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) return;
            try {
                super.close();
            } finally {
                response.close();
            }
        }
    }
}
