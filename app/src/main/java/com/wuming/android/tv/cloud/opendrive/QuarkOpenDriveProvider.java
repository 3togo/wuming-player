package com.wuming.android.tv.cloud.opendrive;

import static com.wuming.android.tv.cloud.opendrive.QuarkNetwork.deleteEndpoint;
import static com.wuming.android.tv.cloud.opendrive.QuarkNetwork.downloadEndpoint;
import static com.wuming.android.tv.cloud.opendrive.QuarkNetwork.downloadEndpointWithoutEntry;
import static com.wuming.android.tv.cloud.opendrive.QuarkNetwork.endpoint;
import static com.wuming.android.tv.cloud.opendrive.QuarkNetwork.playEndpoint;

import com.wuming.android.tv.cloud.opendrive.QuarkNetwork.NetworkTransport;
import com.wuming.android.tv.cloud.opendrive.QuarkNetwork.QuarkHttpException;
import com.wuming.android.tv.cloud.opendrive.QuarkNetwork.QuarkQuotaException;
import com.wuming.android.tv.cloud.opendrive.QuarkNetwork.QuarkTransport;
import com.wuming.android.tv.cloud.opendrive.QuarkNetwork.Waiter;
import com.wuming.tv.core.cloud.*;
import com.wuming.tv.core.media.MediaFileClassifier;

import static com.wuming.android.tv.cloud.opendrive.OpenDriveHttp.array;
import static com.wuming.android.tv.cloud.opendrive.OpenDriveHttp.bool;
import static com.wuming.android.tv.cloud.opendrive.OpenDriveHttp.first;
import static com.wuming.android.tv.cloud.opendrive.OpenDriveHttp.json;
import static com.wuming.android.tv.cloud.opendrive.OpenDriveHttp.number;
import static com.wuming.android.tv.cloud.opendrive.OpenDriveHttp.object;
import static com.wuming.android.tv.cloud.opendrive.OpenDriveHttp.string;

import com.wuming.tv.core.playback.OpenDrivePlaybackHints;

import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.cloud.CloudStorageManager;
import com.wuming.android.tv.cloud.CloudStorageProvider;
import com.wuming.android.tv.cloud.CloudStorageRepository;
import com.wuming.android.tv.server.Server;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.PersistentCookieJar;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Reviewed Quark adapter. Capability tokens stay behind opaque, Keystore-backed handles. */
final class QuarkOpenDriveProvider extends AbstractOpenDriveProvider {

    private static final String SAVE_FOLDER = CloudStorageManager.CACHE_FOLDER;
    private static final String QR_CLIENT_ID = "532";
    private static final int PAGE_SIZE = 200;
    private static final int MAX_PAGES = 20;
    private static final int MAX_DIRECTORIES = 256;
    private static final int MAX_DEPTH = 20;
    private static final int MAX_EXTERNAL_PAYLOAD = 64 * 1024;
    private static final int MAX_CAPABILITY_LENGTH = 2048;
    private static final int SAVE_TASK_MAX_POLLS = 60;
    private static final long SAVE_TASK_POLL_INTERVAL_MILLIS = 500L;
    private static final int RECYCLE_MAX_POLLS = 6;
    private static final long STREAM_CACHE_MILLIS = TimeUnit.MINUTES.toMillis(15);
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private static final Pattern BYTE_RANGE = AbstractOpenDriveProvider.BYTE_RANGE;
    private static final Pattern SUFFIX_RANGE = AbstractOpenDriveProvider.SUFFIX_RANGE;
    private static final Pattern CONTENT_RANGE = AbstractOpenDriveProvider.CONTENT_RANGE;
    private static final int CAST_RANGE_BYTES = 128 * 1024;
    private static final int CAST_PARALLELISM = 32;

    private final OpenDriveFileHandles<FileRef> files = new OpenDriveFileHandles<>(id(), FileRef.class);
    private final ConcurrentHashMap<String, StreamRef> streams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> savedFileIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> savedAccounts = new ConcurrentHashMap<>();
    private final CloudAccountProvider loginDelegate;
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "QuarkOpenDriveCleanup");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean cleanupScheduled = new AtomicBoolean();
    private final QuarkTransport transport;
    private final BooleanSupplier loginState;
    private final Runnable clearLogin;
    private final Waiter waiter;
    private volatile String saveFolderId;

    QuarkOpenDriveProvider() {
        this(new NetworkTransport(),
                QuarkOpenDriveProvider::hasLoginCookie,
                QuarkOpenDriveProvider::clearLoginCookie, Thread::sleep);
    }

    QuarkOpenDriveProvider(QuarkTransport transport, BooleanSupplier loginState, Runnable clearLogin) {
        this(transport, loginState, clearLogin, Thread::sleep);
    }

    QuarkOpenDriveProvider(QuarkTransport transport, BooleanSupplier loginState,
                           Runnable clearLogin, Waiter waiter) {
        this.transport = transport;
        this.loginState = loginState;
        this.clearLogin = clearLogin;
        this.waiter = waiter;
        this.loginDelegate = new com.wuming.tv.core.runtime.cloudlogin.QuarkCloudAccountProvider(
                new AndroidCloudCredentialStore(), new AndroidCloudLoginTransport());
    }

    @Override
    public String id() {
        return "quark";
    }

    @Override
    public String displayName() {
        return "Quark Drive";
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
        return object(root, "data").size() > 0;
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
        String fid = "root".equals(parentId) ? "0" : requireFile(parentId).fid();
        return toItems(personalPage(fid));
    }

    @Override
    public boolean supportsShare(String url) {
        return canonicalShareUrl(url) != null;
    }

    @Override
    public List<OpenDriveItem> listShare(String url) throws Exception {
        String canonical = canonicalShareUrl(url);
        if (canonical == null) throw new IllegalArgumentException("Invalid Quark share link");
        String shareId = canonical.substring(canonical.lastIndexOf('/') + 1);
        String stoken = string(OpenDriveHttp.data(post("share/sharepage/token", json(
                "pwd_id", shareId, "passcode", sharePasscode(url, "Quark"))), "Quark"), "stoken");
        if (stoken.isEmpty()) throw new IOException("Quark rejected the share link");

        List<OpenDriveItem> result = new ArrayList<>();
        ArrayDeque<FolderRef> folders = new ArrayDeque<>();
        folders.add(new FolderRef("0", 0));
        int visited = 0;
        while (!folders.isEmpty()) {
            FolderRef folder = folders.removeFirst();
            if (++visited > MAX_DIRECTORIES) throw new IOException("Quark share contains too many folders");
            for (int page = 1; page <= MAX_PAGES; page++) {
                JsonObject root = get("share/sharepage/detail", Map.of(
                        "pwd_id", shareId,
                        "stoken", stoken,
                        "pdir_fid", folder.fid(),
                        "force", "0",
                        "_page", Integer.toString(page),
                        "_size", Integer.toString(PAGE_SIZE),
                        "_sort", "file_type:asc,file_name:asc"));
                JsonArray list = array(OpenDriveHttp.data(root, "Quark"), "list");
                for (JsonElement element : list) {
                    if (!element.isJsonObject()) continue;
                    JsonObject item = element.getAsJsonObject();
                    if (bool(item, "dir")) {
                        if (folder.depth() < MAX_DEPTH) folders.addLast(
                                new FolderRef(OpenDriveHttp.required(item, "fid", "Quark"), folder.depth() + 1));
                    } else if (OpenDriveHttp.isVideo(item, "obj_category", "file_name")) {
                        FileRef ref = new FileRef(OpenDriveHttp.required(item, "fid", "Quark"), OpenDriveHttp.required(item, "share_fid_token", "Quark"),
                                shareId, stoken, folder.fid(), false, "", number(item, "size"));
                        String handle = remember(ref);
                        result.add(new OpenDriveItem(handle, OpenDriveHttp.name(item, "file_name"), false, number(item, "size")));
                    }
                }
                if (list.size() < PAGE_SIZE) break;
                if (page == MAX_PAGES) throw new IOException("Quark share contains too many files");
            }
        }
        return result;
    }

    @Override
    public OpenDrivePreparedExternal prepareExternal(OpenDriveExternalPayload payload) {
        try {
            JsonObject item = payload.json(false);
            if (item == null || !ExternalEpisodePayload.belongsTo(
                    payload.flag(), item, "quark", "夸克", "夸父")) return null;
            String fid = first(item, "fid", "file_id", "fileId");
            String fileToken = first(item, "share_fid_token", "fid_token", "fileToken");
            String shareId = first(item, "sId", "share_id", "shareId", "pwd_id");
            String shareToken = first(item, "sToken", "stoken", "share_token", "shareToken");
            if (!validIdentifier(fid) || !validCapability(fileToken)
                    || !validIdentifier(shareId) || !validCapability(shareToken)) return null;
            String parentId = first(item, "pdir_fid", "parent_fid", "parentId");
            if (!validIdentifier(parentId)) parentId = "0";
            long size = Math.max(number(item, "size"), number(item, "file_size"));
            FileRef file = new FileRef(fid, fileToken, shareId, shareToken, parentId, false,
                    QuarkTranscodePolicy.transcodeResolution(payload.flag()), size);
            return OpenDrivePreparedExternal.of(() -> remember(file));
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public OpenDrivePlayback resolve(String itemId) throws Exception {
        FileRef file = requireFile(itemId);
        String savedFid = file.personal() ? file.fid() : saveSharedFile(file);
        PlaybackUrl playback;
        try {
            playback = requestPlaybackUrl(savedFid, file);
        } catch (Exception error) {
            if (!file.personal() && missingJustSavedFile(error)) {
                OpenDriveLog.fallback(id(), itemId, "saved-file-missing", "re-save-retry", "first-attempt-failed");
                cleanup(savedFid);
                savedFid = saveSharedFile(file);
                try {
                    playback = requestPlaybackUrl(savedFid, file);
                } catch (Exception retryError) {
                    if (!(retryError instanceof QuarkSmartQualityException)) cleanup(savedFid);
                    OpenDriveLog.unexpected(id(), itemId, retryError);
                    throw retryError;
                }
            } else {
                if (!file.personal() && !(error instanceof QuarkSmartQualityException)) cleanup(savedFid);
                throw error;
            }
        }
        MediaProbe mediaProbe = playback.transcoded()
                ? new MediaProbe(0L, 0L) : probeMedia(playback);
        OpenDriveLog.providerDebug(id(), "resolved",
                "mode=" + (playback.transcoded() ? "transcode" : "original"));
        if (playback.transcoded()) {
            // Quark's play endpoint can return either HLS or a complete MP4 for the same
            // rendition label. Classify the actual object: forcing every transcode through
            // the HLS parser produces Manifest Malformed for valid video/mp4 responses.
            String cookie = accountCookie();
            TranscodeProbe probe = probeTranscode(playback.url(), cookie);
            OpenDriveLog.providerDebug(id(), "transcode-classified",
                    "hls=" + probe.hls() + " contentLength=" + probe.contentLength());
            return OpenDriveCopyUsage.attach(new OpenDrivePlayback(playback.url(),
                    transcodePlaybackHeaders(cookie, probe.hls(), probe.contentLength()),
                    System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10)),
                    id(), savedAccounts.get(savedFid), savedFid);
        }
        String stream = UUID.randomUUID().toString();
        StreamRef streamRef = new StreamRef(
                playback.url(), savedFid, file, playback.transcoded(), playback.cookie(),
                mediaProbe.offset());
        streams.put(stream, streamRef);
        scheduleCleanup();
        String url = HttpUrl.parse(Server.get().getAddress("/proxy")).newBuilder()
                .addQueryParameter("siteKey", VodConfig.OPEN_DRIVE_KEY)
                .addQueryParameter("provider", id())
                .addQueryParameter("stream", stream)
                .build().toString();
        Map<String, String> playbackHeaders = originalPlaybackHeaders(mediaProbe.contentLength());
        if (playback.smartOriginalFallback()) {
            OpenDriveLog.fallback(id(), itemId, "smart-transcode", "original", "quark-not-video");
            playbackHeaders.put(OpenDrivePlaybackHints.QUARK_SMART_ORIGINAL_FALLBACK, "true");
            playbackHeaders = OpenDrivePlaybackHints.fallback(playbackHeaders,
                    OpenDrivePlaybackHints.FallbackReason.QUARK_NOT_VIDEO);
        }
        return OpenDriveCopyUsage.attach(new OpenDrivePlayback(url, playbackHeaders,
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10)),
                id(), savedAccounts.get(savedFid), savedFid);
    }

    static Map<String, String> originalPlaybackHeaders(long contentLength) {
        return OpenDrivePlaybackHints.parallelRanges(Collections.emptyMap(),
                OpenDrivePlaybackHints.PROFILE_QUARK_ORIGINAL, contentLength);
    }

    static Map<String, String> transcodePlaybackHeaders(String cookie, boolean hls) {
        return transcodePlaybackHeaders(cookie, hls, 0L);
    }

    static Map<String, String> transcodePlaybackHeaders(
            String cookie, boolean hls, long contentLength) {
        java.util.LinkedHashMap<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("User-Agent", QuarkNetwork.TRANSCODE_USER_AGENT);
        headers.put("Referer", "https://pan.quark.cn/");
        if (cookie != null && !cookie.isBlank()) headers.put("Cookie", cookie);
        if (hls) return OpenDrivePlaybackHints.hls(headers, headers.containsKey("Cookie"));
        if (headers.containsKey("Cookie")) {
            headers.put(OpenDrivePlaybackHints.EXPLICIT_COOKIE, "true");
        }
        // Quark throttles progressive playback per connection. The probe has already proven
        // exact byte-range support and supplied the full object length, so use the same bounded
        // multi-range downloader that keeps original Quark files ahead of playback.
        return OpenDrivePlaybackHints.parallelRanges(headers,
                OpenDrivePlaybackHints.PROFILE_QUARK_ORIGINAL, contentLength);
    }

    private TranscodeProbe probeTranscode(String url, String cookie) {
        try (Response response = transport.open(url, "bytes=0-63", cookie)) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                return new TranscodeProbe(urlLooksLikeHls(url), 0L);
            }
            byte[] prefix = readPrefix(body.byteStream(), 64);
            String mime = body.contentType() == null ? "" : body.contentType().toString();
            boolean hls = transcodeIsHls(mime, prefix, url);
            long contentLength = hls ? 0L : transcodeRangeLength(
                    response.code(), response.header("Content-Range"));
            return new TranscodeProbe(hls, contentLength);
        } catch (Exception error) {
            OpenDriveLog.providerWarn(id(), "transcode-classify-failed",
                    "error=" + error.getMessage());
            return new TranscodeProbe(urlLooksLikeHls(url), 0L);
        }
    }

    static long transcodeRangeLength(int status, String contentRange) {
        if (status != 206) return 0L;
        LogicalRange range = LogicalRange.parse(contentRange);
        if (range == null || range.start() != 0L || range.total() <= range.length()) return 0L;
        long expectedEnd = Math.min(63L, range.total() - 1L);
        return range.end() == expectedEnd ? range.total() : 0L;
    }

    static boolean transcodeIsHls(String mime, byte[] prefix, String url) {
        if (startsWith(prefix, "#EXTM3U")) return true;
        // Prefer observed container signatures over unreliable CDN Content-Type values.
        if (prefix != null && (prefix.length >= 8
                && prefix[4] == 'f' && prefix[5] == 't' && prefix[6] == 'y' && prefix[7] == 'p'
                || prefix.length >= 4 && (prefix[0] & 0xff) == 0x1a
                && (prefix[1] & 0xff) == 0x45 && (prefix[2] & 0xff) == 0xdf
                && (prefix[3] & 0xff) == 0xa3)) return false;
        String normalized = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (normalized.contains("mpegurl") || normalized.contains("m3u8")) return true;
        if (normalized.startsWith("video/") || normalized.startsWith("audio/")
                || normalized.startsWith("application/mp4")
                || normalized.startsWith("application/octet-stream")) return false;
        return urlLooksLikeHls(url);
    }

    private static boolean startsWith(byte[] value, String prefix) {
        if (value == null || value.length < prefix.length()) return false;
        for (int index = 0; index < prefix.length(); index++) {
            if (value[index] != (byte) prefix.charAt(index)) return false;
        }
        return true;
    }



    private static String accountCookie() {
        try {
            return OkHttp.cookieHeader(QuarkNetwork.API);
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    @Override
    public OpenDriveProxyResponse proxy(Map<String, String> params) throws Exception {
        String handle = params.get("stream");
        StreamRef stream = handle == null ? null : streams.get(handle);
        if (stream == null) throw new IllegalArgumentException("Playback session expired; reopen the share");
        stream.open();
        Response response;
        try {
            response = openPlayback(stream, params.get("range"));
        } catch (Exception error) {
            stream.close();
            throw error;
        }
        ResponseBody body = response.body();
        if (!response.isSuccessful() || body == null) {
            response.close();
            stream.close();
            throw new IOException("Quark playback request failed");
        }
        String mime = stream.mediaOffset() > 0L ? "video/mp4"
                : body.contentType() == null ? "application/octet-stream" : body.contentType().toString();
        OpenDriveLog.providerDebug(id(), "playback-upstream",
                "mode=" + (stream.transcoded() ? "transcode" : "original")
                        + " status=" + response.code()
                        + " mime=" + mime
                        + " ranged=" + (response.header("Content-Range") != null));
        String contentRange = translatedContentRange(
                response.header("Content-Range"), stream.mediaOffset());
        Map<String, String> headers = proxyResponseHeaders(contentRange, body.contentLength());
        LogicalRange logicalRange = LogicalRange.parse(contentRange);
        if (shouldUseParallelCast(params, stream, logicalRange)) {
            response.close();
            OpenDriveLog.providerDebug(id(), "cast-parallel-open",
                    "start=" + logicalRange.start() + " bytes=" + logicalRange.length()
                            + " parts=" + CAST_PARALLELISM);
            InputStream input = new QuarkParallelCastStream(
                    (streamRef, range) -> openPlaybackConnection(streamRef, range),
                    stream, logicalRange.start(), logicalRange.length(),
                    CAST_RANGE_BYTES, CAST_PARALLELISM);
            return new OpenDriveProxyResponse(response.code(), mime, input, headers);
        }
        InputStream input = new CleanupStream(body.byteStream(), response, stream);
        return new OpenDriveProxyResponse(response.code(), mime, input, headers);
    }

    static boolean shouldUseParallelCast(Map<String, String> params, StreamRef stream,
                                         LogicalRange logicalRange) {
        return params != null && "parallel".equals(params.get("quarkCast"))
                // openPlaybackConnection already sends bytes=0- for a plain renderer GET.
                // Use its validated Content-Range; a renderer-supplied Range is not required.
                && stream != null && !stream.transcoded() && logicalRange != null
                && logicalRange.length() > CAST_RANGE_BYTES;
    }

    static Map<String, String> proxyResponseHeaders(String contentRange, long bodyLength) {
        return OpenDriveProxyResponse.mediaHeaders(contentRange, bodyLength);
    }

    Response openPlayback(StreamRef stream, String range) throws Exception {
        return openPlaybackConnection(stream, range).response();
    }

    private PlaybackConnection openPlaybackConnection(StreamRef stream, String range)
            throws Exception {
        // The Quark download host can close an unbounded HTTP 200 stream after roughly one
        // minute without Content-Length. An explicit open-ended RFC 7233 range is accepted by
        // the same live endpoint as HTTP 206 with Content-Range, giving Media3 the file length
        // from its first request and allowing MP4 tail seeks.
        String playbackRange = translatedRange(range, stream.mediaOffset());
        String attemptedUrl = stream.url();
        PlaybackConnection connection = transport.openConnection(
                attemptedUrl, playbackRange, stream.cookie());
        Response response = connection.response();
        if (response.isSuccessful() && response.body() != null) {
            if (!isPngResponse(response)) return connection;
            response.close();
            OpenDriveLog.providerWarn(id(), "url-expired", "reason=png-body");
        } else {
            response.close();
        }

        // Quark direct URLs expire. The maintained Gopeed Quark extension first asks
        // file/download for a fresh URL using the existing saved fid, and only saves the
        // original shared file again when that fid is no longer usable.
        refreshStream(stream, attemptedUrl);
        return transport.openConnection(stream.url(), playbackRange, stream.cookie());
    }

    private boolean isPngResponse(Response response) {
        ResponseBody body = response.body();
        if (body == null) return false;
        try {
            // Use Response.peekBody to read the first 8 bytes without consuming the stream.
            // Reading body.byteStream() directly would consume bytes and leave the
            // response stream at offset 8, causing ExoPlayer to miss the MP4 ftyp
            // box and fail with UnrecognizedInputFormatException (NoDeclaredBrand).
            ResponseBody peeked = response.peekBody(8L);
            byte[] prefix = readPrefix(peeked.byteStream(), 8);
            for (int i = 0; i < PNG_SIGNATURE.length; i++) {
                if (prefix[i] != PNG_SIGNATURE[i]) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private MediaProbe probeMedia(PlaybackUrl playback) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Playback cancelled");
        try (Response response = transport.openProbe(
                playback.url(), "bytes=0-15", playback.cookie())) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) return new MediaProbe(0L, 0L);
            byte[] prefix = readPrefix(body.byteStream(), 16);
            long offset = prefixedMp4Offset(prefix);
            long contentLength = logicalContentLength(
                    response.header("Content-Range"), offset);
            if (offset == 8L) {
                OpenDriveLog.providerDebug(id(), "mp4-prefix-normalized", "bytes=8");
            }
            OpenDriveLog.providerDebug(id(), "media-probed",
                    "length=" + contentLength + " offset=" + offset);
            return new MediaProbe(offset, contentLength);
        } catch (Exception error) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Playback cancelled");
            OpenDriveLog.providerWarn(id(), "media-probe-failed",
                    "error=" + error.getMessage());
        }
        return new MediaProbe(0L, 0L);
    }

    public static byte[] readPrefix(InputStream input, int limit) throws IOException {
        return AbstractOpenDriveProvider.readPrefix(input, limit);
    }

    static long logicalContentLength(String value, long offset) {
        if (value == null) return 0L;
        Matcher matcher = CONTENT_RANGE.matcher(value);
        if (!matcher.matches() || "*".equals(matcher.group(3))) return 0L;
        try {
            long total = Long.parseLong(matcher.group(3));
            return total > offset ? total - offset : 0L;
        } catch (NumberFormatException error) {
            return 0L;
        }
    }

    static long prefixedMp4Offset(byte[] value) {
        if (value == null || value.length < 16) return 0L;
        for (int index = 0; index < PNG_SIGNATURE.length; index++) {
            if (value[index] != PNG_SIGNATURE[index]) return 0L;
        }
        long boxSize = ((long) value[8] & 0xffL) << 24
                | ((long) value[9] & 0xffL) << 16
                | ((long) value[10] & 0xffL) << 8
                | ((long) value[11] & 0xffL);
        return boxSize >= 8L && value[12] == 'f' && value[13] == 't'
                && value[14] == 'y' && value[15] == 'p' ? 8L : 0L;
    }

    static boolean missingJustSavedFile(Exception error) {
        return error instanceof QuarkHttpException quark
                && quark.code() == 404 && quark.apiCode() == 21_001L;
    }

    static String translatedRange(String range, long offset) throws IOException {
        // The Quark download host (dl-pc-zb.drive.quark.cn) can close an unbounded HTTP 200
        // stream after roughly one minute without Content-Length, and some download URLs return
        // an HTML error page or redirect instead of the file when no Range header is present.
        // An explicit open-ended RFC 7233 range (bytes=0-) is accepted by the same live endpoint
        // as HTTP 206 with Content-Range, giving Media3 the file length from its first request
        // and ensuring the CDN returns the actual file bytes rather than an error page.
        if (range == null || range.isEmpty()) return "bytes=" + offset + '-';
        if (offset <= 0L || SUFFIX_RANGE.matcher(range).matches()) return range;
        Matcher matcher = BYTE_RANGE.matcher(range);
        if (!matcher.matches()) {
            throw new IOException("Unsupported Quark playback range: " + range);
        }
        try {
            long start = Math.addExact(Long.parseLong(matcher.group(1)), offset);
            String endValue = matcher.group(2);
            if (endValue.isEmpty()) return "bytes=" + start + '-';
            long end = Math.addExact(Long.parseLong(endValue), offset);
            return "bytes=" + start + '-' + end;
        } catch (NumberFormatException | ArithmeticException error) {
            throw new IOException("Invalid Quark playback range: " + range, error);
        }
    }

    static String translatedContentRange(String value, long offset) {
        if (value == null || offset <= 0L) return value;
        Matcher matcher = CONTENT_RANGE.matcher(value);
        if (!matcher.matches()) return value;
        try {
            long start = Long.parseLong(matcher.group(1)) - offset;
            long end = Long.parseLong(matcher.group(2)) - offset;
            if (start < 0L || end < start) return value;
            long totalValue = "*".equals(matcher.group(3))
                    ? -1L : Long.parseLong(matcher.group(3)) - offset;
            if (totalValue != -1L && totalValue <= end) return value;
            String total = totalValue == -1L ? "*" : Long.toString(totalValue);
            return "bytes " + start + '-' + end + '/' + total;
        } catch (NumberFormatException error) {
            return value;
        }
    }

    private void refreshStream(StreamRef stream, String failedUrl) throws Exception {
        synchronized (stream) {
            // ExoPlayer can issue concurrent range requests. Reuse a URL already refreshed
            // by another request instead of creating duplicate files in the user's drive.
            if (!failedUrl.equals(stream.url())) return;
            try {
                PlaybackUrl refreshed = requestPlaybackUrl(
                        stream.savedFid(), stream.source());
                stream.update(refreshed.url(), stream.savedFid(), refreshed.transcoded(),
                        refreshed.cookie());
                return;
            } catch (Exception refreshError) {
                if (stream.source().personal()) throw refreshError;
                OpenDriveLog.providerWarn(id(), "refresh-failed-resave",
                        "error=" + refreshError.getMessage());
            }

            String oldFid = stream.savedFid();
            String newFid = saveSharedFile(stream.source());
            try {
                PlaybackUrl refreshed = requestPlaybackUrl(newFid, stream.source());
                stream.update(refreshed.url(), newFid, refreshed.transcoded(),
                        refreshed.cookie());
            } catch (Exception error) {
                cleanup(newFid);
                throw error;
            }
            cleanup(oldFid);
        }
    }

    String requestDownloadUrl(String savedFid) throws Exception {
        return requestDownload(savedFid).url();
    }

    private DownloadLink requestDownload(String savedFid) throws Exception {
        JsonObject request = json("fids", List.of(savedFid));
        DownloadResponse download;
        try {
            download = transport.postDownload(downloadEndpoint(), request);
        } catch (QuarkHttpException error) {
            // Current LinkSwift uses entry=ft. The maintained Gopeed extension uses the
            // same endpoint without entry, so retain that proven shape only as a bounded
            // compatibility fallback when Quark says the LinkSwift route does not exist.
            if (error.code() != 404) throw error;
            download = transport.postDownload(downloadEndpointWithoutEntry(), request);
        }
        return parseDownloadResponse(download, savedFid);
    }

    private DownloadLink parseDownloadResponse(DownloadResponse download, String savedFid) throws IOException {
        JsonObject root = download.body();
        if (number(root, "code") == 23_018L) {
            throw new IOException("Quark account risk control is active; try again later");
        }
        JsonElement data = root.get("data");
        JsonArray downloads = data != null && data.isJsonArray()
                ? data.getAsJsonArray()
                : data != null && data.isJsonObject()
                ? array(data.getAsJsonObject(), "list") : new JsonArray();
        if (downloads.isEmpty() && number(root, "code") != 0L) throw apiError(root);
        if (downloads.isEmpty()) throw new IOException("Quark did not return a download URL");
        String fallback = "";
        for (JsonElement item : downloads) {
            String candidate = item.isJsonObject()
                    ? string(item.getAsJsonObject(), "download_url")
                    : item.isJsonPrimitive() ? item.getAsString() : "";
            if (!validDownloadUrl(candidate)) continue;
            if (fallback.isEmpty()) fallback = candidate;
            if (item.isJsonObject() && savedFid.equals(string(item.getAsJsonObject(), "fid"))) {
                return new DownloadLink(candidate, download.cookie());
            }
        }
        if (!fallback.isEmpty()) return new DownloadLink(fallback, download.cookie());
        throw new IOException("Quark returned an invalid download URL");
    }

    PlaybackUrl requestPlaybackUrl(String savedFid, FileRef file) throws Exception {
        com.wuming.android.tv.utils.QuarkPlaybackTrace.write("quark requested=" + file.transcodeResolution());
        if (file.transcodeResolution().isBlank()) {
            DownloadLink download = requestDownload(savedFid);
            return new PlaybackUrl(download.url(), false, download.cookie());
        }
        try {
            JsonObject selected = requestTranscode(savedFid, file.transcodeResolution());
            // Smart selection must retain its quality bounds even on provider failures.
            return new PlaybackUrl(string(object(selected, "video_info"), "url"), true, "");
        } catch (QuarkHttpException error) {
            com.wuming.android.tv.utils.QuarkPlaybackTrace.write(
                    "transcode http=" + error.code() + " apiCode=" + error.apiCode());
            if (!transcodeUnavailable(error)) throw error;
            if ("auto".equalsIgnoreCase(file.transcodeResolution())) {
                // Quark cannot transcode a PNG-wrapped video because its cloud metadata says
                // "not video". The original path normalizes that envelope and was verified to
                // hardware-decode on the same fixture. Fall back only for this exact API cause;
                // do not broaden this to entitlement, absent-rendition, or generic API errors.
                if (error.apiCode() == 21_005L) {
                    DownloadLink download = requestDownload(savedFid);
                    return new PlaybackUrl(download.url(), false, download.cookie(), true);
                }
                // Other smart failures have no verified safe substitute. Preserve the user's
                // accepted low-quality rendition when present and keep the 1080p ceiling.
                throw new QuarkSmartQualityException();
            }
            OpenDriveLog.providerWarn(id(), "transcode-unavailable",
                    "apiCode=" + error.apiCode() + " plfInvalid=" + error.plfInvalid());
            DownloadLink download = requestDownload(savedFid);
            return new PlaybackUrl(download.url(), false, download.cookie());
        }
    }

    static boolean transcodeUnavailable(QuarkHttpException error) {
        // Measured 2026-09-10: E01.mkv was classified as image/png; HTTP 400 / 21005
        // explicitly said "not video". Do not reinterpret this as expired login/membership,
        // clear credentials, or promise that changing quality ranks will enable transcoding.
        // The normalized original can still play. See docs/quark-smart-quality-lessons.md.
        return error != null && (error.plfInvalid()
                || error.code() == 400 && error.apiCode() == 21_005L);
    }

    private JsonObject requestTranscode(String savedFid, String requestedResolution) throws Exception {
        JsonObject root = transport.postTranscode(playEndpoint(), json(
                "fid", savedFid,
                "resolutions", "normal,low,high,super,2k,4k",
                "supports", "fmp4"));
        JsonArray videos = array(OpenDriveHttp.data(root, "Quark"), "video_list");
        Set<String> resolutions = new LinkedHashSet<>();
        for (JsonElement element : videos) {
            if (element.isJsonObject()) {
                JsonObject candidate = element.getAsJsonObject();
                resolutions.add(string(candidate, "resolution"));
                com.wuming.android.tv.utils.QuarkPlaybackTrace.write("candidate="
                        + string(candidate, "resolution") + " playable="
                        + validDownloadUrl(string(object(candidate, "video_info"), "url"))
                        + " accessable=" + diagnosticStatus(candidate, "accessable")
                        + " trans_status=" + diagnosticStatus(candidate, "trans_status")
                        + " right=" + diagnosticStatus(candidate, "right")
                        + " member_right=" + diagnosticStatus(candidate, "member_right")
                        + " width=" + diagnosticStatus(object(candidate, "video_info"), "width")
                        + " height=" + diagnosticStatus(object(candidate, "video_info"), "height"));
            }
        }
        JsonObject selected = QuarkTranscodePolicy.selectTranscode(videos, requestedResolution);
        OpenDriveLog.providerInfo(id(), "transcode-selected",
                "requested=" + requestedResolution
                        + " selected=" + string(selected, "resolution")
                        + " available=" + resolutions);
        com.wuming.android.tv.utils.QuarkPlaybackTrace.write("selected="
                + string(selected, "resolution") + " available=" + resolutions);
        return selected;
    }

    private static String diagnosticStatus(JsonObject value, String key) {
        JsonElement field = value.get(key);
        if (field == null || !field.isJsonPrimitive()) return "unknown";
        if (field.getAsJsonPrimitive().isNumber() || field.getAsJsonPrimitive().isBoolean()) {
            return field.getAsString();
        }
        return "unknown";
    }

    @Override
    public void credentialsRestored() {
        files.clear();
        streams.clear();
        savedFileIds.clear();
        savedAccounts.clear();
        saveFolderId = null;
    }

    @Override
    public void logout() throws Exception {
        loginDelegate.logout();
        files.clearPersisted();
        streams.values().forEach(stream -> cleanup(stream.ownedFid()));
        streams.clear();
        savedFileIds.clear();
        savedAccounts.clear();
        saveFolderId = null;
    }

    @Override
    public void shutdown() {
        cleanupExecutor.shutdownNow();
    }

    static String canonicalShareUrl(String value) {
        String canonical = AbstractOpenDriveProvider.canonicalShareUrl(value, "pan.quark.cn");
        if (canonical == null) return null;
        // Preserve the existing side effect of validating the passcode shape.
        sharePasscode(value);
        return canonical;
    }

    static String sharePasscode(String value) {
        return AbstractOpenDriveProvider.sharePasscode(value, "Quark");
    }

    static String qrContent(String token) {
        if (!validLoginValue(token)) throw new IllegalArgumentException("Invalid Quark QR token");
        return "https://su.quark.cn/4_eMHBJ"
                + "?uc_param_str=&token=" + token
                + "&client_id=" + QR_CLIENT_ID
                + "&uc_biz_str=S%3Acustom%7COPT%3ASAREA%400%7COPT%3AIMMERSIVE%401"
                + "%7COPT%3ABACK_BTN_STYLE%400";
    }

    private static boolean hasLoginCookie() {
        try {
            PersistentCookieJar jar = PersistentCookieJar.get();
            // AndroidCatVodSpider's QuarkApi accepts the account as soon as
            // the QR exchange yields __pus. Quark may add/refresh __puus on
            // subsequent drive API calls, so requiring only __puus rejects a
            // valid freshly scanned reference login.
            return jar.hasCookie(QuarkNetwork.API, "__pus") || jar.hasCookie(QuarkNetwork.API, "__puus");
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    private static void clearLoginCookie() {
        try {
            PersistentCookieJar jar = PersistentCookieJar.get();
            jar.clear("quark.cn");
            jar.clearWebView("https://pan.quark.cn/");
        } catch (IllegalStateException ignored) {
        }
    }

    private List<JsonObject> personalPage(String fid) throws Exception {
        JsonArray values = array(OpenDriveHttp.data(get("file/sort", Map.of(
                "pdir_fid", fid, "_page", "1", "_size", Integer.toString(PAGE_SIZE),
                "_sort", "file_type:asc,updated_at:desc")), "Quark"), "list");
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement value : values) if (value.isJsonObject()) result.add(value.getAsJsonObject());
        return result;
    }

    private List<OpenDriveItem> toItems(List<JsonObject> values) {
        List<OpenDriveItem> result = new ArrayList<>();
        for (JsonObject value : values) {
            boolean directory = bool(value, "dir");
            if (!directory && !OpenDriveHttp.isVideo(value, "obj_category", "file_name")) continue;
            String handle = remember(new FileRef(OpenDriveHttp.required(value, "fid", "Quark"), "", "", "", "", true,
                    "", number(value, "size")));
            result.add(new OpenDriveItem(handle, OpenDriveHttp.name(value, "file_name"), directory, number(value, "size")));
        }
        return result;
    }

    private synchronized String saveSharedFile(FileRef file) throws Exception {
        String sourceKey = savedFileKey(file);
        String cachedFid = savedFileIds.get(sourceKey);
        if (cachedFid != null && !cachedFid.isBlank()) return cachedFid;
        String folderId = ensureSaveFolder();
        ensureCapacityForSave(file, folderId);
        reclaimCacheAtLimit(folderId);
        try {
            String fid = saveSharedFileOnce(file, folderId);
            savedFileIds.put(sourceKey, fid);
            return fid;
        } catch (QuarkQuotaException quota) {
            // Episode selection can start the next save a few milliseconds before Media3
            // closes the previous stream. The first sweep must protect that live stream, but
            // the failed save takes long enough for it to become inactive. Retry only when a
            // second sweep proves that it reclaimed app-owned cache; never delete user files.
            if (reclaimInactiveCache(folderId) == 0) throw quota;
            String fid = saveSharedFileOnce(file, folderId);
            savedFileIds.put(sourceKey, fid);
            return fid;
        }
    }

    /** Matches AndroidCatVod's proven Quark lifecycle: keep saved files until the
     * dedicated cache folder reaches ten entries instead of deleting on every play. */
    private void reclaimCacheAtLimit(String folderId) {
        try {
            if (personalPage(folderId).size() < 10) return;
            savedFileIds.clear();
            reclaimInactiveCache(folderId);
        } catch (Exception error) {
            OpenDriveLog.providerWarn(id(), "cache-inspect-failed",
                    "error=" + error.getMessage());
        }
    }

    private static String savedFileKey(FileRef file) {
        return file.shareId() + '\n' + file.fid();
    }

    private void ensureCapacityForSave(FileRef file, String folderId) throws Exception {
        if (file.size() <= 0L) return;
        try {
            long available = availableCapacity(get("member", Collections.emptyMap()));
            if (available < 0L || available >= file.size()) return;
            savedFileIds.clear();
            int reclaimed = reclaimInactiveCache(folderId);
            OpenDriveLog.providerDebug(id(), "quota-preflight",
                    "required=" + file.size() + " available=" + available + " reclaimed=" + reclaimed);
            if (reclaimed == 0) {
                throw new QuarkQuotaException("Quark cloud space is insufficient; "
                        + "no disposable OpenDrive cache remains");
            }
        } catch (QuarkQuotaException error) {
            throw error;
        } catch (Exception error) {
            // The save task reports authoritative remain_capacity/min_save_file_size. A
            // transient member-endpoint failure must not prevent an otherwise valid play.
            OpenDriveLog.providerWarn(id(), "quota-check-failed",
                    "error=" + error.getMessage());
        }
    }

    static long availableCapacity(JsonObject root) {
        JsonObject data = object(root, "data");
        long total = number(data, "total_capacity");
        long used = number(data, "use_capacity");
        if (total <= 0L || used < 0L || used > total) return -1L;
        return total - used;
    }

    private void forgetSavedFid(String fid) {
        if (fid == null || fid.isBlank()) return;
        savedFileIds.entrySet().removeIf(entry -> fid.equals(entry.getValue()));
    }

    private String saveSharedFileOnce(FileRef file, String folderId) throws Exception {
        JsonObject saved = OpenDriveHttp.data(post("share/sharepage/save", json(
                "fid_list", List.of(file.fid()),
                "fid_token_list", List.of(file.fileToken()),
                "to_pdir_fid", folderId,
                "pwd_id", file.shareId(),
                "stoken", file.shareToken(),
                "pdir_fid", "0",
                "scene", "link")), "Quark");
        String taskId = string(saved, "task_id");
        if (taskId.isEmpty()) throw new IOException("Quark could not save the shared file");
        String fid = awaitSavedFid(taskId);
        // Safe identities distinguish a new save from a server-replayed completed task.
        // Immediate test deletion/re-save returned the same task and deleted destination.
        com.wuming.android.tv.utils.QuarkPlaybackTrace.write("save identity task="
                + Integer.toHexString(taskId.hashCode()) + " destination=" + Integer.toHexString(fid.hashCode()));
        try {
            String account = OpenDriveCacheSupport.record(CloudStorageProvider.QUARK,
                    QuarkNetwork.API, folderId, fid, "Shared video", file.size());
            savedAccounts.put(fid, account);
            OpenDriveCopyUsage.stage(id(), account, fid);
            return fid;
        } catch (RuntimeException error) {
            // Preserve the repository failure. Remote cleanup is best-effort and can reject
            // an already-reclaimed fid independently of the failure being reported here.
            cleanup(fid);
            throw error;
        }
    }

    String awaitSavedFid(String taskId) throws Exception {
        for (int retry = 0; retry < SAVE_TASK_MAX_POLLS; retry++) {
            JsonObject task = OpenDriveHttp.data(get("task", Map.of(
                    "task_id", taskId,
                    "retry_index", Integer.toString(retry),
                    "uc_param_str", "")), "Quark");
            com.wuming.android.tv.utils.QuarkPlaybackTrace.write(
                    "save poll=" + retry + " status=" + number(task, "status")
                            + " destinationCount=" + array(object(task, "save_as"), "save_as_top_fids").size());
            String fid = completedSavedFid(task);
            if (fid != null) return fid;
            if (retry + 1 < SAVE_TASK_MAX_POLLS) {
                try {
                    waiter.sleep(SAVE_TASK_POLL_INTERVAL_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Quark save was interrupted", interrupted);
                }
            }
        }
        throw new IOException("Quark timed out while saving the shared file");
    }

    /**
     * Check terminal status before trusting a destination ID: an ID alone is not completion.
     * Do not restore the old ID-first return; it bypasses pending/failed/quota task states.
     * Covered by saveWaitsForCompletionEvenWhenDestinationIdIsPresent and
     * failedSaveCannotSucceedThroughAnEarlyDestinationId in QuarkOpenDriveProviderTest.
     */
    static String completedSavedFid(JsonObject task) throws IOException {
        JsonObject saveAs = object(task, "save_as");
        JsonArray fids = array(saveAs, "save_as_top_fids");
        String fid = !fids.isEmpty() && fids.get(0).isJsonPrimitive() ? fids.get(0).getAsString() : "";
        switch (com.wuming.tv.core.cloud.QuarkSavePolicy.state(number(task, "status"), fid,
                number(saveAs, "remain_capacity"), number(saveAs, "min_save_file_size"))) {
            case PENDING: return null;
            case COMPLETE: return fid;
            case QUOTA_EXHAUSTED: throw new QuarkQuotaException("Quark cloud space is insufficient; "
                    + "no disposable OpenDrive cache remains");
            case FAILED: throw new IOException("Quark rejected the shared file; try another route");
            default: throw new IOException("Quark completed the save without a file");
        }
    }

    private int reclaimInactiveCache(String folderId) {
        Set<String> protectedIds = ConcurrentHashMap.newKeySet();
        int discardedStreams = 0;
        for (Map.Entry<String, StreamRef> entry : streams.entrySet()) {
            StreamRef stream = entry.getValue();
            if (stream.ownedFid() == null) continue;
            if (stream.active()) {
                protectedIds.add(stream.ownedFid());
            } else if (streams.remove(entry.getKey(), stream)) {
                // A remembered URL is not a live playback session. Keeping it protected for the
                // full 15-minute fallback window can strand several failed files in a full
                // account and prevent the next episode from being saved. Removing the handle
                // first makes the idle provider-side copy safely reclaimable below.
                discardedStreams++;
            }
        }
        Set<String> deletedIds = new LinkedHashSet<>();
        int tracked = OpenDriveCacheSupport.reclaimInactive(
                CloudStorageProvider.QUARK, folderId, protectedIds, fid -> {
                    delete(fid);
                    deletedIds.add(fid);
                    savedAccounts.remove(fid);
                });

        int legacy = 0;
        try {
            for (JsonObject item : personalPage(folderId)) {
                String fid = string(item, "fid");
                if (fid.isBlank() || protectedIds.contains(fid) || deletedIds.contains(fid)
                        || OpenDriveCopyUsage.busy(id(), fid)) continue;
                delete(fid);
                deletedIds.add(fid);
                OpenDriveCacheSupport.deleted(CloudStorageProvider.QUARK,
                        savedAccounts.remove(fid), fid);
                legacy++;
            }
        } catch (Exception error) {
            OpenDriveLog.providerWarn(id(), "legacy-cache-inspect-failed",
                    "error=" + error.getMessage());
        }

        int purged = purgeOwnedRecycle(folderId, deletedIds);
        if (discardedStreams + tracked + legacy + purged > 0) {
            OpenDriveLog.providerDebug(id(), "cache-reclaimed",
                    "streams=" + discardedStreams + " tracked=" + tracked
                            + " legacy=" + legacy + " purged=" + purged);
        }
        return discardedStreams + tracked + legacy + purged;
    }

    /** Permanently removes only recycle records proven to belong to this account's app cache. */
    private int purgeOwnedRecycle(String folderId, Set<String> newlyDeletedIds) {
        Set<String> ownedFids = new LinkedHashSet<>(newlyDeletedIds);
        try {
            String accountHash = OpenDriveCacheSupport.accountHash(QuarkNetwork.API);
            CloudStorageRepository.records(CloudStorageProvider.QUARK, accountHash, folderId)
                    .forEach(record -> ownedFids.add(record.remoteFileId));
            if (ownedFids.isEmpty()) return 0;
            for (int poll = 0; poll < RECYCLE_MAX_POLLS; poll++) {
                JsonArray recycle = array(OpenDriveHttp.data(get("file/recycle/list", Map.of(
                        "_page", "1", "_size", Integer.toString(PAGE_SIZE))), "Quark"), "list");
                List<String> recordIds = matchingRecycleRecordIds(recycle, ownedFids);
                if (!recordIds.isEmpty()) {
                    post("file/recycle/remove", json(
                            "select_mode", 2, "record_list", recordIds));
                    return recordIds.size();
                }
                if (newlyDeletedIds.isEmpty() || poll + 1 >= RECYCLE_MAX_POLLS) break;
                waiter.sleep(SAVE_TASK_POLL_INTERVAL_MILLIS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            OpenDriveLog.providerWarn(id(), "recycle-purge-failed",
                    "error=" + error.getMessage());
        }
        return 0;
    }

    static List<String> matchingRecycleRecordIds(JsonArray recycle, Set<String> ownedFids) {
        if (recycle == null || ownedFids == null || ownedFids.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonElement element : recycle) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String fid = string(item, "fid");
            String recordId = string(item, "record_id");
            if (ownedFids.contains(fid) && !recordId.isBlank()) result.add(recordId);
        }
        return result;
    }

    private synchronized String ensureSaveFolder() throws Exception {
        if (saveFolderId != null) return saveFolderId;
        for (JsonObject item : personalPage("0")) {
            if (bool(item, "dir") && SAVE_FOLDER.equals(string(item, "file_name"))) {
                return saveFolderId = OpenDriveHttp.required(item, "fid", "Quark");
            }
        }
        JsonObject created = OpenDriveHttp.data(post("file", json(
                "pdir_fid", "0", "file_name", SAVE_FOLDER,
                "dir_path", "", "dir_init_lock", false)), "Quark");
        return saveFolderId = OpenDriveHttp.required(created, "fid", "Quark");
    }

    private void cleanup(String fid) {
        if (fid == null || fid.isEmpty()) return;
        OpenDriveCopyUsage.retire(id(), fid);
        if (OpenDriveCopyUsage.busy(id(), fid)) return;
        try {
            delete(fid);
            forgetSavedFid(fid);
            purgeOwnedRecycle(saveFolderId, Set.of(fid));
            OpenDriveCacheSupport.deleted(CloudStorageProvider.QUARK,
                    savedAccounts.remove(fid), fid);
        } catch (Exception e) {
            OpenDriveLog.providerWarn("quark", "cleanup_failed", "fid=" + fid + " error=" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void scheduleCleanup() {
        if (!cleanupScheduled.compareAndSet(false, true)) return;
        cleanupExecutor.schedule(this::cleanupStreams, STREAM_CACHE_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void cleanupStreams() {
        try {
            long cutoff = System.currentTimeMillis() - STREAM_CACHE_MILLIS;
            streams.entrySet().removeIf(entry ->
                    !entry.getValue().active() && entry.getValue().lastAccess() < cutoff);
        } finally {
            cleanupScheduled.set(false);
            if (!streams.isEmpty()) scheduleCleanup();
        }
    }

    private void delete(String fid) throws Exception {
        if (!OpenDriveCopyUsage.delete(id(), savedAccounts.getOrDefault(fid, "untracked"), fid, () -> {
            transport.post(deleteEndpoint(), json("action_type", 2, "filelist", List.of(fid),
                    "exclude_fids", Collections.emptyList()));
            return true;
        })) throw new OpenDriveCopyUsage.InUse();
    }

    boolean deleteTracked(com.wuming.android.tv.cloud.CloudCacheRecord record) throws Exception {
        if (!com.wuming.tv.core.cloud.SavedCopyPolicy.owned(id(), OpenDriveCacheSupport.accountHash(QuarkNetwork.API),
                com.wuming.android.tv.api.config.VodConfig.OPEN_DRIVE_KEY,
                record.provider, record.accountHash, record.sourceSiteKey,
                record.state == com.wuming.android.tv.cloud.CloudCacheRecord.ACTIVE)) return false;
        if (!com.wuming.android.tv.api.config.VodConfig.OPEN_DRIVE_KEY.equals(record.sourceSiteKey)) return false;
        deleteTracked(record.remoteFileId);
        return true;
    }

    void deleteTracked(String fid) throws Exception {
        if (fid == null || fid.isBlank()) throw new IllegalArgumentException("Tracked file ID is required");
        delete(fid);
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

    private JsonObject get(String endpoint, Map<String, String> params) throws Exception {
        HttpUrl.Builder url = endpoint(endpoint).newBuilder();
        params.forEach(url::addQueryParameter);
        return transport.get(url.build());
    }

    private JsonObject post(String endpoint, JsonObject body) throws Exception {
        return transport.post(endpoint(endpoint), body);
    }

    private static boolean validIdentifier(String value) {
        return value != null && value.length() >= 6 && value.length() <= 256
                && value.matches("[A-Za-z0-9_-]+");
    }

    private static boolean validCapability(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_CAPABILITY_LENGTH) return false;
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) return false;
        }
        return true;
    }

    private static IOException apiError(JsonObject root) {
        String message = string(root, "message");
        return new IOException(message.isBlank() ? "Quark API request failed" : "Quark API: " + message);
    }

    static boolean validDownloadUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private final class CleanupStream extends FilterInputStream {

        private final Response response;
        private final StreamRef stream;
        private final AtomicBoolean closed = new AtomicBoolean();

        private CleanupStream(InputStream input, Response response, StreamRef stream) {
            super(input);
            this.response = response;
            this.stream = stream;
        }

        @Override
        public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) return;
            try {
                super.close();
            } finally {
                response.close();
                stream.close();
            }
        }
    }

    record PlaybackConnection(Response response, Runnable cancellation) {

        void cancel() {
            cancellation.run();
        }
    }

    record LogicalRange(long start, long end, long total) {

        long length() {
            return end - start + 1L;
        }

        static LogicalRange parse(String value) {
            if (value == null) return null;
            Matcher matcher = CONTENT_RANGE.matcher(value);
            if (!matcher.matches()) return null;
            try {
                long start = Long.parseLong(matcher.group(1));
                long end = Long.parseLong(matcher.group(2));
                long total = "*".equals(matcher.group(3))
                        ? -1L : Long.parseLong(matcher.group(3));
                if (start < 0L || end < start || total == 0L
                        || total > 0L && end >= total) return null;
                return new LogicalRange(start, end, total);
            } catch (NumberFormatException error) {
                return null;
            }
        }
    }

    record FileRef(String fid, String fileToken, String shareId, String shareToken,
                   String parentId, boolean personal, String transcodeResolution, long size,
                   int transcodePolicyVersion) {

        FileRef(String fid, String fileToken, String shareId, String shareToken,
                String parentId, boolean personal, String transcodeResolution, long size) {
            this(fid, fileToken, shareId, shareToken, parentId, personal,
                    transcodeResolution, size, 1);
        }

        FileRef(String fid, String fileToken, String shareId, String shareToken,
                String parentId, boolean personal, String transcodeResolution) {
            this(fid, fileToken, shareId, shareToken, parentId, personal,
                    transcodeResolution, 0L);
        }

        FileRef {
            transcodeResolution = transcodeResolution == null ? "" : transcodeResolution;
            // Old persisted smart-route handles pinned playback to 270p. Gson supplies
            // zero for the absent policy version when restoring those handles. New
            // explicit low-quality requests carry version 1 and remain low quality.
            if (transcodePolicyVersion == 0 && "low".equalsIgnoreCase(transcodeResolution)) {
                transcodeResolution = "auto";
            }
            transcodePolicyVersion = 1;
            size = Math.max(0L, size);
        }
    }

    private record FolderRef(String fid, int depth) {
    }

    record DownloadResponse(JsonObject body, String cookie) {

        DownloadResponse {
            cookie = cookie == null ? "" : cookie;
        }
    }

    private record DownloadLink(String url, String cookie) {
    }

    record PlaybackUrl(String url, boolean transcoded, String cookie,
                       boolean smartOriginalFallback) {

        PlaybackUrl(String url, boolean transcoded, String cookie) {
            this(url, transcoded, cookie, false);
        }

        PlaybackUrl {
            cookie = cookie == null ? "" : cookie;
        }
    }

    private record MediaProbe(long offset, long contentLength) {
    }

    private record TranscodeProbe(boolean hls, long contentLength) {
    }

    static final class StreamRef {

        private final AtomicInteger active = new AtomicInteger();
        private final FileRef source;
        private volatile String savedFid;
        private volatile String url;
        private volatile boolean transcoded;
        private volatile String cookie;
        private final long mediaOffset;
        private volatile long lastAccess = System.currentTimeMillis();

        StreamRef(String url, String savedFid, FileRef source) {
            this(url, savedFid, source, false, "", 0L);
        }

        StreamRef(String url, String savedFid, FileRef source, boolean transcoded) {
            this(url, savedFid, source, transcoded, "", 0L);
        }

        StreamRef(String url, String savedFid, FileRef source, boolean transcoded,
                  String cookie) {
            this(url, savedFid, source, transcoded, cookie, 0L);
        }

        StreamRef(String url, String savedFid, FileRef source, boolean transcoded,
                  String cookie, long mediaOffset) {
            this.url = url;
            this.savedFid = savedFid;
            this.source = source;
            this.transcoded = transcoded;
            this.cookie = cookie == null ? "" : cookie;
            this.mediaOffset = Math.max(0L, mediaOffset);
        }

        private void open() {
            lastAccess = System.currentTimeMillis();
            active.incrementAndGet();
        }

        void close() {
            lastAccess = System.currentTimeMillis();
            active.updateAndGet(value -> Math.max(0, value - 1));
        }

        private boolean active() {
            return active.get() > 0;
        }

        private long lastAccess() {
            return lastAccess;
        }

        private String savedFid() {
            return savedFid;
        }

        private String ownedFid() {
            return source.personal() ? null : savedFid;
        }

        String url() {
            return url;
        }

        private FileRef source() {
            return source;
        }

        private boolean transcoded() {
            return transcoded;
        }

        private String cookie() {
            return cookie;
        }

        long mediaOffset() {
            return mediaOffset;
        }

        private void update(String url, String savedFid, boolean transcoded, String cookie) {
            this.url = url;
            this.savedFid = savedFid;
            this.transcoded = transcoded;
            this.cookie = cookie == null ? "" : cookie;
            lastAccess = System.currentTimeMillis();
        }
    }
}
