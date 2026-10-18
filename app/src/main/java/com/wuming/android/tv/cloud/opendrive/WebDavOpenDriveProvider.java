package com.wuming.android.tv.cloud.opendrive;

import com.wuming.tv.core.cloud.*;

import com.wuming.tv.core.media.MediaFileClassifier;
import com.wuming.android.tv.utils.Hex;
import com.thegrizzlylabs.sardineandroid.DavResource;
import com.thegrizzlylabs.sardineandroid.Sardine;
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Bridges user-owned OpenList/WebDAV servers into the universal OpenDrive pipeline. */
final class WebDavOpenDriveProvider implements OpenDriveProvider {

    private static final String CONNECTOR_PREFIX = "connector.";
    private static final String ITEM_PREFIX = "item.";
    private static final String ALIST_PREFIX = "alist.";
    private static final int MAX_TRANSIENT_ITEMS = 2048;

    interface ConnectorSource {
        List<WebDavConnectorStore.Connector> list();

        WebDavConnectorStore.Connector get(int id);
    }

    interface DavClient {
        List<DavEntry> list(String url, String username, String password) throws Exception;
    }

    record DavEntry(URI href, String name, boolean directory, long size) {
    }

    private record ItemRef(int connectorId, String url, String name, long size) {
    }

    private final ConnectorSource connectors;
    private final DavClient davClient;
    private final AlistClient alistClient;
    private final Map<String, ItemRef> transientItems =
            com.wuming.tv.core.cache.BoundedLruMap.create(MAX_TRANSIENT_ITEMS);
    private final Map<String, AlistItem> alistItems =
            com.wuming.tv.core.cache.BoundedLruMap.create(MAX_TRANSIENT_ITEMS);
    private final Map<Integer, AlistSession> alistSessions = Collections.synchronizedMap(new LinkedHashMap<>());

    WebDavOpenDriveProvider() {
        WebDavConnectorStore store = new WebDavConnectorStore();
        this.connectors = new ConnectorSource() {
            @Override
            public List<WebDavConnectorStore.Connector> list() {
                return store.list();
            }

            @Override
            public WebDavConnectorStore.Connector get(int id) {
                return store.get(id);
            }
        };
        this.davClient = WebDavOpenDriveProvider::listWithSardine;
        this.alistClient = HttpAlistClient.INSTANCE;
    }

    WebDavOpenDriveProvider(ConnectorSource connectors, DavClient davClient, AlistClient alistClient) {
        this.connectors = connectors;
        this.davClient = davClient;
        this.alistClient = alistClient;
    }

    WebDavOpenDriveProvider(ConnectorSource connectors, DavClient davClient) {
        this(connectors, davClient, HttpAlistClient.INSTANCE);
    }

    @Override
    public String id() {
        return "webdav";
    }

    @Override
    public String displayName() {
        return "OpenList / WebDAV";
    }

    @Override
    public boolean available() {
        return WebDavConnectorFeature.isEnabled();
    }

    @Override
    public String availabilityMessage() {
        return "Pending — connector implementation is disabled";
    }

    @Override
    public boolean authenticated() {
        if (!WebDavConnectorFeature.isEnabled()) return false;
        try {
            return !connectors.list().isEmpty();
        } catch (RuntimeException unavailableStore) {
            return false;
        }
    }

    @Override
    public OpenDriveLoginChallenge beginLogin() {
        return new OpenDriveLoginChallenge(UUID.randomUUID().toString(),
                "tvbox://settings/webdav", System.currentTimeMillis() + 300_000L);
    }

    @Override
    public OpenDriveLoginState pollLogin(String sessionId) {
        return authenticated() ? OpenDriveLoginState.CONFIRMED : OpenDriveLoginState.WAITING;
    }

    @Override
    public List<OpenDriveItem> list(String parentId) throws Exception {
        if (parentId == null || parentId.isBlank() || "root".equals(parentId)) {
            List<OpenDriveItem> result = new ArrayList<>();
            for (WebDavConnectorStore.Connector connector : connectors.list()) {
                result.add(new OpenDriveItem(CONNECTOR_PREFIX + connector.id(), connector.name(), true, 0));
            }
            return result;
        }

        WebDavConnectorStore.Connector connector;
        URI requestUri;
        if (parentId.startsWith(CONNECTOR_PREFIX)) {
            connector = requireConnector(parseConnectorId(parentId));
            AlistSession session = alistSession(connector);
            if (session.alist) {
                return alistList(connector, session, initialAlistPath(connector));
            }
            requestUri = appendPath(URI.create(connector.baseUrl()), connector.initialPath());
        } else if (parentId.startsWith(ALIST_PREFIX)) {
            AlistItem folder = requireAlist(parentId);
            connector = requireConnector(folder.connectorId());
            return alistList(connector, alistSession(connector), folder.path());
        } else {
            ItemRef folder = requireItem(parentId);
            connector = requireConnector(folder.connectorId());
            requestUri = URI.create(folder.url());
        }

        List<OpenDriveItem> result = new ArrayList<>();
        for (DavEntry entry : davClient.list(requestUri.toASCIIString(),
                connector.username(), connector.password())) {
            URI resolved = requestUri.resolve(entry.href()).normalize();
            if (!withinConnector(URI.create(connector.baseUrl()), resolved)
                    || sameResource(requestUri, resolved)) continue;
            String name = entry.name() == null || entry.name().isBlank()
                    ? fileName(resolved) : entry.name();
            if (!entry.directory() && !isVideo(name)) continue;
            String handle = remember(new ItemRef(connector.id(), resolved.toASCIIString(),
                    name, Math.max(0, entry.size())));
            result.add(new OpenDriveItem(handle, name, entry.directory(), entry.size()));
        }
        return result;
    }

    @Override
    public OpenDrivePlayback resolve(String itemId) throws Exception {
        if (itemId != null && itemId.startsWith(ALIST_PREFIX)) {
            return alistResolve(itemId);
        }
        ItemRef item = requireItem(itemId);
        WebDavConnectorStore.Connector connector = requireConnector(item.connectorId());
        URI url = URI.create(item.url());
        if (!withinConnector(URI.create(connector.baseUrl()), url)) {
            OpenDriveLog.resolveInvalid(id(), itemId, "endpoint_escape", "WebDAV item escaped its configured endpoint");
            throw new IllegalStateException("WebDAV item escaped its configured endpoint");
        }
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", OpenDriveHttp.USER_AGENT);
        if (!connector.username().isEmpty() || !connector.password().isEmpty()) {
            String token = Base64.getEncoder().encodeToString(
                    (connector.username() + ":" + connector.password())
                            .getBytes(StandardCharsets.UTF_8));
            headers.put("Authorization", "Basic " + token);
        }
        return new OpenDrivePlayback(url.toASCIIString(), headers, 0);
    }

    @Override
    public boolean persistHandle(String itemId) {
        if (itemId != null && itemId.startsWith(ALIST_PREFIX)) {
            AlistItem item = alistItems.get(itemId);
            if (item == null) return false;
            OpenDriveHandleVault.put(id(), itemId, item);
            return true;
        }
        ItemRef item = transientItems.get(itemId);
        if (item == null) return false;
        OpenDriveHandleVault.put(id(), itemId, item);
        return true;
    }

    @Override
    public void logout() {
        // Connectors are explicitly managed in Settings; a generic logout must not delete them.
    }

    @Override
    public boolean supportsShare(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("/s/") || lower.contains("/share/");
    }

    @Override
    public List<OpenDriveItem> listShare(String url) throws Exception {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Share URL is required");
        }
        URI uri = URI.create(url);
        String baseUrl = trimSlash(uri.getScheme() + "://" + uri.getHost()
                + (uri.getPort() >= 0 ? ":" + uri.getPort() : ""));
        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            throw new IOException("Share URL path is empty");
        }
        String[] segments = path.split("/");
        String shareId = "";
        for (int i = 0; i < segments.length; i++) {
            if ("s".equals(segments[i]) && i + 1 < segments.length) {
                shareId = segments[i + 1];
                break;
            }
            if ("share".equals(segments[i]) && i + 1 < segments.length) {
                shareId = segments[i + 1];
                break;
            }
        }
        if (shareId.isBlank()) {
            throw new IOException("Could not extract share ID from URL");
        }
        String sharePath = path.substring(path.indexOf(shareId) + shareId.length());
        if (sharePath.isBlank()) sharePath = "/";

        JsonObject body = OpenDriveHttp.json("path", sharePath, "password", "");
        JsonObject resp = OpenDriveHttp.postJson(baseUrl + "/api/fs/list", body,
                alistHeaders(baseUrl, ""));
        if (OpenDriveHttp.number(resp, "code") != 200) {
            throw new IOException("AList share listing failed: " + OpenDriveHttp.number(resp, "code"));
        }
        JsonObject data = OpenDriveHttp.object(resp, "data");
        JsonArray values = OpenDriveHttp.array(data, "content");
        List<OpenDriveItem> result = new ArrayList<>();
        String base = sharePath.endsWith("/") ? sharePath : sharePath + "/";
        for (JsonElement value : values) {
            if (!value.isJsonObject()) continue;
            JsonObject item = value.getAsJsonObject();
            String name = OpenDriveHttp.name(item, "name");
            boolean directory = OpenDriveHttp.bool(item, "is_dir");
            long size = OpenDriveHttp.number(item, "size");
            if (!directory && !isVideo(name)) continue;
            String itemPath = base + name;
            String handle = ALIST_PREFIX + sha256("share\n" + baseUrl + "\n" + itemPath);
            alistItems.put(handle, new AlistItem(-1, itemPath, name, size, directory, baseUrl));
            result.add(new OpenDriveItem(handle, name, directory, size));
        }
        return result;
    }

    private ItemRef requireItem(String handle) {
        if (handle == null || !handle.matches("item\\.[a-f0-9]{64}")) {
            throw new IllegalArgumentException("WebDAV item handle is invalid");
        }
        ItemRef item = transientItems.get(handle);
        if (item == null) item = OpenDriveHandleVault.get(id(), handle, ItemRef.class);
        if (item == null) throw new IllegalStateException("WebDAV item is no longer available");
        transientItems.put(handle, item);
        return item;
    }

    private WebDavConnectorStore.Connector requireConnector(int id) {
        WebDavConnectorStore.Connector connector = connectors.get(id);
        if (connector == null) throw new IllegalStateException("WebDAV connector was removed");
        return connector;
    }

    private String remember(ItemRef item) {
        String handle = ITEM_PREFIX + sha256(item.connectorId() + "\n" + item.url());
        transientItems.put(handle, item);
        return handle;
    }

    private static int parseConnectorId(String handle) {
        try {
            int id = Integer.parseInt(handle.substring(CONNECTOR_PREFIX.length()));
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("WebDAV connector handle is invalid");
        }
    }

    private static List<DavEntry> listWithSardine(String url, String username,
                                                   String password) throws IOException {
        Sardine sardine = new OkHttpSardine();
        if (!username.isEmpty() || !password.isEmpty()) sardine.setCredentials(username, password);
        List<DavEntry> result = new ArrayList<>();
        for (DavResource resource : sardine.list(url, 1)) {
            Long length = resource.getContentLength();
            result.add(new DavEntry(resource.getHref(), resource.getName(), resource.isDirectory(),
                    length == null ? 0 : length));
        }
        return result;
    }

    private static URI appendPath(URI base, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return base;
        StringBuilder encoded = new StringBuilder();
        for (String segment : relativePath.split("/")) {
            if (encoded.length() > 0) encoded.append('/');
            encoded.append(URLEncoder.encode(segment, StandardCharsets.UTF_8)
                    .replace("+", "%20"));
        }
        return base.resolve(encoded + "/");
    }

    private static boolean withinConnector(URI base, URI target) {
        if (!sameOrigin(base, target)) return false;
        String root = normalizedDecodedPath(base);
        String path = normalizedDecodedPath(target);
        if (root.isEmpty() || path.isEmpty()) return false;
        if (!root.endsWith("/")) root += "/";
        return path.equals(root.substring(0, root.length() - 1)) || path.startsWith(root);
    }

    private static String normalizedDecodedPath(URI uri) {
        try {
            String path = uri.getPath();
            if (path == null || path.indexOf('\\') >= 0) return "";
            return new URI(null, null, path, null).normalize().getPath();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean sameOrigin(URI first, URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean sameResource(URI first, URI second) {
        return trimSlash(first.normalize().toASCIIString())
                .equals(trimSlash(second.normalize().toASCIIString()));
    }

    private static String trimSlash(String value) {
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static String fileName(URI uri) {
        String path = trimSlash(uri.getPath());
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static boolean isVideo(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot + 1 < name.length()
                && MediaFileClassifier.isVideoExtension(name.substring(dot + 1));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Hex.encodeLower(digest);
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private String initialAlistPath(WebDavConnectorStore.Connector connector) {
        String path = connector.initialPath();
        return path == null || path.isEmpty() ? "/" : "/" + path;
    }

    /**
     * Feature-detects whether a connector points at an AList/OpenList instance and,
     * when the instance enforces auth, obtains a session token. The decision is
     * cached per connector id. This reuses the same AList REST contract the app's
     * legacy {@code AlistDriveViewModel} already speaks (public settings probe,
     * {@code /api/fs/list}, {@code /api/fs/get}, {@code /api/auth/login}).
     */
    private AlistSession alistSession(WebDavConnectorStore.Connector connector) {
        AlistSession cached = alistSessions.get(connector.id());
        if (cached != null) return cached;
        boolean alist = false;
        int version = 3;
        String token = "";
        try {
            JsonObject settings = alistClient.publicSettings(connector.baseUrl());
            if (OpenDriveHttp.number(settings, "code") == 200) {
                JsonElement data = settings.get("data");
                if (data != null && !data.isJsonNull()) {
                    alist = true;
                    version = data.isJsonObject() ? 3 : 2;
                }
            }
        } catch (Exception ignored) {
        }
        if (!alist && !connector.username().isEmpty() && !connector.password().isEmpty()) {
            try {
                String issued = alistClient.authLogin(connector.baseUrl(),
                        connector.username(), connector.password());
                if (issued != null && !issued.isBlank()) {
                    token = issued;
                    alist = true;
                    version = 3;
                }
            } catch (Exception ignored) {
            }
        }
        AlistSession session = new AlistSession(alist, version, token);
        alistSessions.put(connector.id(), session);
        return session;
    }

    private List<OpenDriveItem> alistList(WebDavConnectorStore.Connector connector,
            AlistSession session, String path) throws Exception {
        if (!session.alist) {
            throw new IOException("OpenList/AList endpoint is not reachable");
        }
        JsonObject resp = alistListResponse(connector, session, path);
        if (OpenDriveHttp.number(resp, "code") != 200 && session.token.isEmpty()
                && !connector.username().isEmpty() && !connector.password().isEmpty()) {
            String issued = alistClient.authLogin(connector.baseUrl(),
                    connector.username(), connector.password());
            if (issued != null && !issued.isBlank()) {
                session.token = issued;
                resp = alistListResponse(connector, session, path);
            }
        }
        if (OpenDriveHttp.number(resp, "code") != 200) {
            throw new IOException("OpenList/AList listing failed");
        }
        JsonObject data = OpenDriveHttp.object(resp, "data");
        JsonArray values = session.version == 3
                ? OpenDriveHttp.array(data, "content")
                : OpenDriveHttp.array(data, "files");
        List<OpenDriveItem> result = new ArrayList<>();
        String base = path.endsWith("/") ? path : path + "/";
        for (JsonElement value : values) {
            if (!value.isJsonObject()) continue;
            JsonObject item = value.getAsJsonObject();
            String name = OpenDriveHttp.name(item, "name");
            boolean directory = session.version == 3
                    ? OpenDriveHttp.bool(item, "is_dir")
                    : OpenDriveHttp.number(item, "type") == 1;
            long size = OpenDriveHttp.number(item, "size");
            if (!directory && !isVideo(name)) continue;
            String itemPath = base + name;
            result.add(new OpenDriveItem(rememberAlist(connector.id(), itemPath, name, size, directory),
                    name, directory, size));
        }
        return result;
    }

    private JsonObject alistListResponse(WebDavConnectorStore.Connector connector,
            AlistSession session, String path) throws Exception {
        if (session.version == 3) {
            return alistClient.fsList(connector.baseUrl(), session.token, path);
        }
        JsonObject body = OpenDriveHttp.json("path", path, "password", "",
                "page_num", 1, "page_size", 200);
        return OpenDriveHttp.postJson(connector.baseUrl() + "/api/public/path",
                body, alistHeaders(connector.baseUrl(), session.token));
    }

    private OpenDrivePlayback alistResolve(String itemId) throws Exception {
        AlistItem item = requireAlist(itemId);
        if (item.directory()) {
            throw new IllegalArgumentException("OpenList/AList folders cannot be played");
        }
        if (item.shareBaseUrl() != null) {
            return alistResolveShare(item);
        }
        WebDavConnectorStore.Connector connector = requireConnector(item.connectorId());
        AlistSession session = alistSession(connector);
        JsonObject resp = alistClient.fsGet(connector.baseUrl(), session.token, item.path());
        if (OpenDriveHttp.number(resp, "code") != 200 && session.token.isEmpty()
                && !connector.username().isEmpty() && !connector.password().isEmpty()) {
            String issued = alistClient.authLogin(connector.baseUrl(),
                    connector.username(), connector.password());
            if (issued != null && !issued.isBlank()) {
                session.token = issued;
                resp = alistClient.fsGet(connector.baseUrl(), issued, item.path());
            }
        }
        if (OpenDriveHttp.number(resp, "code") != 200) {
            throw new IOException("OpenList/AList could not authorize playback");
        }
        JsonObject data = OpenDriveHttp.object(resp, "data");
        String url = OpenDriveHttp.first(data, "raw_url", "url");
        if (url.isBlank()) throw new IOException("OpenList/AList returned no playback URL");
        if (url.startsWith("//")) url = "https:" + url;
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", OpenDriveHttp.USER_AGENT);
        if (!session.token.isEmpty()) headers.put("Authorization", session.token);
        return new OpenDrivePlayback(url, headers, 0);
    }

    private OpenDrivePlayback alistResolveShare(AlistItem item) throws Exception {
        JsonObject body = OpenDriveHttp.json("path", item.path(), "password", "");
        JsonObject resp = OpenDriveHttp.postJson(item.shareBaseUrl() + "/api/fs/get", body,
                alistHeaders(item.shareBaseUrl(), ""));
        if (OpenDriveHttp.number(resp, "code") != 200) {
            throw new IOException("AList share resolve failed: " + OpenDriveHttp.number(resp, "code"));
        }
        JsonObject data = OpenDriveHttp.object(resp, "data");
        String url = OpenDriveHttp.first(data, "raw_url", "url");
        if (url.isBlank()) throw new IOException("AList share returned no playback URL");
        if (url.startsWith("//")) url = "https:" + url;
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", OpenDriveHttp.USER_AGENT);
        return new OpenDrivePlayback(url, headers, 0);
    }

    private AlistItem requireAlist(String handle) {
        if (handle == null || !handle.matches("alist\\.[a-f0-9]{64}")) {
            throw new IllegalArgumentException("OpenList/AList item handle is invalid");
        }
        AlistItem item = alistItems.get(handle);
        if (item == null) item = OpenDriveHandleVault.get(id(), handle, AlistItem.class);
        if (item == null) throw new IllegalStateException("OpenList/AList item is no longer available");
        alistItems.put(handle, item);
        return item;
    }

    private String rememberAlist(int connectorId, String path, String name, long size, boolean directory) {
        String handle = ALIST_PREFIX + sha256(connectorId + "\n" + path);
        alistItems.put(handle, new AlistItem(connectorId, path, name, size, directory));
        return handle;
    }

    private static Map<String, String> settingsHeaders(String baseUrl) {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", trimSlash(baseUrl) + "/");
        return headers;
    }

    private static Map<String, String> alistHeaders(String baseUrl, String token) {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("accept", "application/json, text/plain, */*");
        headers.put("content-type", "application/json;charset=UTF-8");
        headers.put("origin", trimSlash(baseUrl));
        headers.put("Referer", trimSlash(baseUrl) + "/");
        headers.put("User-Agent", OpenDriveHttp.USER_AGENT);
        if (token != null && !token.isEmpty()) headers.put("Authorization", token);
        return headers;
    }

    private record AlistItem(int connectorId, String path, String name, long size, boolean directory,
                              String shareBaseUrl) {
        AlistItem(int connectorId, String path, String name, long size, boolean directory) {
            this(connectorId, path, name, size, directory, null);
        }
    }

    private static final class AlistSession {
        boolean alist;
        int version;
        String token;

        AlistSession(boolean alist, int version, String token) {
            this.alist = alist;
            this.version = version;
            this.token = token == null ? "" : token;
        }
    }

    interface AlistClient {
        JsonObject publicSettings(String baseUrl) throws Exception;

        String authLogin(String baseUrl, String user, String password) throws Exception;

        JsonObject fsList(String baseUrl, String token, String path) throws Exception;

        JsonObject fsGet(String baseUrl, String token, String path) throws Exception;
    }

    private static final class HttpAlistClient implements AlistClient {
        static final HttpAlistClient INSTANCE = new HttpAlistClient();

        @Override
        public JsonObject publicSettings(String baseUrl) throws Exception {
            return OpenDriveHttp.get(baseUrl + "/api/public/settings", null, settingsHeaders(baseUrl));
        }

        @Override
        public String authLogin(String baseUrl, String user, String password) throws Exception {
            JsonObject body = OpenDriveHttp.json("username", user == null ? "" : user,
                    "password", password == null ? "" : password);
            JsonObject resp = OpenDriveHttp.postJson(baseUrl + "/api/auth/login", body, settingsHeaders(baseUrl));
            if (OpenDriveHttp.number(resp, "code") != 200) return "";
            return OpenDriveHttp.string(OpenDriveHttp.object(resp, "data"), "token");
        }

        @Override
        public JsonObject fsList(String baseUrl, String token, String path) throws Exception {
            JsonObject body = OpenDriveHttp.json("path", path, "password", "", "page", 1,
                    "per_page", 200, "refresh", false);
            return OpenDriveHttp.postJson(baseUrl + "/api/fs/list", body, alistHeaders(baseUrl, token));
        }

        @Override
        public JsonObject fsGet(String baseUrl, String token, String path) throws Exception {
            JsonObject body = OpenDriveHttp.json("path", path, "password", "");
            return OpenDriveHttp.postJson(baseUrl + "/api/fs/get", body, alistHeaders(baseUrl, token));
        }
    }
}
