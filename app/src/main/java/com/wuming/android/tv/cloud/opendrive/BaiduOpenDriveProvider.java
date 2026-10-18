package com.wuming.android.tv.cloud.opendrive;

import com.wuming.tv.core.cloud.*;
import com.wuming.tv.core.media.MediaFileClassifier;
import com.wuming.tv.core.playback.OpenDrivePlaybackHints;

import com.github.catvod.net.PersistentCookieJar;
import com.wuming.android.tv.utils.Hex;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Cookie;
import okhttp3.HttpUrl;
import okhttp3.Response;

/** Baidu Netdisk web adapter. Shared videos use preview playback without saving a copy. */
final class BaiduOpenDriveProvider implements OpenDriveProvider {

    BaiduOpenDriveProvider() {
        this(new com.wuming.tv.core.runtime.cloudlogin.BaiduCloudAccountProvider(
                new AndroidCloudCredentialStore(), new AndroidCloudLoginTransport()));
    }

    BaiduOpenDriveProvider(CloudAccountProvider loginDelegate) {
        this.loginDelegate = loginDelegate;
    }

    private static final String HOST = "https://pan.baidu.com";
    private static final String REFERENCE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; SM-X800) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/101.0.4951.40 Safari/537.36";
    private static final String ENCODED_BOOTSTRAP = "eval(decodeURIComponent(";
    private static final String APP_USER_AGENT =
            "netdisk;P2SP;2.2.91.136;android-android;";
    private static final String MEDIA_USER_AGENT =
            "netdisk;1.4.2;22021211RC;android-android;12;JSbridge4.4.0;jointBridge;1.1.0;";
    private static final String APP_DEV_UID =
            "73CED981D0F186D12BC18CAE1684FFD5|VSRCQTF6W";
    private static final String APP_VERSION = "11.30.2";
    private static final String APP_CHANNEL = "android_12_zhao_bd-netdisk_1024266h";
    private static final String APP_RAND_SECRET =
            "ebrcUYiuxaZv2XGu7KIYKxUrqfnOfpDF";
    private static final String APP_RAND_SUFFIX =
            "ae5821440fab5e1a61a025f014bd8972";
    private static final String TRANSFER_FOLDER = "TVBoxOSC_OpenDrive";
    private static final String TRANSFER_PATH = "/" + TRANSFER_FOLDER;
    private static final int TRANSFER_MAX_ATTEMPTS = 3;
    private static final long TRANSFER_RETRY_DELAY_MILLIS = 2_000L;
    private final OpenDriveFileHandles<FileRef> files = new OpenDriveFileHandles<>(id(), FileRef.class);
    private final CloudAccountProvider loginDelegate;

    @Override
    public String id() {
        return "baidu";
    }

    @Override
    public String displayName() {
        return "Baidu Netdisk";
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
        JsonElement list = root == null ? null : root.get("list");
        return OpenDriveHttp.number(root, "errno") == 0
                && list != null && list.isJsonArray();
    }

    static JsonObject parseBaiduJsonp(String value) throws IOException {
        String content = value == null ? "" : value.trim();
        int open = content.indexOf('(');
        int close = content.lastIndexOf(')');
        if (open >= 0 && close > open) content = content.substring(open + 1, close).trim();
        content = content.replace("&quot;", "\"")
                .replace("&#34;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&");
        try {
            JsonElement parsed = com.google.gson.JsonParser.parseString(content);
            if (parsed.isJsonObject()) return parsed.getAsJsonObject();
        } catch (Exception ignored) {
        }
        throw new IOException("Baidu returned an invalid QR confirmation");
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
        String path = "root".equals(parentId) ? "/" : requireFile(parentId).path;
        JsonObject root = OpenDriveHttp.getExplicit(HOST + "/api/list", Map.of(
                "dir", path, "order", "name", "desc", "0", "showempty", "0",
                "web", "1", "app_id", "250528"), headers("")).body();
        if (OpenDriveHttp.number(root, "errno") != 0) throw new IOException("Baidu Netdisk API request failed");
        return personalItems(OpenDriveHttp.array(root, "list"));
    }

    @Override
    public boolean supportsShare(String url) {
        return share(url) != null;
    }

    @Override
    public List<OpenDriveItem> listShare(String url) throws Exception {
        ShareLink link = share(url);
        if (link == null) throw new IllegalArgumentException("Invalid Baidu Netdisk share link");
        JsonObject verified = OpenDriveHttp.postFormExplicit(HOST + "/share/verify?t="
                        + System.currentTimeMillis() + "&surl=" + link.surl,
                Map.of("pwd", link.password), referenceHeaders()).body();
        String randsk = OpenDriveHttp.string(verified, "randsk");
        if (randsk.isBlank()) throw new IOException("Baidu Netdisk rejected the share link or extraction code");

        List<OpenDriveItem> result = new ArrayList<>();
        ArrayDeque<FolderRef> folders = new ArrayDeque<>();
        folders.add(new FolderRef("", "", "", 0));
        int visited = 0;
        while (!folders.isEmpty()) {
            FolderRef folder = folders.removeFirst();
            if (++visited > 256) throw new IOException("Baidu Netdisk share contains too many folders");
            for (int page = 1; page <= 20; page++) {
                java.util.LinkedHashMap<String, String> query = new java.util.LinkedHashMap<>();
                query.put("page", String.valueOf(page));
                query.put("num", "100");
                query.put("desc", "0");
                query.put("order", "name");
                if (folder.path.isBlank()) {
                    query.put("shorturl", link.surl);
                    query.put("root", "1");
                } else {
                    query.put("uk", folder.uk);
                    query.put("shareid", folder.shareId);
                    query.put("dir", folder.path);
                }
                JsonObject root = OpenDriveHttp.getExplicit(HOST + "/share/list", query,
                        headers(randsk)).body();
                JsonArray values = OpenDriveHttp.array(root, "list");
                String uk = folder.uk.isBlank() ? jsonString(root, "uk") : folder.uk;
                String shareId = folder.shareId.isBlank() ? jsonString(root, "share_id") : folder.shareId;
                for (JsonElement value : values) {
                    if (!value.isJsonObject()) continue;
                    JsonObject item = value.getAsJsonObject();
                    if (OpenDriveHttp.number(item, "isdir") == 1) {
                        String childPath = OpenDriveHttp.string(item, "path");
                        if (childPath.isBlank()) childPath = "/sharelink" + uk + "-"
                                + OpenDriveHttp.string(item, "fs_id") + "/" + OpenDriveHttp.name(item, "server_filename");
                        if (folder.depth < 20) folders.addLast(new FolderRef(childPath, uk, shareId, folder.depth + 1));
                    } else if (isVideo(OpenDriveHttp.name(item, "server_filename"))) {
                        FileRef ref = new FileRef(OpenDriveHttp.string(item, "fs_id"),
                                OpenDriveHttp.string(item, "path"), uk, shareId,
                                "1" + link.surl, randsk, "original",
                                OpenDriveHttp.number(item, "size"), OpenDriveHttp.string(item, "md5"));
                        result.add(new OpenDriveItem(remember(ref), OpenDriveHttp.name(item, "server_filename"), false,
                                OpenDriveHttp.number(item, "size")));
                    }
                }
                if (values.size() < 100) break;
            }
        }
        return result;
    }

    @Override
    public OpenDrivePreparedExternal prepareExternal(OpenDriveExternalPayload payload) {
        JsonObject item = payload.json(true);
        if (item == null) return null;
        String fsId = ExternalEpisodePayload.first(item, "fid", "fs_id", "fsId");
        String uk = ExternalEpisodePayload.first(item, "uk");
        String shareId = ExternalEpisodePayload.first(item, "shareid", "share_id", "shareId");
        String surl = ExternalEpisodePayload.first(item, "surl", "yurl");
        String randsk = ExternalEpisodePayload.first(item, "randsk", "sekey", "seKey");
        String qtype = ExternalEpisodePayload.first(item, "qtype", "quality");
        boolean labeled = ExternalEpisodePayload.belongsTo(
                payload.flag(), item, "baidu", "百度", "bd原", "bd预");
        boolean original = isOriginalQuality(qtype);
        boolean baiduSchema = ExternalEpisodePayload.identifier(fsId)
                && ExternalEpisodePayload.identifier(uk)
                && ExternalEpisodePayload.identifier(shareId)
                && ExternalEpisodePayload.capability(randsk)
                && (original || ExternalEpisodePayload.identifier(surl));
        // Aggregators frequently rename a Baidu line after their own source
        // (for example "嘟嘟盘"). The provider-specific field combination is
        // a safer discriminator than a mutable display label.
        if (!labeled && !baiduSchema) return null;
        if (!ExternalEpisodePayload.identifier(fsId) || !ExternalEpisodePayload.identifier(uk)
                || !ExternalEpisodePayload.identifier(shareId)) return null;
        if (original && !ExternalEpisodePayload.capability(randsk)) return null;
        if (!original && !ExternalEpisodePayload.identifier(surl)) return null;
        if (!randsk.isBlank() && !validCookieValue(randsk)) return null;
        if (!surl.isBlank() && !surl.startsWith("1")) surl = "1" + surl;
        long size = Math.max(OpenDriveHttp.number(item, "size"),
                OpenDriveHttp.number(item, "file_size"));
        FileRef file = new FileRef(fsId, "", uk, shareId, surl, randsk,
                original ? "original" : "preview", size);
        return OpenDrivePreparedExternal.of(() -> remember(file));
    }

    @Override
    public OpenDrivePlayback resolve(String itemId) throws Exception {
        FileRef file = requireFile(itemId);
        String url;
        String originalUserAgent = "";
        String shareCookie = file.randsk;
        if (file.shareId.isBlank()) {
            JsonObject root = OpenDriveHttp.getExplicit(HOST + "/api/mediainfo", Map.of(
                    "type", "M3U8_FLV_264_480", "path", file.path,
                    "clienttype", "80", "origin", "dlna"), headers("")).body();
            url = OpenDriveHttp.string(OpenDriveHttp.object(root, "info"), "dlink");
        } else if (isOriginalQuality(file.quality)) {
            OriginalPlayback original = originalPlayback(file);
            url = original.url;
            originalUserAgent = original.userAgent;
        } else {
            String shareUrl = HOST + "/s/" + file.surl;
            String jsToken = optionalPageToken(shareUrl, file.randsk);
            // The official page concatenates window.jsToken; JavaScript produces
            // the literal value "undefined" when that optional bootstrap is absent.
            jsToken = playbackToken(jsToken);
            JsonObject config = OpenDriveHttp.getExplicit(HOST + "/share/tplconfig", Map.of(
                    "surl", file.surl,
                    "fields", "cfrom_id,Espace_info,card_info,sign,timestamp"),
                    headers("")).body();
            JsonObject data = OpenDriveHttp.object(config, "data");
            String sign = OpenDriveHttp.string(data, "sign");
            String timestamp = OpenDriveHttp.string(data, "timestamp");
            if (sign.isBlank() || timestamp.isBlank()) throw new IOException("Baidu Netdisk did not authorize preview playback");
            url = shareStreamUrl(file.uk, file.fsId, sign, timestamp, file.shareId,
                    jsToken, vipValue(data));
        }
        if (!OpenDriveHttp.validHttpsUrl(url)) throw new IOException("Baidu Netdisk returned an invalid playback URL");
        java.util.LinkedHashMap<String, String> playbackHeaders = new java.util.LinkedHashMap<>();
        playbackHeaders.put("User-Agent", !originalUserAgent.isBlank()
                ? originalUserAgent : file.shareId.isBlank()
                ? "netdisk;1.4.2;22021211RC;android-android;12;JSbridge4.4.0;jointBridge;1.1.0;"
                : REFERENCE_USER_AGENT);
        if (!file.shareId.isBlank() && !isOriginalQuality(file.quality)) {
            playbackHeaders.put("Accept", "application/json, text/plain, */*");
            playbackHeaders.put("Content-Type", "application/x-www-form-urlencoded");
            playbackHeaders.put("Origin", HOST);
        }
        playbackHeaders.put("Referer", HOST + "/");
        // BaiduDrive.kt signs and opens preview playlists with the account cookie string.
        // BDCLND is share-scoped and is used for /share/list, not preview playback.
        if (!isOriginalQuality(file.quality)) {
            String cookie = cookieHeader(shareCookie);
            if (!cookie.isBlank()) playbackHeaders.put("Cookie", cookie);
        }
        boolean originalRoute = shouldUseOriginalRanges(file.quality, false);
        if (!file.shareId.isBlank() && !originalRoute
                && !file.randsk.isBlank() && previewRejected(url, playbackHeaders)) {
            // AndroidCatVodSpider's normal Baidu catalog publishes the original
            // path, not its preview list. Fall back only for Baidu's observed
            // errno=71 preview rejection; the first reference path is read-only.
            OriginalPlayback original = originalPlayback(file);
            url = original.url;
            playbackHeaders.clear();
            playbackHeaders.put("User-Agent", original.userAgent);
            originalRoute = shouldUseOriginalRanges(file.quality, true);
            playbackHeaders.put(OpenDrivePlaybackHints.FALLBACK_REASON,
                    OpenDrivePlaybackHints.FallbackReason.BAIDU_PREVIEW_REJECTED.name());
        }
        if (originalRoute) {
            long contentLength = validatedRangeLength(url, playbackHeaders);
            playbackHeaders = new java.util.LinkedHashMap<>(
                    OpenDrivePlaybackHints.parallelRanges(playbackHeaders,
                            OpenDrivePlaybackHints.PROFILE_BAIDU_ORIGINAL, contentLength));
        }
        return new OpenDrivePlayback(url, playbackHeaders,
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10));
    }

    private static long validatedRangeLength(String url, Map<String, String> headers) {
        try (Response response = OpenDriveHttp.openExplicit(url, "bytes=0-0", headers)) {
            if (response.code() != 206) return 0L;
            return contentRangeTotal(response.header("Content-Range"));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    static long contentRangeTotal(String value) {
        if (value == null || !value.startsWith("bytes 0-0/")) return 0L;
        try {
            return Math.max(0L, Long.parseLong(value.substring("bytes 0-0/".length())));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static boolean previewRejected(String url, Map<String, String> headers) {
        try {
            return previewRejected(OpenDriveHttp.getTextExplicit(url, headers));
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean previewRejected(String content) {
        if (content == null || content.isBlank() || content.startsWith("#EXTM3U")) return false;
        try {
            JsonElement parsed = com.google.gson.JsonParser.parseString(content);
            return parsed.isJsonObject()
                    && OpenDriveHttp.number(parsed.getAsJsonObject(), "errno") == 71;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String optionalPageToken(String shareUrl, String randsk) {
        try {
            String token = extractJsToken(OpenDriveHttp.getTextExplicit(shareUrl,
                    sharePageHeaders(randsk, false)));
            if (token.isBlank()) {
                token = extractJsToken(OpenDriveHttp.getTextExplicit(shareUrl,
                        sharePageHeaders(randsk, true)));
            }
            return token;
        } catch (IOException ignored) {
            // The current Baidu page concatenates window.jsToken into the
            // streaming URL. When its optional bootstrap is absent JavaScript
            // contributes the literal value "undefined". A share-page redirect
            // loop must therefore not prevent the signed preview request.
            return "";
        } catch (Exception ignored) {
            return "";
        }
    }

    static String shareStreamUrl(String uk, String fsId, String sign,
                                 String timestamp, String shareId) {
        return shareStreamUrl(uk, fsId, sign, timestamp, shareId, "undefined", 0);
    }

    static String shareStreamUrl(String uk, String fsId, String sign,
                                 String timestamp, String shareId, String jsToken, int vip) {
        if (!validEncodedSign(sign)) throw new IllegalArgumentException("Invalid Baidu preview sign");
        if (!("undefined".equals(jsToken) || validLoginValue(jsToken))) {
            throw new IllegalArgumentException("Invalid Baidu page playback token");
        }
        if (vip < 0 || vip > 2) throw new IllegalArgumentException("Invalid Baidu VIP state");
        return HttpUrl.parse(HOST + "/share/streaming").newBuilder()
                .addQueryParameter("channel", "chunlei")
                .addQueryParameter("uk", uk)
                .addQueryParameter("fid", fsId)
                // tplconfig returns the sign in the form consumed directly by
                // BaiduDrive.kt. Treat it as an encoded component so existing
                // %XX sequences are not changed to %25XX.
                .addEncodedQueryParameter("sign", sign)
                .addQueryParameter("timestamp", timestamp)
                .addQueryParameter("shareid", shareId)
                .addQueryParameter("type", vip == 0 ? "M3U8_AUTO_480" : "M3U8_AUTO_1080")
                .addQueryParameter("vip", String.valueOf(vip))
                .addQueryParameter("jsToken", jsToken)
                .build().toString();
    }

    static int vipValue(JsonObject data) {
        JsonObject card = OpenDriveHttp.object(data, "card_info");
        if (OpenDriveHttp.number(card, "is_svip") == 1
                || OpenDriveHttp.number(data, "is_svip") == 1) return 2;
        if (OpenDriveHttp.number(card, "is_vip") == 1
                || OpenDriveHttp.number(data, "is_vip") == 1) return 1;
        return 0;
    }

    private static OriginalPlayback originalPlayback(FileRef file) throws Exception {
        String account = accountCookieToken();
        String bduss = cookieValue(account, "BDUSS");
        if (bduss.isBlank()) bduss = cookieValue(account, "BDUSS_BFESS");
        if (bduss.isBlank()) throw new IOException("Baidu account cookie is incomplete");

        JsonObject user = OpenDriveHttp.getExplicit(
                "https://mbd.baidu.com/userx/v1/info/get", Map.of(
                        "appname", "baiduboxapp",
                        "fields", "[\"bg_image\",\"member\",\"uid\",\"avatar\",\"avatar_member\"]",
                        "lang", "zh-cn"), appHeaders(account)).body();
        String uid = OpenDriveHttp.string(OpenDriveHttp.object(
                OpenDriveHttp.object(user, "data"), "fields"), "uid");
        if (uid.isBlank()) throw new IOException("Baidu did not return the account UID");

        long now = System.currentTimeMillis();
        String rand = sha1Hex(sha1Hex(bduss) + uid + APP_RAND_SECRET + now
                + APP_DEV_UID + APP_VERSION + APP_RAND_SUFFIX);
        JsonObject root = OpenDriveHttp.getExplicit(HOST + "/share/list", Map.ofEntries(
                Map.entry("shareid", file.shareId),
                Map.entry("uk", file.uk),
                Map.entry("fid", file.fsId),
                Map.entry("sekey", percentDecode(file.randsk)),
                Map.entry("origin", "dlna"),
                Map.entry("devuid", APP_DEV_UID),
                Map.entry("clienttype", "1"),
                Map.entry("channel", APP_CHANNEL),
                Map.entry("version", APP_VERSION),
                Map.entry("time", String.valueOf(now)),
                Map.entry("rand", rand)), appHeaders(account)).body();
        JsonArray values = OpenDriveHttp.array(root, "list");
        String dlink = values.isEmpty() || !values.get(0).isJsonObject() ? ""
                : OpenDriveHttp.string(values.get(0).getAsJsonObject(), "dlink");
        if (OpenDriveHttp.validHttpsUrl(dlink)) {
            return new OriginalPlayback(dlink, APP_USER_AGENT);
        }
        return new OriginalPlayback(transferredDownloadUrl(file, account), MEDIA_USER_AGENT);
    }

    private static String transferredDownloadUrl(FileRef file, String account) throws Exception {
        ensureTransferFolder(account);
        ensureCapacityForTransfer(file.size, account);
        String cookie = cookieHeader(file.randsk);
        java.util.LinkedHashMap<String, String> transferHeaders = new java.util.LinkedHashMap<>();
        transferHeaders.put("User-Agent", "Android");
        transferHeaders.put("Connection", "Keep-Alive");
        transferHeaders.put("Content-Type", "application/x-www-form-urlencoded");
        transferHeaders.put("Accept-Language", "zh-CN,zh;q=0.8");
        transferHeaders.put("charset", "UTF-8");
        transferHeaders.put("Referer", HOST);
        transferHeaders.put("Cookie", cookie);
        HttpUrl transferUrl = transferUrl(file.uk, file.shareId);
        String to = "";
        long errno = Long.MIN_VALUE;
        for (int attempt = 1; attempt <= TRANSFER_MAX_ATTEMPTS; attempt++) {
            JsonObject transferred = OpenDriveHttp.postFormExplicit(transferUrl.toString(),
                    transferForm(file.fsId), transferHeaders).body();
            to = transferPath(transferred);
            errno = OpenDriveHttp.number(transferred, "errno");
            OpenDriveLog.providerInfo("baidu", "transfer-attempt",
                    "attempt=" + attempt + " errno=" + errno + " hasPath=" + !to.isBlank());
            if (!to.isBlank()) break;
            if (!retryableTransferError(errno, attempt)) break;
            try {
                Thread.sleep(TRANSFER_RETRY_DELAY_MILLIS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("Baidu playback transfer was interrupted", error);
            }
        }
        boolean reusedCopy = false;
        if (to.isBlank() && errno == 4L && validContentHash(file.md5) && file.size > 0) {
            // Repeated live transfers can report a collision even with ondup=newcopy.
            // Reuse only a cache file with the source listing's exact hash AND size;
            // never broaden errno 4 into a retry or assume a same-name file is correct.
            JsonObject cached = OpenDriveHttp.getExplicit(HOST + "/api/list", Map.of(
                    "dir", TRANSFER_PATH, "order", "name", "desc", "0", "page", "1", "num", "1000",
                    "web", "1", "app_id", "250528"), appHeaders(account)).body();
            if (OpenDriveHttp.number(cached, "errno") == 0L)
                to = matchingTransferPath(OpenDriveHttp.array(cached, "list"), file.md5, file.size);
            reusedCopy = !to.isBlank();
            OpenDriveLog.providerInfo("baidu", "transfer-collision",
                    "matchedCopy=" + reusedCopy);
        }
        if (to.isBlank()) {
            String suffix = errno == Long.MIN_VALUE ? "" : " (errno " + errno + ")";
            throw new IOException("Baidu did not save the shared file for playback" + suffix);
        }
        String path = to.startsWith("/") ? to : "/" + to;

        java.util.LinkedHashMap<String, String> mediaHeaders = new java.util.LinkedHashMap<>();
        mediaHeaders.put("User-Agent", MEDIA_USER_AGENT);
        mediaHeaders.put("Connection", "Keep-Alive");
        mediaHeaders.put("Accept-Language", "zh-CN,zh;q=0.8");
        mediaHeaders.put("charset", "UTF-8");
        mediaHeaders.put("Cookie", cookie);
        JsonObject media = OpenDriveHttp.getExplicit(HOST + "/api/mediainfo", Map.of(
                "type", "M3U8_FLV_264_480", "path", path,
                "clienttype", "80", "origin", "dlna"), mediaHeaders).body();
        String dlink = OpenDriveHttp.string(OpenDriveHttp.object(media, "info"), "dlink");
        if (!OpenDriveHttp.validHttpsUrl(dlink)) {
            try {
                if (!reusedCopy) deleteTransferPaths(account, List.of(path));
            } catch (Exception cleanupError) {
                OpenDriveLog.providerWarn("baidu", "transfer-cleanup-failed",
                        "error=" + cleanupError.getClass().getSimpleName());
            }
            throw new IOException("Baidu did not authorize the saved file for playback");
        }
        return dlink;
    }

    private static void ensureCapacityForTransfer(long requiredBytes, String account)
            throws Exception {
        if (requiredBytes <= 0L) return;
        try {
            JsonObject quota = OpenDriveHttp.getExplicit(HOST + "/api/quota", Map.of(
                    "checkexpire", "1", "checkfree", "1", "channel", "chunlei",
                    "web", "1", "app_id", "250528"), appHeaders(account)).body();
            long available = availableCapacity(quota);
            if (available < 0L || available >= requiredBytes) return;
            int reclaimed;
            try {
                reclaimed = reclaimTransferCache(account);
            } catch (Exception cleanupError) {
                throw new BaiduQuotaException(
                        "Baidu cloud space is insufficient and its OpenDrive cache "
                                + "could not be removed", cleanupError);
            }
            OpenDriveLog.providerInfo("baidu", "quota-preflight",
                    "required=" + requiredBytes + " available=" + available + " reclaimed=" + reclaimed);
            if (reclaimed == 0) {
                throw new BaiduQuotaException("Baidu cloud space is insufficient; "
                        + "no disposable OpenDrive cache remains");
            }
        } catch (BaiduQuotaException error) {
            throw error;
        } catch (Exception error) {
            // The transfer endpoint remains authoritative when the optional quota endpoint
            // is unavailable for a valid web-cookie session.
            OpenDriveLog.providerWarn("baidu", "quota-check-failed",
                    "error=" + error.getClass().getSimpleName());
        }
    }

    static long availableCapacity(JsonObject root) {
        if (root == null || OpenDriveHttp.number(root, "errno") != 0L
                || !root.has("total") || !root.has("used")) return -1L;
        long total = OpenDriveHttp.number(root, "total");
        long used = OpenDriveHttp.number(root, "used");
        return total >= 0L && used >= 0L && total >= used ? total - used : -1L;
    }

    private static int reclaimTransferCache(String account) throws Exception {
        int reclaimed = 0;
        for (int sweep = 0; sweep < 20; sweep++) {
            JsonObject listed = OpenDriveHttp.getExplicit(HOST + "/api/list", Map.of(
                    "dir", TRANSFER_PATH, "order", "name", "desc", "0",
                    "showempty", "0", "page", "1", "num", "1000",
                    "web", "1", "app_id", "250528"), appHeaders(account)).body();
            List<String> paths = transferCachePaths(OpenDriveHttp.array(listed, "list"));
            if (paths.isEmpty()) return reclaimed;
            deleteTransferPaths(account, paths);
            reclaimed += paths.size();
            if (paths.size() < 1000) return reclaimed;
        }
        throw new IOException("Baidu OpenDrive cache exceeds the bounded cleanup limit");
    }

    static List<String> transferCachePaths(JsonArray values) {
        if (values == null || values.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        String prefix = TRANSFER_PATH + "/";
        for (JsonElement value : values) {
            if (!value.isJsonObject()) continue;
            String path = OpenDriveHttp.string(value.getAsJsonObject(), "path");
            if (path.startsWith(prefix) && path.length() > prefix.length()) result.add(path);
        }
        return result;
    }

    static String deleteFileList(List<String> paths) {
        JsonArray values = new JsonArray();
        if (paths != null) paths.forEach(values::add);
        return values.toString();
    }

    private static void deleteTransferPaths(String account, List<String> paths) throws Exception {
        if (paths == null || paths.isEmpty()) return;
        String prefix = TRANSFER_PATH + "/";
        for (String path : paths) {
            if (path == null || !path.startsWith(prefix) || path.length() <= prefix.length()) {
                throw new IllegalArgumentException("Refusing to delete outside Baidu OpenDrive cache");
            }
        }
        String token = bdstoken(account);
        HttpUrl url = HttpUrl.parse(HOST + "/api/filemanager").newBuilder()
                .addQueryParameter("opera", "delete")
                .addQueryParameter("onnest", "fail")
                .addQueryParameter("async", "0")
                .addQueryParameter("channel", "chunlei")
                .addQueryParameter("web", "1")
                .addQueryParameter("app_id", "250528")
                .addQueryParameter("bdstoken", token)
                .addQueryParameter("clienttype", "0")
                .build();
        JsonObject deleted = OpenDriveHttp.postFormExplicit(url.toString(),
                Map.of("filelist", deleteFileList(paths)), appHeaders(account)).body();
        if (OpenDriveHttp.number(deleted, "errno") != 0L) {
            throw new IOException("Baidu did not remove its disposable playback cache");
        }
    }

    static HttpUrl transferUrl(String uk, String shareId) {
        return HttpUrl.parse(HOST + "/share/transfer").newBuilder()
                .addQueryParameter("from", uk)
                .addQueryParameter("shareid", shareId)
                // The share endpoint rejects existing names with errno 4 when
                // asked to overwrite. Use its supported collision rename and
                // consume the actual destination returned in extra.list.to.
                .addQueryParameter("ondup", "newcopy")
                .build();
    }

    static String matchingTransferPath(JsonArray values, String md5, long size) {
        if (!validContentHash(md5) || size <= 0 || values == null) return "";
        for (JsonElement value : values) {
            if (!value.isJsonObject()) continue;
            JsonObject item = value.getAsJsonObject();
            String path = OpenDriveHttp.string(item, "path");
            if (OpenDriveHttp.number(item, "isdir") == 0
                    && path.startsWith(TRANSFER_PATH + "/") && !path.contains("/../")
                    && OpenDriveHttp.number(item, "size") == size
                    && md5.equalsIgnoreCase(OpenDriveHttp.string(item, "md5"))) return path;
        }
        return "";
    }

    private static boolean validContentHash(String value) {
        return value != null && value.matches("[A-Fa-f0-9]{32}");
    }

    static Map<String, String> transferForm(String fsId) {
        return Map.of("path", TRANSFER_PATH, "fsidlist", "[" + fsId + "]");
    }

    static String transferPath(JsonObject response) {
        JsonObject extra = flexibleObject(response, "extra");
        JsonArray values = OpenDriveHttp.array(extra, "list");
        if (values.isEmpty() || !values.get(0).isJsonObject()) return "";
        return OpenDriveHttp.string(values.get(0).getAsJsonObject(), "to");
    }

    private static JsonObject flexibleObject(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        if (value == null || value.isJsonNull()) return new JsonObject();
        if (value.isJsonObject()) return value.getAsJsonObject();
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = com.google.gson.JsonParser.parseString(value.getAsString());
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }

    static boolean retryableTransferError(long errno, int attempt) {
        // Baidu reports 1504 when a large-file transfer exceeds the synchronous
        // request deadline. The maintained reference client retries this whole
        // boundary. Keep that recovery narrowly classified and bounded so auth,
        // quota, policy and malformed-request failures are never replayed.
        return errno == 1504L && attempt < TRANSFER_MAX_ATTEMPTS;
    }

    private static void ensureTransferFolder(String account) throws Exception {
        JsonObject root = OpenDriveHttp.getExplicit(HOST + "/api/list", Map.of(
                "dir", "/", "order", "name", "desc", "0", "showempty", "0",
                "web", "1", "app_id", "250528"), appHeaders(account)).body();
        for (JsonElement value : OpenDriveHttp.array(root, "list")) {
            if (!value.isJsonObject()) continue;
            JsonObject item = value.getAsJsonObject();
            if (OpenDriveHttp.number(item, "isdir") == 1
                    && TRANSFER_FOLDER.equals(OpenDriveHttp.string(item, "server_filename"))) return;
        }
        String bdstoken = bdstoken(account);
        HttpUrl createUrl = HttpUrl.parse(HOST + "/api/create").newBuilder()
                .addQueryParameter("a", "commit")
                .addQueryParameter("bdstoken", bdstoken)
                .addQueryParameter("clienttype", "0")
                .addQueryParameter("app_id", "250528")
                .addQueryParameter("web", "1")
                .build();
        JsonObject created = OpenDriveHttp.postFormExplicit(createUrl.toString(), Map.of(
                "path", TRANSFER_PATH, "isdir", "1", "block_list", "[]"),
                appHeaders(account)).body();
        if (OpenDriveHttp.number(created, "errno") != 0
                || OpenDriveHttp.number(created, "fs_id") == 0) {
            throw new IOException("Baidu did not create the playback folder");
        }
    }

    private static String bdstoken(String account) throws Exception {
        JsonObject template = OpenDriveHttp.getExplicit(HOST + "/api/gettemplatevariable",
                Map.of("clienttype", "0", "app_id", "250528", "web", "1",
                        "fields", "[\"bdstoken\",\"token\",\"uk\",\"isdocuser\",\"servertime\"]"),
                appHeaders(account)).body();
        String bdstoken = OpenDriveHttp.string(OpenDriveHttp.object(template, "result"), "bdstoken");
        if (bdstoken.isBlank()) throw new IOException("Baidu did not authorize the playback folder");
        return bdstoken;
    }

    private static Map<String, String> appHeaders(String account) {
        java.util.LinkedHashMap<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("User-Agent", APP_USER_AGENT);
        headers.put("Connection", "Keep-Alive");
        headers.put("Accept-Language", "zh-CN,zh;q=0.8");
        headers.put("charset", "UTF-8");
        headers.put("Cookie", account);
        return headers;
    }

    static boolean isOriginalQuality(String value) {
        return "original".equalsIgnoreCase(value);
    }

    static boolean shouldUseOriginalRanges(String quality, boolean fallbackToOriginal) {
        return fallbackToOriginal || isOriginalQuality(quality);
    }

    static String percentDecode(String value) {
        if (value == null || value.isBlank()) return "";
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '%' && index + 2 < value.length()) {
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high >= 0 && low >= 0) {
                    result.append((char) ((high << 4) | low));
                    index += 2;
                    continue;
                }
            }
            result.append(character);
        }
        return result.toString();
    }

    static String sha1Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Hex.encodeLower(digest);
        } catch (Exception error) {
            throw new IllegalStateException("SHA-1 is unavailable", error);
        }
    }

    private static String cookieValue(String header, String expectedName) {
        if (header == null || header.isBlank()) return "";
        for (String part : header.split(";")) {
            String pair = part.trim();
            int separator = pair.indexOf('=');
            if (separator <= 0) continue;
            if (expectedName.equals(pair.substring(0, separator).trim())) {
                return pair.substring(separator + 1).trim();
            }
        }
        return "";
    }

    private static boolean validEncodedSign(String value) {
        if (value == null || value.length() < 8 || value.length() > 4096) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x20 || character >= 0x7f
                    || character == '&' || character == '#') return false;
            if (character != '%') continue;
            if (index + 2 >= value.length()
                    || Character.digit(value.charAt(index + 1), 16) < 0
                    || Character.digit(value.charAt(index + 2), 16) < 0) return false;
            index += 2;
        }
        return true;
    }

    static String extractJsToken(String html) {
        if (html == null || html.isBlank()) return "";
        String direct = tokenFromScript(html);
        if (!direct.isBlank()) return direct;
        int offset = 0;
        while ((offset = html.indexOf(ENCODED_BOOTSTRAP, offset)) >= 0) {
            int quote = skipWhitespace(html, offset + ENCODED_BOOTSTRAP.length());
            if (quote >= html.length() || html.charAt(quote) != '\'' && html.charAt(quote) != '"') {
                offset += ENCODED_BOOTSTRAP.length();
                continue;
            }
            int end = html.indexOf(html.charAt(quote), quote + 1);
            if (end < 0) return "";
            String script;
            try {
                // URLDecoder treats a literal '+' as a space, unlike decodeURIComponent.
                script = URLDecoder.decode(html.substring(quote + 1, end).replace("+", "%2B"),
                        StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                offset = end + 1;
                continue;
            }
            direct = tokenFromScript(script);
            if (!direct.isBlank()) return direct;
            offset = end + 1;
        }
        return "";
    }

    static String playbackToken(String extractedToken) {
        return extractedToken == null || extractedToken.isBlank() ? "undefined" : extractedToken;
    }

    private static String tokenFromScript(String script) {
        int marker = script.indexOf("window.jsToken");
        if (marker < 0) return "";
        int equals = script.indexOf('=', marker + "window.jsToken".length());
        if (equals < 0 || equals - marker > 64) return "";
        int value = skipWhitespace(script, equals + 1);
        String direct = quoted(script, value);
        if (!direct.isBlank()) return direct;
        int brace = script.indexOf('}', value);
        int call = brace < 0 ? -1 : script.indexOf('(', brace + 1);
        if (call < 0 || call - brace > 128) return "";
        return quoted(script, skipWhitespace(script, call + 1));
    }

    private static int skipWhitespace(String value, int index) {
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
        return index;
    }

    private static String quoted(String value, int index) {
        if (index >= value.length()) return "";
        char quote = value.charAt(index);
        if (quote != '\'' && quote != '"') return "";
        int end = value.indexOf(quote, index + 1);
        if (end < 0) return "";
        String token = value.substring(index + 1, end).trim();
        if (token.length() < 8 || token.length() > 4096) return "";
        for (int i = 0; i < token.length(); i++) {
            char character = token.charAt(i);
            if (character <= 0x20 || character >= 0x7f) return "";
        }
        return token;
    }

    @Override
    public void credentialsRestored() {
        files.clear();
    }

    @Override
    public void logout() throws Exception {
        loginDelegate.logout();
        files.clearPersisted();
    }

    static String canonicalShareUrl(String value) {
        ShareLink link = share(value);
        return link == null ? null : HOST + "/s/1" + link.surl
                + (link.password.isBlank() ? "" : "?pwd=" + link.password);
    }

    private List<OpenDriveItem> personalItems(JsonArray values) {
        List<OpenDriveItem> result = new ArrayList<>();
        for (JsonElement value : values) {
            if (!value.isJsonObject()) continue;
            JsonObject item = value.getAsJsonObject();
            boolean directory = OpenDriveHttp.number(item, "isdir") == 1;
            if (!directory && !isVideo(OpenDriveHttp.name(item, "server_filename"))) continue;
            FileRef ref = new FileRef(OpenDriveHttp.string(item, "fs_id"),
                    OpenDriveHttp.string(item, "path"), "", "", "", "", "personal",
                    OpenDriveHttp.number(item, "size"));
            result.add(new OpenDriveItem(remember(ref), OpenDriveHttp.name(item, "server_filename"), directory,
                    OpenDriveHttp.number(item, "size")));
        }
        return result;
    }

    private static Map<String, String> headers(String randsk) {
        java.util.LinkedHashMap<String, String> headers = referenceHeaders();
        String cookie = randsk.isBlank() ? accountCookieToken() : shareCookieHeader(randsk);
        if (!cookie.isBlank()) headers.put("Cookie", cookie);
        return headers;
    }

    private static java.util.LinkedHashMap<String, String> referenceHeaders() {
        java.util.LinkedHashMap<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("User-Agent", REFERENCE_USER_AGENT);
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Origin", HOST);
        headers.put("Referer", HOST + "/");
        return headers;
    }

    private static boolean validLoginValue(String value) {
        if (value == null || value.length() < 8 || value.length() > 4096) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x20 || character >= 0x7f || character == '&') return false;
        }
        return true;
    }

    private static Map<String, String> sharePageHeaders(String randsk, boolean includeAccountCookies) {
        java.util.LinkedHashMap<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Referer", HOST + "/");
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        String cookie = includeAccountCookies ? cookieHeader(randsk) : shareCookieHeader(randsk);
        if (!cookie.isBlank()) headers.put("Cookie", cookie);
        return headers;
    }

    private static String shareCookieHeader(String randsk) {
        return randsk == null || randsk.isBlank() ? ""
                : mergeCookieHeader(List.of(), randsk);
    }

    private static String cookieHeader(String randsk) {
        String account = accountCookieToken();
        if (account.isBlank()) return shareCookieHeader(randsk);
        if (randsk == null || randsk.isBlank()) return account;
        if (!validCookieValue(randsk)) throw new IllegalArgumentException("Invalid Baidu share cookie");
        return account + "; BDCLND=" + randsk;
    }

    private static String accountCookieToken() {
        String secured = normalizeAccountCookieToken(OpenDriveTokenStore.get("baidu"));
        if (!secured.isBlank()) return secured;
        List<Cookie> cookies = List.of();
        try {
            // BaiDuYunHandler.generateCooike() flattens every Set-Cookie returned by
            // qrbdusslogin, including cookies originally scoped to passport.baidu.com.
            cookies = PersistentCookieJar.get().loadForStorageDomain("baidu.com");
        } catch (Exception ignored) {
        }
        String migrated = normalizeAccountCookieToken(mergeCookieHeader(cookies, ""));
        if (!migrated.isBlank()) OpenDriveTokenStore.put("baidu", migrated);
        return migrated;
    }

    static String normalizeAccountCookieToken(String value) {
        if (value == null || value.isBlank() || value.length() > 32768) return "";
        StringBuilder result = new StringBuilder();
        boolean account = false;
        for (String part : value.split(";")) {
            String pair = part.trim();
            int separator = pair.indexOf('=');
            if (separator <= 0) return "";
            String name = pair.substring(0, separator).trim();
            String cookieValue = pair.substring(separator + 1).trim();
            if (!name.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}")) return "";
            if (cookieValue.isEmpty()) continue;
            if (!validCookieValue(cookieValue)) return "";
            if ("BDCLND".equalsIgnoreCase(name)) continue;
            if ("BDUSS".equals(name) || "BDUSS_BFESS".equals(name)) account = true;
            appendCookie(result, name, cookieValue);
        }
        return account ? result.toString() : "";
    }

    static String mergeCookieHeader(List<Cookie> cookies, String randsk) {
        if (randsk == null) randsk = "";
        if (!randsk.isBlank() && !validCookieValue(randsk)) {
            throw new IllegalArgumentException("Invalid Baidu share cookie");
        }
        StringBuilder result = new StringBuilder();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie == null) continue;
                String name = cookie.name().toLowerCase(Locale.ROOT);
                // BDCLND is scoped to one share. Never inherit it from the shared
                // jar: /share/list receives the explicit current randsk, while
                // tplconfig and preview playback use account cookies only.
                if ("bdclnd".equals(name)) continue;
                appendCookie(result, cookie.name(), cookie.value());
            }
        }
        if (!randsk.isBlank()) appendCookie(result, "BDCLND", randsk);
        return result.toString();
    }

    private static void appendCookie(StringBuilder result, String name, String value) {
        if (result.length() > 0) result.append("; ");
        result.append(name).append('=').append(value);
    }

    private static boolean validCookieValue(String value) {
        if (value == null || value.isBlank() || value.length() > 4096) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x20 || character >= 0x7f || character == ';') return false;
        }
        return true;
    }

    private static String jsonString(JsonObject object, String key) {
        String value = OpenDriveHttp.string(object, key);
        if (!value.isBlank()) return value;
        return String.valueOf(OpenDriveHttp.number(object, key));
    }

    private static boolean isVideo(String name) {
        return MediaFileClassifier.isCloudVideoFileName(name);
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
            String normalized = value.trim().replace("提取码:", "?pwd=").replace("提取码：", "?pwd=");
            URI uri = URI.create(normalized);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || uri.getPort() != -1 || !"pan.baidu.com".equalsIgnoreCase(uri.getHost())) return null;
            String path = uri.getPath();
            if (path == null || !path.matches("/s/1[A-Za-z0-9_-]{5,128}/?")) return null;
            String surl = path.substring(4).replace("/", "");
            String password = "";
            if (uri.getRawQuery() != null) {
                for (String part : uri.getRawQuery().split("&")) {
                    String[] pair = part.split("=", 2);
                    if (pair.length == 2 && "pwd".equalsIgnoreCase(pair[0])) {
                        password = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                    }
                }
            }
            if (!password.matches("[A-Za-z0-9]{0,8}")) return null;
            return new ShareLink(surl, password);
        } catch (Exception ignored) {
            return null;
        }
    }

    private record FileRef(String fsId, String path, String uk, String shareId,
                           String surl, String randsk, String quality, long size, String md5) {

        private FileRef(String fsId, String path, String uk, String shareId,
                        String surl, String randsk, String quality, long size) {
            this(fsId, path, uk, shareId, surl, randsk, quality, size, "");
        }

        private FileRef(String fsId, String path, String uk, String shareId,
                        String surl, String randsk, String quality) {
            this(fsId, path, uk, shareId, surl, randsk, quality, 0L);
        }
    }

    private record OriginalPlayback(String url, String userAgent) {
    }

    static final class BaiduQuotaException extends OpenDriveFailure {

        BaiduQuotaException(String message) {
            super("baidu", Reason.QUOTA_EXHAUSTED, 0, 0, new IOException(message));
        }

        BaiduQuotaException(String message, Throwable cause) {
            super("baidu", Reason.QUOTA_EXHAUSTED, 0, 0, cause);
        }
    }

    private record FolderRef(String path, String uk, String shareId, int depth) {
    }

    private record ShareLink(String surl, String password) {
    }
}
