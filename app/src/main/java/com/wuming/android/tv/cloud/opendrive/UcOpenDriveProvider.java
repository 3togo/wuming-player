package com.wuming.android.tv.cloud.opendrive;

import com.wuming.tv.core.cloud.*;
import com.wuming.tv.core.media.MediaFileClassifier;

import com.wuming.tv.core.playback.OpenDrivePlaybackHints;

import com.wuming.android.tv.cloud.CloudCacheRecord;
import com.wuming.android.tv.cloud.CloudStorageManager;
import com.wuming.android.tv.cloud.CloudStorageProvider;
import com.wuming.android.tv.cloud.CloudStorageRepository;

import com.github.catvod.net.PersistentCookieJar;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Cookie;
import okhttp3.HttpUrl;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** UC's first-party web API adapter. Login cookies come from the Settings WebView. */
final class UcOpenDriveProvider extends AbstractOpenDriveProvider {

    private static final String API = "https://pc-api.uc.cn/1/clouddrive/";
    private static final String LOGIN = "https://drive.uc.cn/";
    private static final String QR_CLIENT_ID = "381";
    private static final String API_UT =
            "Nk27FcCv6q1eo6rXz8QHR/nIG6qLA3jh7KdL+agFgcOvww==";
    private static final String API_QUERY =
            "pr=UCBrowser&fr=pc&sys=darwin&ve=1.8.6&ut=" + API_UT;
    private static final String UC_PLAY_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) uc-cloud-drive/2.5.20 "
                    + "Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 "
                    + "Safari/537.36 Channel/pckk_other_ch";
    private static final String PDS_PLAY_USER_AGENT =
            "Mozilla/5.0 (X11; Linux aarch64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    /**
     * Keep share token and detail reads on UC's transfer route. The former
     * {@code pr=ucpro&fr=pc&uc_param_str=} route now returns HTTP 403/code 41027
     * ("share does not exist") for valid public links, including unrelated-account fixtures.
     */
    private static final String SHAREPAGE_QUERY = "entry=ft&fr=pc&pr=UCBrowser";
    private static final String SAVE_QUERY = "entry=update_share&pr=UCBrowser&fr=pc";
    private static final String TASK_QUERY = "pr=UCBrowser&fr=pc";
    // LinkSwift's maintained UC client contract. This returns the PDS/OSS URL class used by
    // UC's current desktop client; the older darwin/1.8.6 query returns a throttled drive URL.
    private static final String DOWNLOAD_QUERY = "entry=ft&fr=pc&pr=UCBrowser";
    private static final String SAVE_FOLDER = CloudStorageManager.CACHE_FOLDER;
    private static final int PAGE_SIZE = 200;
    private static final int MAX_PAGES = 20;
    private static final int MAX_DIRECTORIES = 256;
    private static final int SAVE_TASK_MAX_POLLS = 30;
    private static final long SAVE_TASK_POLL_INTERVAL_MILLIS = 1_000L;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 8.0.0; SM-G955U Build/R16NW) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 "
                    + "Mobile Safari/537.36";

    private final OpenDriveFileHandles<FileRef> files = new OpenDriveFileHandles<>(id(), FileRef.class);
    private final ConcurrentHashMap<String, StreamRef> streams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> savedAccounts = new ConcurrentHashMap<>();
    // Player resolution can be requested twice for one episode. Reuse that saved copy so
    // automatic previous-item cleanup never deletes the file while the player is opening it.
    private final ConcurrentHashMap<String, String> savedSourceFids = new ConcurrentHashMap<>();
    private final Set<String> protectedSavedFids = ConcurrentHashMap.newKeySet();
    private final CloudAccountProvider loginDelegate;
    private final ScheduledExecutorService cleanupExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "UcOpenDriveCleanup");
                thread.setDaemon(true);
                return thread;
            });
    private volatile String saveFolderId;
    private volatile UcCookies credentialCookies;
    private volatile String dynamicUt;

    UcOpenDriveProvider() {
        this(new com.wuming.tv.core.runtime.cloudlogin.UcCloudAccountProvider(
                new AndroidCloudCredentialStore(), new AndroidCloudLoginTransport()));
    }

    UcOpenDriveProvider(CloudAccountProvider loginDelegate) {
        this.loginDelegate = loginDelegate;
    }

    @Override
    public String id() {
        return "uc";
    }

    @Override
    public String displayName() {
        return "UC Drive";
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
    public boolean verifyAuthentication() throws Exception {
        return loginDelegate.verifyAuthentication();
    }

    static boolean validAccountResponse(JsonObject root) {
        return OpenDriveHttp.object(root, "data").size() > 0;
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
        String fid = "root".equals(parentId) ? "0" : requireFile(parentId).fid;
        JsonArray values = OpenDriveHttp.array(OpenDriveHttp.data(get("file/sort", Map.of(
                "pdir_fid", fid, "_page", "1", "_size", String.valueOf(PAGE_SIZE),
                "_sort", "file_type:asc,updated_at:desc")), "UC"), "list");
        List<OpenDriveItem> result = new ArrayList<>();
        for (JsonElement value : values) {
            if (!value.isJsonObject()) continue;
            JsonObject item = value.getAsJsonObject();
            boolean directory = OpenDriveHttp.bool(item, "dir");
            if (!directory && !OpenDriveHttp.isVideo(item, "obj_category", "file_name")) continue;
            String handle = remember(new FileRef(
                    OpenDriveHttp.required(item, "fid", "UC"), "", "", "", "", true, false,
                    false, OpenDriveHttp.number(item, "size")));
            result.add(new OpenDriveItem(handle, OpenDriveHttp.name(item, "file_name"), directory,
                    OpenDriveHttp.number(item, "size")));
        }
        return result;
    }

    @Override
    public boolean supportsShare(String url) {
        return canonicalShareUrl(url) != null;
    }

    @Override
    public List<OpenDriveItem> listShare(String url) throws Exception {
        String canonical = canonicalShareUrl(url);
        if (canonical == null) throw new IllegalArgumentException("Invalid UC share link");
        String shareId = canonical.substring(canonical.lastIndexOf('/') + 1);
        String passcode = sharePasscode(url);
        String stoken = OpenDriveHttp.string(OpenDriveHttp.data(post("share/sharepage/token",
                shareTokenBody(shareId, passcode)), "UC"), "stoken");
        if (stoken.isBlank()) throw new IOException("UC rejected the share link");

        List<OpenDriveItem> result = new ArrayList<>();
        ArrayDeque<FolderRef> pending = new ArrayDeque<>();
        pending.add(new FolderRef("0", 0));
        int visited = 0;
        while (!pending.isEmpty()) {
            FolderRef folder = pending.removeFirst();
            if (++visited > MAX_DIRECTORIES) throw new IOException("UC share contains too many folders");
            for (int page = 1; page <= MAX_PAGES; page++) {
                JsonArray values = OpenDriveHttp.array(OpenDriveHttp.data(get("share/sharepage/detail", Map.of(
                        "pwd_id", shareId, "stoken", stoken, "pdir_fid", folder.fid,
                        "force", "0", "_page", String.valueOf(page),
                        "_size", String.valueOf(PAGE_SIZE), "_sort", "file_type:asc,file_name:asc")), "UC"), "list");
                for (JsonElement value : values) {
                    if (!value.isJsonObject()) continue;
                    JsonObject item = value.getAsJsonObject();
                    if (OpenDriveHttp.bool(item, "dir")) {
                        if (folder.depth < 20) pending.addLast(new FolderRef(OpenDriveHttp.required(item, "fid", "UC"), folder.depth + 1));
                    } else if (OpenDriveHttp.isVideo(item, "obj_category", "file_name")) {
                        String handle = remember(new FileRef(OpenDriveHttp.required(item, "fid", "UC"),
                                OpenDriveHttp.required(item, "share_fid_token", "UC"), shareId, stoken, passcode, false,
                                OpenDriveHttp.bool(item, "ban"), false,
                                OpenDriveHttp.number(item, "size")));
                        result.add(new OpenDriveItem(handle, OpenDriveHttp.name(item, "file_name"), false,
                                OpenDriveHttp.number(item, "size")));
                    }
                }
                if (values.size() < PAGE_SIZE) break;
            }
        }
        return result;
    }

    @Override
    public OpenDrivePreparedExternal prepareExternal(OpenDriveExternalPayload payload) {
        JsonObject item = payload.json(false);
        if (!ExternalEpisodePayload.belongsTo(payload.flag(), item,
                "ucpan", "uc盘", "uc网", "uc云", "uc原", "uc智", "优汐")) {
            return null;
        }
        String fid;
        String fileToken;
        String shareId;
        String shareToken;
        String passcode = "";
        if (item != null) {
            fid = ExternalEpisodePayload.first(item, "fid", "file_id", "fileId");
            fileToken = ExternalEpisodePayload.first(item, "share_fid_token", "fid_token", "fileToken");
            shareId = ExternalEpisodePayload.first(item, "sId", "share_id", "shareId", "pwd_id");
            shareToken = ExternalEpisodePayload.first(item, "sToken", "stoken", "share_token", "shareToken");
            passcode = ExternalEpisodePayload.first(item,
                    "passcode", "pwd", "password", "share_pwd", "sPwd", "sharePwd");
        } else {
            String[] values = ExternalEpisodePayload.split(payload.raw(), "\\+\\+", 4);
            if (values == null) return null;
            fid = values[0];
            fileToken = values[1];
            shareId = values[2];
            shareToken = values[3];
        }
        if (!ExternalEpisodePayload.identifier(fid) || !ExternalEpisodePayload.capability(fileToken)
                || !ExternalEpisodePayload.identifier(shareId)
                || !ExternalEpisodePayload.capability(shareToken)) return null;
        long size = item == null ? 0L : Math.max(OpenDriveHttp.number(item, "size"),
                OpenDriveHttp.number(item, "file_size"));
        FileRef file = new FileRef(fid, fileToken, shareId, shareToken, passcode, false,
                item != null && OpenDriveHttp.bool(item, "ban"),
                smartPlayback(payload.flag(), item), size);
        return OpenDrivePreparedExternal.of(() -> remember(file));
    }

    static boolean smartPlayback(String flag, JsonObject item) {
        return smartPlayback(flag) || smartPlayback(ExternalEpisodePayload.first(
                item, "flag", "quality", "qtype"));
    }

    static boolean smartPlayback(String flag) {
        if (flag == null) return false;
        String value = flag.toLowerCase(Locale.ROOT);
        return value.contains("uc智") || value.contains("优汐智")
                || value.contains("uc smart") || value.contains("ucpan smart")
                || value.equals("smart") || value.equals("preview");
    }

    @Override
    public OpenDrivePlayback resolve(String itemId) throws Exception {
        FileRef file = requireFile(itemId);
        if (file.banned) {
            OpenDriveLog.resolveInvalid(id(), itemId, "file_banned", "UC marks this shared file unavailable");
            throw new IOException("UC marks this shared file unavailable");
        }
        if (file.personal) return resolveSaved(file.fid, true, false);
        // UC original and smart are separate source contracts. Original must never
        // create a personal-cloud copy or silently switch to the transcode endpoint.
        // Smart keeps the save/transcode path and its quota-only direct fallback.
        if (directSharePlayback(file.personal, file.smart)) return resolveSharedDownload(file);

        String fid;
        try {
            fid = save(file);
        } catch (UcQuotaException quota) {
            OpenDriveLog.fallback(id(), itemId, "save-transcode", "share-download", "quota-exhausted");
            try {
                OpenDrivePlayback playback = resolveSharedDownload(file);
                return new OpenDrivePlayback(playback.url(), OpenDrivePlaybackHints.fallback(
                        playback.headers(), OpenDrivePlaybackHints.FallbackReason.UC_QUOTA),
                        playback.expiresAtEpochMillis());
            } catch (Exception directError) {
                OpenDriveLog.unexpected(id(), itemId, directError);
                directError.addSuppressed(quota);
                throw directError;
            }
        }
        try {
            return resolveSaved(fid, false, true);
        } catch (Exception firstError) {
            if (!playEndpointNotFound(firstError)) {
                cleanupSaved(fid);
                throw firstError;
            }
            OpenDriveLog.fallback(id(), itemId, "play-404", "re-save-retry", "play-endpoint-not-found");
            cleanupSaved(fid);
            fid = save(file);
            try {
                return resolveSaved(fid, true, true);
            } catch (Exception retryError) {
                cleanupSaved(fid);
                OpenDriveLog.unexpected(id(), itemId, retryError);
                retryError.addSuppressed(firstError);
                throw retryError;
            }
        }
    }

    private OpenDrivePlayback resolveSharedDownload(FileRef file) throws Exception {
        // share_fid_token and stoken are issued as one capability pair by listShare.
        // Refreshing only stoken here makes UC reject file/download with HTTP 403.
        String stoken = file.shareToken;
        JsonObject request = shareDownloadBody(
                file.fid, file.fileToken, file.shareId, stoken);
        ResolvedDownload resolved = resolveDownload(request);
        if (!OpenDriveHttp.validHttpsUrl(resolved.url)) {
            throw new IOException("UC did not return a playable share URL");
        }
        OpenDriveLog.providerInfo(id(), "share-download-fallback",
                "host=" + safeHost(resolved.url) + " expectedSize=" + resolved.size);
        return new OpenDrivePlayback(resolved.url,
                downloadHeaders(resolved.response.cookie, resolved.url, resolved.size),
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10));
    }

    private OpenDrivePlayback resolveSaved(String fid, boolean fallbackOnPlay404,
                                           boolean disposableCopy) throws Exception {
        JsonObject request = OpenDriveHttp.json("fids", List.of(fid));
        DownloadResponse download = null;
        String rawUrl = "";
        long expectedSize = 0L;
        try {
            ResolvedDownload resolved = resolveDownload(request);
            download = resolved.response;
            rawUrl = resolved.url;
            expectedSize = resolved.size;
            // uzVideo treats raw download as one optional candidate and still calls
            // getLiveTranscoding when file/download is unavailable for this account.
        } catch (IOException ignored) {
        }
        String transcodedUrl = "";
        try {
            transcodedUrl = pickTranscodedUrl(postPlay(fid));
        } catch (IOException error) {
            if (!useRawAfterPlayFailure(error, fallbackOnPlay404, rawUrl)) throw error;
            OpenDriveLog.providerInfo(id(), "play-unavailable", "using=original-download");
        }
        PlaybackCandidate candidate = selectPlaybackCandidate(rawUrl, transcodedUrl);
        if (candidate == null) {
            throw new IOException("UC did not return a playable personal-drive URL");
        }
        boolean transcoded = candidate.transcoded;
        String url = candidate.url;
        String streamCookie = transcoded || download == null
                ? requireCredential().header() : download.cookie;
        if (disposableCopy) {
            cleanupExecutor.schedule(() -> cleanupSaved(fid), 15, TimeUnit.MINUTES);
        }
        // Keep HLS directly on its origin so relative playlists and segments resolve correctly.
        if (transcoded) {
            Map<String, String> headers = downloadHeaders(streamCookie);
            return OpenDriveCopyUsage.attach(new OpenDrivePlayback(url, OpenDrivePlaybackHints.hls(
                    headers, headers.containsKey("Cookie")),
                    System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10)),
                    id(), disposableCopy ? savedAccounts.get(fid) : null, fid);
        }
        return OpenDriveCopyUsage.attach(new OpenDrivePlayback(url, downloadHeaders(streamCookie, url, expectedSize),
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10)),
                id(), disposableCopy ? savedAccounts.get(fid) : null, fid);
    }

    static JsonObject shareTokenBody(String shareId, String passcode) {
        return OpenDriveHttp.json("pwd_id", shareId, "passcode", passcode,
                "share_for_transfer", true);
    }

    static JsonObject shareDownloadBody(String fid, String fileToken, String shareId,
                                        String shareToken) {
        return OpenDriveHttp.json("fids", List.of(fid),
                "fids_token", List.of(fileToken), "pwd_id", shareId,
                "stoken", shareToken);
    }

    @Override
    public OpenDriveProxyResponse proxy(Map<String, String> params) throws Exception {
        long requestStart = System.nanoTime();
        StreamRef stream = streams.get(params.get("stream"));
        if (stream == null) throw new IllegalArgumentException("Playback session expired; reopen the share");
        // UC signs the download request headers. Content-Type belongs on JSON API calls;
        // sending it on this GET makes the signed PDS endpoint reject an otherwise valid
        // range request with 403, which our local proxy would surface as HTTP 500.
        String requestCookie = stream.cookie;
        Response response = OpenDriveHttp.openExplicit(
                stream.url, params.get("range"), downloadHeaders(requestCookie));
        OpenDriveLog.providerInfo(id(), "proxy-upstream",
                "status=" + response.code() + " elapsedMs=" + elapsedMillis(requestStart));
        ResponseBody body = response.body();
        if (!response.isSuccessful() || body == null) {
            int code = response.code();
            String upstreamCode = classifyUpstreamError(response, body);
            response.close();
            // The signed-download cookie (and the signed URL) may have expired between
            // resolve and playback. Per the proven UC protocol, a fresh signed URL and
            // cookie are obtained by re-calling file/download. Re-resolve once and retry.
            StreamRef refreshed = "RequestDeniedByCallback".equals(upstreamCode)
                    && !stream.transcoded ? reResolve(stream) : null;
            if (refreshed != null) {
                response = OpenDriveHttp.openExplicit(
                        refreshed.url, params.get("range"), downloadHeaders(refreshed.cookie));
                OpenDriveLog.providerInfo(id(), "proxy-refreshed",
                        "status=" + response.code() + " elapsedMs=" + elapsedMillis(requestStart));
                body = response.body();
                if (!response.isSuccessful() || body == null) {
                    code = response.code();
                    upstreamCode = classifyUpstreamError(response, body);
                    response.close();
                    return upstreamFailure(code, upstreamCode);
                }
                // Keep the renewed stream available for subsequent range requests.
                streams.put(params.get("stream"), refreshed);
            } else {
                return upstreamFailure(code, upstreamCode);
            }
        }
        // UC returns HTML/JSON error pages with HTTP 200 when the signed URL
        // is stale or the user's playback quota is exhausted. Forwarding that
        // body to Media3 surfaces as "UnrecognizedInputFormatException".
        String mime = body.contentType() == null ? "" : body.contentType().toString()
                .toLowerCase(java.util.Locale.ROOT);
        boolean videoContent = mime.startsWith("video/") || mime.startsWith("audio/")
                || mime.startsWith("application/octet-stream") || mime.startsWith("application/mp4")
                || mime.startsWith("application/vnd.apple.mpegurl")
                || mime.startsWith("application/x-mpegurl");
        if (!videoContent) {
            response.close();
            throw new IOException("UC playback returned a non-media response ("
                    + mime + ")");
        }
        long returnedSize = contentRangeTotal(response.header("Content-Range"));
        if (stream.expectedSize > 0L && returnedSize > 0L
                && stream.expectedSize != returnedSize) {
            response.close();
            throw new IOException("UC returned substitute media instead of the saved file");
        }
        InputStream input = new CleanupStream(body.byteStream(), response, requestStart);
        Map<String, String> outputHeaders = OpenDriveProxyResponse.mediaHeaders(
                response.header("Content-Range"), body.contentLength());
        return new OpenDriveProxyResponse(response.code(), mime, input, outputHeaders);
    }

    /**
     * Re-resolves a signed download URL and its cookie via file/download, following the
     * proven UC protocol (the signed URL and __puus are both produced by that call).
     * Returns null if the re-resolve fails.
     */
    private StreamRef reResolve(StreamRef stream) {
        try {
            DownloadResponse download = postDownload(stream.downloadRequest);
            JsonArray values = OpenDriveHttp.array(download.body, null);
            if (values.isEmpty() || !values.get(0).isJsonObject()) return null;
            JsonObject item = values.get(0).getAsJsonObject();
            String url = OpenDriveHttp.string(item, "download_url");
            if (!OpenDriveHttp.validHttpsUrl(url)) return null;
            long expectedSize = OpenDriveHttp.number(item, "size");
            return new StreamRef(url, download.cookie, stream.downloadRequest, stream.savedFid,
                    expectedSize > 0L ? expectedSize : stream.expectedSize, false);
        } catch (Exception ignored) {
            return null;
        }
    }

    static OpenDriveProxyResponse upstreamFailure(int status, String upstreamCode) {
        String message = "RequestDeniedByCallback".equals(upstreamCode)
                ? "UC rejected this download authorization. Re-login to UC or use another source."
                : "UC playback request was rejected by the upstream service.";
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        return new OpenDriveProxyResponse(status, "text/plain; charset=utf-8",
                new ByteArrayInputStream(bytes), Map.of("X-OpenDrive-Error", message));
    }

    private static String classifyUpstreamError(Response response, ResponseBody body) {
        for (String header : List.of("x-oss-error-code", "x-error-code")) {
            String value = response.header(header, "");
            if (value.matches("[A-Za-z0-9_.-]{1,64}")) return value;
        }
        if (body == null) return "";
        try {
            String text = response.peekBody(2_048).string();
            java.util.regex.Matcher xml = java.util.regex.Pattern.compile(
                    "<Code>([A-Za-z0-9_.-]{1,64})</Code>").matcher(text);
            if (xml.find()) return xml.group(1);
            java.util.regex.Matcher json = java.util.regex.Pattern.compile(
                    "\\\"(?:code|error)\\\"\\s*:\\s*\\\"([A-Za-z0-9_.-]{1,64})\\\"")
                    .matcher(text);
            return json.find() ? json.group(1) : "";
        } catch (IOException ignored) {
            return "";
        }
    }

    static long contentRangeTotal(String value) {
        if (value == null) return 0L;
        int slash = value.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= value.length()) return 0L;
        try {
            return Long.parseLong(value.substring(slash + 1));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    @Override
    public synchronized void credentialsRestored() {
        files.clear();
        streams.clear();
        savedSourceFids.clear();
        protectedSavedFids.clear();
        saveFolderId = null;
        credentialCookies = null;
    }

    @Override
    public void logout() throws Exception {
        loginDelegate.logout();
        files.clearPersisted();
        for (StreamRef stream : streams.values()) {
            if (stream.savedFid != null) cleanupSaved(stream.savedFid);
        }
        streams.clear();
        savedSourceFids.clear();
        protectedSavedFids.clear();
        saveFolderId = null;
        credentialCookies = new UcCookies();
    }

    @Override
    public void shutdown() {
        cleanupExecutor.shutdownNow();
    }

    static String canonicalShareUrl(String value) {
        return AbstractOpenDriveProvider.canonicalShareUrl(value, "drive.uc.cn");
    }

    static String sharePasscode(String value) {
        return AbstractOpenDriveProvider.sharePasscode(value, "UC");
    }

    static String qrContent(String token) {
        if (!validUcToken(token)) throw new IllegalArgumentException("Invalid UC QR token");
        return "https://su.uc.cn/1_n0ZCv"
                + "?uc_param_str=dsdnfrpfbivesscpgimibtbmnijblauputogpintnwktprchmt"
                + "&token=" + token
                + "&client_id=" + QR_CLIENT_ID
                + "&uc_biz_str=S%3Acustom%7CC%3Atitlebar_fix";
    }

    // UC keeps its stricter token shape ([A-Za-z0-9_-]{8,512}) rather than the shared
    // validLoginValue, because the UC login service rejects wider tokens.
    private static boolean validUcToken(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{8,512}");
    }

    private synchronized String save(FileRef file) throws Exception {
        String sourceKey = file.shareId + "\n" + file.fid;
        String reusable = savedSourceFids.get(sourceKey);
        if (reusable != null && protectedSavedFids.contains(reusable)) return reusable;

        // A protected copy belongs to the previous UC selection. Keeping it until the
        // 15-minute safety timer fires can fill a small UC account and make the newly
        // selected episode look "skipped". Delete it now; the same-source check above
        // still protects duplicate player requests while the current item is starting.
        List<String> previousFids = List.copyOf(protectedSavedFids);
        if (!previousFids.isEmpty()) {
            OpenDriveLog.providerInfo(id(), "cache-handoff-cleanup",
                    "count=" + previousFids.size());
        }
        for (String previousFid : previousFids) {
            cleanupSaved(previousFid);
        }
        String folder = ensureFolder();
        ensureCapacityForSave(file, folder);
        try {
            String fid = saveOnce(file, folder);
            savedSourceFids.put(sourceKey, fid);
            return fid;
        } catch (UcQuotaException quota) {
            if (reclaimInactiveCache(folder) == 0) throw quota;
            String fid = saveOnce(file, folder);
            savedSourceFids.put(sourceKey, fid);
            return fid;
        }
    }

    private String saveOnce(FileRef file, String folder) throws Exception {
        // Preserve the stoken/share_fid_token pair returned by listShare, just as
        // original download does. Refreshing stoken alone invalidates this save.
        String stoken = file.shareToken;
        JsonObject task = OpenDriveHttp.data(post("share/sharepage/save", OpenDriveHttp.json(
                "fid_list", List.of(file.fid),
                "fid_token_list", List.of(file.fileToken),
                "to_pdir_fid", folder,
                "pwd_id", file.shareId,
                "stoken", stoken,
                "pdir_fid", "0",
                "scene", "link")), "UC");
        String taskId = OpenDriveHttp.string(task, "task_id");
        if (taskId.isBlank()) throw new IOException("UC could not save the shared file");
        for (int retry = 0; retry < SAVE_TASK_MAX_POLLS; retry++) {
            LinkedHashMap<String, String> query = new LinkedHashMap<>();
            query.put("task_id", taskId);
            query.put("retry_index", String.valueOf(retry));
            JsonObject result = OpenDriveHttp.data(get("task", query), "UC");
            JsonObject saveAs = OpenDriveHttp.object(result, "save_as");
            JsonArray saved = OpenDriveHttp.array(saveAs, "save_as_top_fids");
            if (!saved.isEmpty()) {
                String fid = saved.get(0).getAsString();
                try {
                    String account = OpenDriveCacheSupport.record(CloudStorageProvider.UC,
                            API, folder, fid, "Shared video", file.size);
                    savedAccounts.put(fid, account);
                    OpenDriveCopyUsage.stage(id(), account, fid);
                    // A concurrent resolve may reclaim inactive cache immediately after this
                    // synchronized save returns. Keep a bounded playback lease so another
                    // resolve cannot delete this copy before or just after the player opens it.
                    protectedSavedFids.add(fid);
                    return fid;
                } catch (RuntimeException error) {
                    deleteTracked(fid);
                    throw error;
                }
            }
            long status = OpenDriveHttp.number(result, "status");
            if (status == 2L) throw new IOException("UC completed the save without a file");
            if (status >= 3L) {
                long remaining = OpenDriveHttp.number(saveAs, "remain_capacity");
                long required = OpenDriveHttp.number(saveAs, "min_save_file_size");
                if (required > 0L && remaining < required) {
                    throw new UcQuotaException("UC cloud space is insufficient; "
                            + "no disposable OpenDrive cache remains");
                }
                throw new IOException("UC rejected the shared file; try another route");
            }
            if (retry + 1 < SAVE_TASK_MAX_POLLS) {
                try {
                    Thread.sleep(SAVE_TASK_POLL_INTERVAL_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("UC save was interrupted", interrupted);
                }
            }
        }
        throw new IOException("UC timed out while saving the shared file");
    }

    private void ensureCapacityForSave(FileRef file, String folderId) throws Exception {
        if (file.size <= 0L) return;
        try {
            long available = availableCapacity(get("member", Collections.emptyMap()));
            if (available < 0L || available >= file.size) return;
            int reclaimed = reclaimInactiveCache(folderId);
            OpenDriveLog.providerInfo(id(), "quota-preflight",
                    "required=" + file.size + " available=" + available + " reclaimed=" + reclaimed);
            if (reclaimed == 0) {
                throw new UcQuotaException("UC cloud space is insufficient; "
                        + "no disposable OpenDrive cache remains");
            }
        } catch (UcQuotaException error) {
            throw error;
        } catch (Exception error) {
            OpenDriveLog.providerWarn(id(), "quota-check-failed",
                    "error=" + error.getClass().getSimpleName());
        }
    }

    static long availableCapacity(JsonObject root) {
        JsonObject data = OpenDriveHttp.object(root, "data");
        long total = OpenDriveHttp.number(data, "total_capacity");
        long used = OpenDriveHttp.number(data, "use_capacity");
        if (total <= 0L || used < 0L || used > total) return -1L;
        return total - used;
    }

    private List<JsonObject> personalPage(String fid) throws Exception {
        JsonArray values = OpenDriveHttp.array(OpenDriveHttp.data(get("file/sort", Map.of(
                "pdir_fid", fid, "_page", "1", "_size", Integer.toString(PAGE_SIZE),
                "_sort", "file_type:asc,updated_at:desc")), "UC"), "list");
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement value : values) {
            if (value.isJsonObject()) result.add(value.getAsJsonObject());
        }
        return result;
    }

    private int reclaimInactiveCache(String folderId) {
        Set<String> protectedIds = ConcurrentHashMap.newKeySet();
        protectedIds.addAll(protectedSavedFids);
        for (StreamRef stream : streams.values()) {
            if (stream.savedFid != null) protectedIds.add(stream.savedFid);
        }
        // Interrupted and older app versions can leave duplicate TVBoxCache folders.
        // Cleaning only ensureFolder()'s first match reports reclaimed=0 while another
        // app-owned folder still consumes the UC quota, causing playback to skip.
        Set<String> cacheFolderIds = new LinkedHashSet<>();
        cacheFolderIds.add(folderId);
        try {
            cacheFolderIds.addAll(cacheFolderIds(personalPage("0")));
        } catch (Exception error) {
            OpenDriveLog.providerWarn(id(), "cache-root-inspect-failed",
                    "error=" + error.getClass().getSimpleName());
        }

        int tracked = 0;
        int folderFiles = 0;
        int purged = 0;
        for (String cacheFolderId : cacheFolderIds) {
            Set<String> deletedIds = new LinkedHashSet<>();
            tracked += OpenDriveCacheSupport.reclaimInactive(
                    CloudStorageProvider.UC, cacheFolderId, protectedIds, fid -> {
                        deleteTracked(fid);
                        deletedIds.add(fid);
                        savedAccounts.remove(fid);
                    });
            try {
                // AndroidCatVodSpider's maintained UCApi.clearSaveDir() lists the
                // dedicated save directory and deletes every old entry before a new
                // share transfer. Database-only cleanup misses copies created before
                // tracking was introduced, after a process restart, or by an
                // interrupted transfer, and those copies can consume the whole quota.
                Set<String> presentIds = cacheFileIds(personalPage(cacheFolderId));
                for (String fid : presentIds) {
                    if (protectedIds.contains(fid) || deletedIds.contains(fid) || OpenDriveCopyUsage.busy(id(), fid)) continue;
                    deleteTracked(fid);
                    deletedIds.add(fid);
                    OpenDriveCacheSupport.deleted(CloudStorageProvider.UC,
                            savedAccounts.remove(fid), fid);
                    folderFiles++;
                }
                reconcileMissingCacheRecords(cacheFolderId, presentIds, deletedIds);
            } catch (Exception error) {
            OpenDriveLog.providerWarn(id(), "cache-folder-inspect-failed",
                    "error=" + error.getClass().getSimpleName());
            }
            purged += purgeOwnedRecycle(cacheFolderId, deletedIds);
        }
        if (tracked + folderFiles + purged > 0) {
            OpenDriveLog.providerInfo(id(), "cache-reclaimed",
                    "tracked=" + tracked + " folderFiles=" + folderFiles
                            + " purged=" + purged + " folders=" + cacheFolderIds.size());
        }
        return tracked + folderFiles + purged;
    }

    private int purgeOwnedRecycle(String folderId, Set<String> newlyDeletedFids) {
        List<CloudCacheRecord> records = List.of();
        try {
            String account = OpenDriveCacheSupport.accountHash(API);
            records = CloudStorageRepository.records(
                    CloudStorageProvider.UC, account, folderId);
        } catch (RuntimeException error) {
            OpenDriveLog.providerWarn(id(), "recycle-ownership-failed",
                    "error=" + error.getClass().getSimpleName());
        }
        Set<String> ownedFids = ownedRecycleFids(newlyDeletedFids, records);
        if (ownedFids.isEmpty()) return 0;
        for (int poll = 0; poll < 6; poll++) {
            try {
                JsonArray recycle = OpenDriveHttp.array(OpenDriveHttp.data(get("file/recycle/list", Map.of(
                        "_page", "1", "_size", String.valueOf(PAGE_SIZE))), "UC"), "list");
                List<String> recordIds = matchingRecycleRecordIds(recycle, ownedFids);
                if (!recordIds.isEmpty()) {
                    post("file/recycle/remove", OpenDriveHttp.json(
                            "select_mode", 2, "record_list", recordIds));
                    return recordIds.size();
                }
                if (newlyDeletedFids == null || newlyDeletedFids.isEmpty()
                        || poll + 1 >= 6) break;
                Thread.sleep(SAVE_TASK_POLL_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return 0;
            } catch (Exception error) {
            OpenDriveLog.providerWarn(id(), "recycle-purge-failed",
                    "error=" + error.getClass().getSimpleName());
                return 0;
            }
        }
        return 0;
    }

    static Set<String> ownedRecycleFids(Set<String> newlyDeletedFids,
                                        List<CloudCacheRecord> records) {
        Set<String> result = new LinkedHashSet<>();
        if (newlyDeletedFids != null) result.addAll(newlyDeletedFids);
        if (records != null) {
            for (CloudCacheRecord record : records) {
                if (record != null && record.remoteFileId != null
                        && !record.remoteFileId.isBlank()) result.add(record.remoteFileId);
            }
        }
        return result;
    }

    static List<String> matchingRecycleRecordIds(JsonArray recycle, Set<String> ownedFids) {
        if (recycle == null || ownedFids == null || ownedFids.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonElement element : recycle) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String fid = OpenDriveHttp.string(item, "fid");
            String recordId = OpenDriveHttp.string(item, "record_id");
            if (ownedFids.contains(fid) && !recordId.isBlank()) result.add(recordId);
        }
        return result;
    }

    static Set<String> cacheFileIds(List<JsonObject> values) {
        if (values == null || values.isEmpty()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (JsonObject value : values) {
            String fid = OpenDriveHttp.string(value, "fid");
            if (!fid.isBlank()) result.add(fid);
        }
        return result;
    }

    static Set<String> cacheFolderIds(List<JsonObject> values) {
        if (values == null || values.isEmpty()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (JsonObject value : values) {
            if (!OpenDriveHttp.bool(value, "dir") || !SAVE_FOLDER.equals(OpenDriveHttp.name(value, "file_name"))) continue;
            String fid = OpenDriveHttp.string(value, "fid");
            if (!fid.isBlank()) result.add(fid);
        }
        return result;
    }

    private void reconcileMissingCacheRecords(String folderId, Set<String> presentIds,
                                              Set<String> deletedIds) {
        try {
            String account = OpenDriveCacheSupport.accountHash(API);
            CloudStorageRepository.records(CloudStorageProvider.UC, account, folderId)
                    .forEach(record -> {
                        String fid = record.remoteFileId;
                        if (!presentIds.contains(fid) || deletedIds.contains(fid)) {
                            OpenDriveCacheSupport.deleted(
                                    CloudStorageProvider.UC, account, fid);
                        }
                    });
        } catch (RuntimeException error) {
            OpenDriveLog.providerWarn(id(), "cache-reconcile-failed",
                    "error=" + error.getClass().getSimpleName());
        }
    }

    private synchronized String ensureFolder() throws Exception {
        if (saveFolderId != null) return saveFolderId;
        JsonArray values = OpenDriveHttp.array(OpenDriveHttp.data(get("file/sort", Map.of(
                "pdir_fid", "0", "_page", "1", "_size", String.valueOf(PAGE_SIZE),
                "_sort", "file_type:asc,updated_at:desc")), "UC"), "list");
        for (JsonElement value : values) {
            if (!value.isJsonObject()) continue;
            JsonObject item = value.getAsJsonObject();
            if (OpenDriveHttp.bool(item, "dir") && SAVE_FOLDER.equals(OpenDriveHttp.name(item, "file_name"))) {
                return saveFolderId = OpenDriveHttp.required(item, "fid", "UC");
            }
        }
        return saveFolderId = OpenDriveHttp.required(OpenDriveHttp.data(post("file", folderCreateBody()), "UC"), "fid", "UC");
    }

    static JsonObject folderCreateBody() {
        return OpenDriveHttp.json("pdir_fid", "0", "file_name", SAVE_FOLDER,
                "dir_path", "", "dir_init_lock", "false");
    }

    private void cleanupSaved(String fid) {
        if (fid == null || fid.isBlank()) return;
        OpenDriveCopyUsage.retire(id(), fid);
        if (OpenDriveCopyUsage.busy(id(), fid)) return;
        boolean deleted = false;
        int purged = 0;
        try {
            deleteTracked(fid);
            deleted = true;
        } catch (Exception error) {
            OpenDriveLog.providerWarn(id(), "cache-delete-failed",
                    "error=" + error.getClass().getSimpleName());
        }
        try {
            purged = purgeOwnedRecycle(saveFolderId, Set.of(fid));
        } catch (RuntimeException error) {
            OpenDriveLog.providerWarn(id(), "recycle-cleanup-failed",
                    "error=" + error.getClass().getSimpleName());
        }
        if (deleted || purged > 0) {
            protectedSavedFids.remove(fid);
            savedSourceFids.entrySet().removeIf(entry -> fid.equals(entry.getValue()));
            OpenDriveCacheSupport.deleted(CloudStorageProvider.UC,
                    savedAccounts.remove(fid), fid);
        }
    }

    boolean deleteTracked(com.wuming.android.tv.cloud.CloudCacheRecord record) throws Exception {
        if (!com.wuming.tv.core.cloud.SavedCopyPolicy.owned(id(), OpenDriveCacheSupport.accountHash(API),
                com.wuming.android.tv.api.config.VodConfig.OPEN_DRIVE_KEY,
                record.provider, record.accountHash, record.sourceSiteKey,
                record.state == com.wuming.android.tv.cloud.CloudCacheRecord.ACTIVE)) return false;
        if (!com.wuming.android.tv.api.config.VodConfig.OPEN_DRIVE_KEY.equals(record.sourceSiteKey)) return false;
        deleteTracked(record.remoteFileId);
        return true;
    }

    void deleteTracked(String fid) throws Exception {
        if (fid == null || fid.isBlank()) throw new IllegalArgumentException("Tracked file ID is required");
        if (!OpenDriveCopyUsage.delete(id(), savedAccounts.getOrDefault(fid, "untracked"), fid, () -> {
            post("file/delete", OpenDriveHttp.json("action_type", 2,
                    "filelist", List.of(fid), "exclude_fids", List.of()));
            return true;
        })) throw new OpenDriveCopyUsage.InUse();
    }

    private JsonObject get(String endpoint, Map<String, String> query) throws Exception {
        UcCookies cookies = requireCredential();
        OpenDriveHttp.JsonResponse response;
        try {
            response = OpenDriveHttp.getExplicit(apiUrl(endpoint, query).toString(),
                    Collections.emptyMap(), headers(cookies, endpoint));
        } catch (OpenDriveHttp.StatusException error) {
            throw ucStatus(endpoint, error);
        }
        refreshCredential(cookies, response.setCookieHeaders());
        return response.body();
    }

    private JsonObject post(String endpoint, JsonObject body) throws Exception {
        UcCookies cookies = requireCredential();
        OpenDriveHttp.JsonResponse response;
        try {
            response = OpenDriveHttp.postJsonExplicit(
                    apiUrl(endpoint, Collections.emptyMap()).toString(), body,
                    headers(cookies, endpoint));
        } catch (OpenDriveHttp.StatusException error) {
            throw ucStatus(endpoint, error);
        }
        refreshCredential(cookies, response.setCookieHeaders());
        return response.body();
    }

    private String ensureUt() {
        String cached = dynamicUt;
        if (cached != null && !cached.isBlank()) return cached;
        try {
            String value = OpenDriveHttp.getTextExplicit(
                    API + "file", Collections.emptyMap());
            if (value != null && !value.isBlank()) {
                return dynamicUt = value.trim();
            }
        } catch (Exception ignored) {
        }
        return API_UT;
    }

    private String mediaApiUrl(String endpoint) {
        String query = "pr=UCBrowser&fr=pc&sys=darwin&ve=1.8.6&ut="
                + ensureUt() + "&uc_param_str=";
        HttpUrl parsed = HttpUrl.parse(API + endpoint + "?" + query);
        if (parsed == null) throw new IllegalArgumentException("Invalid UC media endpoint");
        return parsed.toString();
    }

    private DownloadResponse postDownload(JsonObject body) throws Exception {
        UcCookies cookies = requireCredential();
        String requestCookie = cookies.header();
        OpenDriveHttp.JsonResponse response;
        try {
            response = OpenDriveHttp.postJsonExplicit(
                    apiUrl("file/download", Collections.emptyMap()).toString(), body,
                    headers(requestCookie, "file/download"));
        } catch (OpenDriveHttp.StatusException error) {
            throw ucStatus("file/download", error);
        }
        // Keep every response cookie for this playback URL, while the stored account
        // credential below still accepts only UC's reviewed __puus refresh cookie.
        UcCookies playbackCookies = UcCookies.from(cookies.header());
        playbackCookies.merge(response.setCookieHeaders());
        refreshCredential(cookies, response.setCookieHeaders());
        // Persist the refreshed credential so later resolves reuse a valid cookie.
        OpenDriveTokenStore.put(id(), cookies.header());
        // AndroidCatVodSpider performs this request with the same CookieJar later used
        // by its player. Mirror that observable behaviour even though OpenDrive keeps
        // API calls on an explicit-cookie client: the raw signed URL is then played by
        // OkHttp.player() with the just-rotated UC cookies and no invented media headers.
        syncPlayerCookieJar(cookies);
        // uzVideo updates __puus from file/download before constructing playHeaders.
        return new DownloadResponse(response.body(), playbackCookies.header());
    }

    private ResolvedDownload resolveDownload(JsonObject request) throws Exception {
        DownloadResponse response = postDownload(request);
        JsonArray values = OpenDriveHttp.array(response.body, null);
        if (values.isEmpty() || !values.get(0).isJsonObject()) {
            return new ResolvedDownload(response, "", 0L);
        }
        JsonObject item = values.get(0).getAsJsonObject();
        return new ResolvedDownload(response, OpenDriveHttp.string(item, "download_url"),
                OpenDriveHttp.number(item, "size"));
    }

    private static void syncPlayerCookieJar(UcCookies cookies) {
        HttpUrl login = HttpUrl.parse(LOGIN);
        if (login == null) return;
        try {
            PersistentCookieJar.get().saveFromResponse(login, cookies.playCookies());
        } catch (IllegalStateException ignored) {
        }
    }

    private JsonObject postPlay(String fid) throws Exception {
        UcCookies cookies = requireCredential();
        JsonObject body = OpenDriveHttp.json(
                "fid", fid,
                "resolutions", "normal,low,high,super,2k,4k",
                "supports", "fmp4");
        OpenDriveHttp.JsonResponse response;
        try {
            response = OpenDriveHttp.postJsonExplicit(
                    mediaApiUrl("file/v2/play"), body,
                    headers(cookies.header(), "file/v2/play"));
        } catch (OpenDriveHttp.StatusException error) {
            throw ucStatus("file/v2/play", error);
        }
        refreshCredential(cookies, response.setCookieHeaders());
        return response.body();
    }

    static String pickTranscodedUrl(JsonObject response) {
        JsonArray videos = OpenDriveHttp.array(OpenDriveHttp.object(response, "data"), "video_list");
        String best = null;
        long bestWidth = -1L;
        for (JsonElement value : videos) {
            if (!value.isJsonObject()) continue;
            JsonObject info = OpenDriveHttp.object(value.getAsJsonObject(), "video_info");
            String url = OpenDriveHttp.string(info, "url");
            long width = OpenDriveHttp.number(info, "width");
            if (OpenDriveHttp.validHttpsUrl(url) && width > bestWidth) {
                best = url;
                bestWidth = width;
            }
        }
        return best;
    }

    static boolean directSharePlayback(boolean personal, boolean smart) {
        return !personal && !smart;
    }

    /** Prefer the saved-file transcode because share-download may be UC's membership notice. */
    static PlaybackCandidate selectPlaybackCandidate(String rawUrl, String transcodedUrl) {
        if (OpenDriveHttp.validHttpsUrl(transcodedUrl)) {
            return new PlaybackCandidate(transcodedUrl, true);
        }
        if (OpenDriveHttp.validHttpsUrl(rawUrl)) return new PlaybackCandidate(rawUrl, false);
        return null;
    }

    private static String safeHost(String value) {
        HttpUrl parsed = HttpUrl.parse(value);
        return parsed == null ? "" : parsed.host();
    }

    /** Builds the query in the same order as UCApi's literal {@code pr} string. */
    static HttpUrl apiUrl(String endpoint, Map<String, String> query) {
        // UCApi appends this whole string to the endpoint. Parsing the same
        // literal preserves the trailing "==" in ut; adding ut as an encoded
        // value changes it to "%3D%3D", which UC rejects on the task route.
        boolean sharepage = endpoint.startsWith("share/sharepage/");
        boolean download = "file/download".equals(endpoint);
        boolean save = "share/sharepage/save".equals(endpoint);
        boolean task = "task".equals(endpoint);
        String baseQuery = download ? DOWNLOAD_QUERY
                : save ? SAVE_QUERY
                : task ? TASK_QUERY
                : sharepage ? SHAREPAGE_QUERY : API_QUERY;
        HttpUrl parsed = HttpUrl.parse(API + endpoint + "?" + baseQuery);
        if (parsed == null) throw new IllegalArgumentException("Invalid UC API endpoint");
        HttpUrl.Builder url = parsed.newBuilder();
        if (!sharepage && !download && ("file/delete".equals(endpoint)
                || endpoint.startsWith("file/recycle/"))) {
            url.addQueryParameter("uc_param_str", "");
        }
        if (query != null) query.forEach(url::addQueryParameter);
        return url.build();
    }

    private static IOException ucStatus(String endpoint, OpenDriveHttp.StatusException error) {
        return new IOException("UC " + endpoint + " request failed (HTTP "
                + error.code() + ")", error);
    }

    static boolean playEndpointNotFound(Throwable error) {
        if (error == null || error.getMessage() == null
                || !error.getMessage().contains("file/v2/play")) return false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof OpenDriveHttp.StatusException status
                    && status.code() == 404) return true;
        }
        return false;
    }

    static boolean useRawAfterPlayFailure(Throwable error, boolean fallbackOnPlay404,
                                          String rawUrl) {
        return OpenDriveHttp.validHttpsUrl(rawUrl)
                && (fallbackOnPlay404 || !playEndpointNotFound(error));
    }

    private Map<String, String> headers(UcCookies cookies, String endpoint) {
        return headers(cookies.header(), endpoint);
    }

    private Map<String, String> headers(String cookie, String endpoint) {
        boolean saveOperation = "share/sharepage/save".equals(endpoint)
                || "task".equals(endpoint);
        boolean download = "file/download".equals(endpoint);
        String userAgent = download ? UC_PLAY_USER_AGENT
                : saveOperation ? OpenDriveHttp.USER_AGENT : USER_AGENT;
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", LOGIN);
        headers.put("User-Agent", userAgent);
        headers.put("Content-Type", "application/json");
        headers.put("Cookie", cookie);
        if (download || endpoint.startsWith("share/sharepage/")) {
            // Match the browser headers used by UC's transfer endpoints.
            headers.put("Origin", "https://drive.uc.cn");
            headers.put("Accept", "application/json, text/plain, */*");
        }
        return headers;
    }

    private Map<String, String> downloadHeaders(String requestCookie) {
        return downloadHeaders(requestCookie, "");
    }

    private Map<String, String> downloadHeaders(String requestCookie, String mediaUrl) {
        return downloadHeaders(requestCookie, mediaUrl, 0L);
    }

    private Map<String, String> downloadHeaders(String requestCookie, String mediaUrl,
                                                long expectedSize) {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "*/*");
        String host = safeHost(mediaUrl);
        boolean pds = "pds.uc.cn".equalsIgnoreCase(host)
                || host.toLowerCase(java.util.Locale.ROOT).endsWith(".pds.uc.cn");
        headers.put("Referer", pds ? "https://" + host + "/" : "https://drive.uc.cn/");
        headers.put("User-Agent", pds ? PDS_PLAY_USER_AGENT : UC_PLAY_USER_AGENT);
        String playCookie = UcCookies.filterPlayCookie(requestCookie);
        if (!playCookie.isBlank()) {
            headers.put("Cookie", playCookie);
        }
        if (pds && expectedSize > 0L) {
            // Consumed and removed by the parallel range source before the HTTP request.
            // The exact size lets its final bounded range stop at the object EOF.
            headers.putAll(OpenDrivePlaybackHints.parallelRanges(Map.of(),
                    OpenDrivePlaybackHints.PROFILE_UC_PDS, expectedSize));
        }
        return headers;
    }

    private UcCookies requireCredential() throws IOException {
        UcCookies cookies = credential();
        if (!cookies.has("__pus")) throw new IOException("UC credential is missing");
        return cookies;
    }

    private UcCookies credential() {
        UcCookies current = credentialCookies;
        if (current != null) return current;
        synchronized (this) {
            if (credentialCookies != null) return credentialCookies;
            UcCookies loaded = UcCookies.from(OpenDriveTokenStore.get(id()));
            if (!loaded.has("__pus")) loaded = legacyCredential();
            credentialCookies = loaded;
            if (loaded.has("__pus")) OpenDriveTokenStore.put(id(), loaded.header());
            return loaded;
        }
    }

    private static UcCookies legacyCredential() {
        UcCookies result = new UcCookies();
        try {
            HttpUrl url = HttpUrl.parse(API);
            if (url == null) return result;
            List<String> pairs = new ArrayList<>();
            for (Cookie cookie : PersistentCookieJar.get().loadForRequest(url)) {
                pairs.add(cookie.name() + "=" + cookie.value());
            }
            result.mergePairs(pairs);
        } catch (IllegalStateException ignored) {
        }
        return result.has("__pus") ? result : new UcCookies();
    }

    private void refreshCredential(UcCookies cookies, List<String> setCookieHeaders) {
        if (cookies.mergeNamed(setCookieHeaders, "__puus")) {
            OpenDriveTokenStore.put(id(), cookies.header());
        }
    }

    static final class UcCookies {
        private static final String COOKIE_NAME = "[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}";
        private final LinkedHashMap<String, String> values = new LinkedHashMap<>();

        static UcCookies from(String header) {
            UcCookies result = new UcCookies();
            if (header != null && !header.isBlank()) {
                result.mergePairs(List.of(header.split(";")));
            }
            return result;
        }

        synchronized boolean merge(List<String> setCookieHeaders) {
            if (setCookieHeaders == null || setCookieHeaders.isEmpty()) return false;
            List<String> pairs = new ArrayList<>();
            for (String header : setCookieHeaders) {
                if (header == null) continue;
                int end = header.indexOf(';');
                pairs.add(end < 0 ? header : header.substring(0, end));
            }
            return mergePairs(pairs);
        }

        synchronized boolean mergeNamed(List<String> setCookieHeaders, String expectedName) {
            if (setCookieHeaders == null || expectedName == null) return false;
            for (String header : setCookieHeaders) {
                CookiePair pair = pair(header == null ? "" : header.split(";", 2)[0]);
                if (pair != null && expectedName.equals(pair.name) && !pair.value.isEmpty()) {
                    return put(pair);
                }
            }
            return false;
        }

        synchronized boolean mergePairs(List<String> pairs) {
            boolean changed = false;
            for (String value : pairs) {
                CookiePair pair = pair(value);
                if (pair != null) changed |= put(pair);
            }
            return changed;
        }

        private boolean put(CookiePair pair) {
            String previous = values.put(pair.name, pair.value);
            return !pair.value.equals(previous);
        }

        synchronized boolean has(String name) {
            String value = values.get(name);
            return value != null && !value.isBlank();
        }

        synchronized String header() {
            StringBuilder result = new StringBuilder();
            values.forEach((name, value) -> {
                if (result.length() > 0) result.append("; ");
                result.append(name).append('=').append(value);
            });
            return result.toString();
        }

        synchronized String headerWithout(String excludedName) {
            StringBuilder result = new StringBuilder();
            values.forEach((name, value) -> {
                if (name.equals(excludedName)) return;
                if (result.length() > 0) result.append("; ");
                result.append(name).append('=').append(value);
            });
            return result.toString();
        }

        private static final Set<String> PLAY_COOKIE_NAMES = Set.of(
                "_UP_A4A_11_", "tfstk", "__uid", "__pus", "__kp", "__puus",
                // Anonymous share downloads return __pugs; omitting it makes the
                // signed PDS URL fail with RequestDeniedByCallback (HTTP 403).
                "__pugs");

        static String filterPlayCookie(String cookieHeader) {
            if (cookieHeader == null || cookieHeader.isBlank()) return "";
            StringBuilder result = new StringBuilder();
            for (String part : cookieHeader.split(";")) {
                String trimmed = part.trim();
                int separator = trimmed.indexOf('=');
                if (separator <= 0) continue;
                String name = trimmed.substring(0, separator).trim();
                if (!PLAY_COOKIE_NAMES.contains(name)) continue;
                if (result.length() > 0) result.append(';');
                result.append(trimmed);
            }
            return result.toString();
        }

        synchronized List<Cookie> playCookies() {
            List<Cookie> result = new ArrayList<>();
            values.forEach((name, value) -> {
                if (!PLAY_COOKIE_NAMES.contains(name)) return;
                result.add(new Cookie.Builder().name(name).value(value)
                        .domain("uc.cn").path("/").build());
            });
            return result;
        }

        private static CookiePair pair(String value) {
            String trimmed = value == null ? "" : value.trim();
            int separator = trimmed.indexOf('=');
            if (separator <= 0) return null;
            String name = trimmed.substring(0, separator).trim();
            String cookieValue = trimmed.substring(separator + 1).trim();
            if (!name.matches(COOKIE_NAME) || cookieValue.length() > 8192) return null;
            for (int index = 0; index < cookieValue.length(); index++) {
                char character = cookieValue.charAt(index);
                if (character <= 0x20 || character == 0x7f || character == ';') return null;
            }
            return new CookiePair(name, cookieValue);
        }
    }

    private record CookiePair(String name, String value) {
    }

    private String remember(FileRef ref) {
        return files.remember(ref);
    }

    @Override
    public boolean persistHandle(String handle) {
        return files.persist(handle);
    }

    private FileRef requireFile(String handle) {
        return files.require(handle);
    }

    private final class CleanupStream extends FilterInputStream {
        private final Response response;
        private final long requestStart;
        private final AtomicBoolean closed = new AtomicBoolean();
        private boolean firstByteLogged;
        private long bytesRead;

        CleanupStream(InputStream input, Response response, long requestStart) {
            super(input);
            this.response = response;
            this.requestStart = requestStart;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) recordRead(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) recordRead(count);
            return count;
        }

        private void recordRead(int count) {
            bytesRead += count;
            if (firstByteLogged) return;
            firstByteLogged = true;
            OpenDriveLog.providerInfo(id(), "proxy-first-byte",
                    "elapsedMs=" + elapsedMillis(requestStart));
        }

        @Override
        public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) return;
            try {
                super.close();
            } finally {
                response.close();
                long elapsedMs = Math.max(1L, elapsedMillis(requestStart));
                OpenDriveLog.providerInfo(id(), "proxy-complete",
                        "bytes=" + bytesRead + " elapsedMs=" + elapsedMs
                                + " bytesPerSecond=" + (bytesRead * 1000L / elapsedMs));
            }
        }
    }

    record FileRef(String fid, String fileToken, String shareId, String shareToken,
                   String passcode, boolean personal, boolean banned, boolean smart,
                   long size, int playbackModeVersion) {

        private FileRef(String fid, String fileToken, String shareId, String shareToken,
                        String passcode, boolean personal, boolean banned, boolean smart,
                        long size) {
            this(fid, fileToken, shareId, shareToken, passcode, personal, banned,
                    smart, size, 1);
        }

        private FileRef(String fid, String fileToken, String shareId, String shareToken,
                        String passcode, boolean personal, boolean banned) {
            this(fid, fileToken, shareId, shareToken, passcode, personal, banned,
                    false, 0L, 1);
        }

        FileRef {
            // Handles saved before original/smart separation used the merged resolver,
            // which followed the current smart save/transcode policy. Preserve that
            // behavior on restore instead of silently converting an old route to original.
            if (playbackModeVersion == 0) smart = true;
            playbackModeVersion = 1;
            size = Math.max(0L, size);
        }
    }

    private static final class UcQuotaException extends com.wuming.tv.core.cloud.OpenDriveFailure {

        private UcQuotaException(String message) {
            super("uc", Reason.QUOTA_EXHAUSTED, 0, 0, new IOException(message));
        }
    }

    private record FolderRef(String fid, int depth) {
    }

    private record StreamRef(String url, String cookie, JsonObject downloadRequest,
                             String savedFid, long expectedSize, boolean transcoded) {
    }

    private record DownloadResponse(JsonObject body, String cookie) {
    }

    private record ResolvedDownload(DownloadResponse response, String url, long size) {
    }

    record PlaybackCandidate(String url, boolean transcoded) {
    }
}
