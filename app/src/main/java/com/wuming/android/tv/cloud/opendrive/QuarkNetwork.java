package com.wuming.android.tv.cloud.opendrive;

import static com.wuming.android.tv.cloud.opendrive.OpenDriveHttp.number;

import com.wuming.tv.core.cloud.OpenDriveFailure;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Quark drive HTTP transport: endpoints, OkHttp client factories, the JSON API client,
 * and its request/response types. Extracted from {@link QuarkOpenDriveProvider};
 * behaviour is unchanged.
 */
final class QuarkNetwork {

    static final String API = "https://drive-pc.quark.cn/1/clouddrive/";
    static final String QUERY_PR = "ucpro";
    static final String QUERY_FR = "pc";
    static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) quark-cloud-drive/3.20.0 Chrome/112.0.5615.165 "
                    + "Electron/24.1.3.8 Safari/537.36 Channel/pckk_other_ch";
    static final String TRANSCODE_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 "
                    + "Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch";

    private QuarkNetwork() {
    }

    interface QuarkTransport {
        JsonObject get(HttpUrl url) throws Exception;

        JsonObject post(HttpUrl url, JsonObject body) throws Exception;

        default QuarkOpenDriveProvider.DownloadResponse postDownload(
                HttpUrl url, JsonObject body) throws Exception {
            return new QuarkOpenDriveProvider.DownloadResponse(post(url, body), "");
        }

        default JsonObject postTranscode(HttpUrl url, JsonObject body) throws Exception {
            return post(url, body);
        }

        Response open(String url, String range) throws Exception;

        default Response open(String url, String range, String cookie) throws Exception {
            return open(url, range);
        }

        default Response openProbe(String url, String range, String cookie) throws Exception {
            return open(url, range, cookie);
        }

        default QuarkOpenDriveProvider.PlaybackConnection openConnection(
                String url, String range, String cookie) throws Exception {
            return new QuarkOpenDriveProvider.PlaybackConnection(open(url, range, cookie), () -> {
            });
        }
    }

    interface Waiter {
        void sleep(long millis) throws InterruptedException;
    }

    static final class QuarkHttpException extends IOException {

        private final int code;
        private final long apiCode;
        private final boolean plfInvalid;

        QuarkHttpException(int code, String path) {
            this(code, path, 0L, false);
        }

        QuarkHttpException(int code, String path, long apiCode, boolean plfInvalid) {
            super("Quark API request failed (HTTP " + code + ", " + path
                    + (apiCode == 0L ? "" : ", API code " + apiCode)
                    + (plfInvalid ? ", plf_invalid" : "") + ")");
            this.code = code;
            this.apiCode = apiCode;
            this.plfInvalid = plfInvalid;
        }

        int code() {
            return code;
        }

        long apiCode() {
            return apiCode;
        }

        boolean plfInvalid() {
            return plfInvalid;
        }
    }

    static final class QuarkQuotaException extends OpenDriveFailure {

        QuarkQuotaException(String message) {
            super("quark", Reason.QUOTA_EXHAUSTED, 0, 0, new IOException(message));
        }
    }

    static final class NetworkTransport implements QuarkTransport {

        private volatile okhttp3.OkHttpClient client;
        private volatile okhttp3.OkHttpClient streamingClient;

        @Override
        public JsonObject get(HttpUrl url) throws Exception {
            return execute(new Request.Builder().url(url).headers(headers()).get().build());
        }

        @Override
        public JsonObject post(HttpUrl url, JsonObject body) throws Exception {
            RequestBody requestBody = RequestBody.create(
                    body.toString(), MediaType.parse("application/json"));
            return execute(new Request.Builder().url(url).headers(headers())
                    .post(requestBody).build());
        }

        @Override
        public synchronized QuarkOpenDriveProvider.DownloadResponse postDownload(
                HttpUrl url, JsonObject body) throws Exception {
            RequestBody requestBody = RequestBody.create(
                    body.toString(), MediaType.parse("application/json"));
            // AList snapshots the cookie used to sign /file/download and sends that exact
            // snapshot to the returned CDN URL. The CDN host is not necessarily quark.cn,
            // so relying on the cookie jar to attach it there returns an image placeholder.
            String before = OkHttp.cookieHeader(url.toString());
            JsonObject result = execute(new Request.Builder().url(url).headers(headers())
                    .post(requestBody).build());
            String after = OkHttp.cookieHeader(url.toString());
            OpenDriveLog.providerDebug("quark", "cookie-rotated",
                    "rotated=" + !before.equals(after) + " present=" + !before.isBlank());
            return new QuarkOpenDriveProvider.DownloadResponse(result, before);
        }

        @Override
        public JsonObject postTranscode(HttpUrl url, JsonObject body) throws Exception {
            RequestBody requestBody = RequestBody.create(
                    body.toString(), MediaType.parse("application/json"));
            okhttp3.Headers headers = new okhttp3.Headers.Builder()
                    .add("Accept", "application/json, text/plain, */*")
                    .add("Content-Type", "application/json")
                    .add("Referer", "https://pan.quark.cn/")
                    .add("User-Agent", TRANSCODE_USER_AGENT)
                    .add("Host", "drive-pc.quark.cn")
                    .build();
            return execute(new Request.Builder().url(url).headers(headers)
                    .post(requestBody).build());
        }

        @Override
        public Response open(String url, String range) throws Exception {
            return open(url, range, "");
        }

        @Override
        public Response open(String url, String range, String cookie) throws Exception {
            return openConnection(url, range, cookie).response();
        }

        @Override
        public QuarkOpenDriveProvider.PlaybackConnection openConnection(
                String url, String range, String cookie) throws Exception {
            return openConnection(url, range, cookie, false);
        }

        @Override
        public Response openProbe(String url, String range, String cookie) throws Exception {
            return openConnection(url, range, cookie, true).response();
        }

        private QuarkOpenDriveProvider.PlaybackConnection openConnection(
                String url, String range, String cookie, boolean probe) throws Exception {
            Request.Builder request = new Request.Builder().url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://pan.quark.cn/");
            if (cookie != null && !cookie.isBlank()) request.header("Cookie", cookie);
            if (range != null && !range.isBlank()) request.header("Range", range);
            okhttp3.Call call = streamingClient().newCall(request.build());
            // Prefix normalization is best-effort. Bound DNS/connect/TLS, retries and body
            // reads together, rather than inheriting the streaming client's long timeouts.
            if (probe) call.timeout().timeout(3L, TimeUnit.SECONDS);
            Response response = call.execute();
            OpenDriveLog.providerDebug("quark", "playback-connection",
                    "protocol=" + response.protocol() + " range=" + range);
            return new QuarkOpenDriveProvider.PlaybackConnection(response, call::cancel);
        }

        private JsonObject execute(Request request) throws Exception {
            try (Response response = client().newCall(request).execute()) {
                ResponseBody body = response.body();
                if (!response.isSuccessful() || body == null) {
                    long apiCode = 0L;
                    boolean plfInvalid = false;
                    if (body != null && body.contentLength() <= 1024L * 1024L) {
                        try {
                            String errorBody = body.string();
                            plfInvalid = errorBody.contains("plf_invalid");
                            JsonElement error = JsonParser.parseString(errorBody);
                            if (error.isJsonObject()) apiCode = number(error.getAsJsonObject(), "code");
                        } catch (Exception ignored) {
                        }
                    }
                    OpenDriveLog.providerWarn("quark", "api-failure",
                            "host=" + request.url().host()
                                    + " path=" + request.url().encodedPath()
                                    + " status=" + response.code()
                                    + " apiCode=" + apiCode
                                    + " plfInvalid=" + plfInvalid);
                    throw new QuarkHttpException(
                            response.code(), request.url().encodedPath(), apiCode, plfInvalid);
                }
                if (body.contentLength() > 5L * 1024L * 1024L) {
                    throw new IOException("Quark response is too large");
                }
                JsonElement value = JsonParser.parseString(body.string());
                if (!value.isJsonObject()) throw new IOException("Quark returned an invalid response");
                OpenDriveDiagnostics.api(request.url().host(), value.getAsJsonObject());
                return value.getAsJsonObject();
            }
        }

        private okhttp3.OkHttpClient client() {
            if (client != null) return client;
            synchronized (this) {
                if (client == null) {
                    client = newNetworkClient();
                }
            }
            return client;
        }

        private okhttp3.OkHttpClient streamingClient() {
            if (streamingClient != null) return streamingClient;
            synchronized (this) {
                if (streamingClient == null) streamingClient = newStreamingClient();
            }
            return streamingClient;
        }

        private okhttp3.Headers headers() {
            return new okhttp3.Headers.Builder()
                    .add("Accept", "application/json, text/plain, */*")
                    .add("Content-Type", "application/json")
                    .add("Referer", "https://pan.quark.cn/")
                    .add("User-Agent", USER_AGENT)
                    .build();
        }
    }

    static HttpUrl endpoint(String endpoint) {
        HttpUrl base = HttpUrl.parse(API + endpoint);
        if (base == null) throw new IllegalArgumentException("Invalid Quark API endpoint");
        return base.newBuilder().addQueryParameter("pr", QUERY_PR).addQueryParameter("fr", QUERY_FR).build();
    }

    static HttpUrl downloadEndpoint() {
        return endpoint("file/download").newBuilder().addQueryParameter("entry", "ft").build();
    }

    static HttpUrl downloadEndpointWithoutEntry() {
        return endpoint("file/download");
    }

    static HttpUrl playEndpoint() {
        // AndroidCatVodSpider's current QuarkApi uses the PC API's non-project endpoint.
        // This exact request is also the one observed returning a playable rendition on device.
        return endpoint("file/v2/play");
    }

    static HttpUrl deleteEndpoint() {
        // AndroidCatVodSpider's maintained QuarkApi sends this exact client parameter on
        // file/delete. Quark can reject the otherwise identical request with HTTP 400.
        return endpoint("file/delete").newBuilder().addQueryParameter("uc_param_str", "").build();
    }

    static okhttp3.OkHttpClient newNetworkClient() {
        return configureNetworkClient(OkHttp.newClientBuilder()).build();
    }

    static okhttp3.OkHttpClient newStreamingClient() {
        return configureStreamingClient(OkHttp.newClientBuilder()).build();
    }

    static okhttp3.OkHttpClient.Builder configureNetworkClient(
            okhttp3.OkHttpClient.Builder builder) {
        // Preserve shared cookies, DNS and proxy settings, but never inherit legacy TLS
        // or a pool which may contain connections established without verification.
        okhttp3.OkHttpClient verified = new okhttp3.OkHttpClient();
        return builder
                .sslSocketFactory(verified.sslSocketFactory(), verified.x509TrustManager())
                .hostnameVerifier(verified.hostnameVerifier())
                .connectionPool(new okhttp3.ConnectionPool())
                .eventListenerFactory(OpenDriveDiagnostics.factory())
                // Streaming responses can legitimately remain open for the entire video.
                // Keep bounded connect and idle-read timeouts, but do not inherit the shared
                // client's 60-second whole-call deadline that cancels an active HTTP/2 stream.
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS);
    }

    static okhttp3.OkHttpClient.Builder configureStreamingClient(
            okhttp3.OkHttpClient.Builder builder) {
        return configureNetworkClient(builder)
                // Quark applies its download throttle per connection. HTTP/2 would multiplex
                // every range onto one TCP connection, defeating the parallel-range transport.
                // aria2 and the established TVBox Quark proxies use independent connections.
                .protocols(Collections.singletonList(okhttp3.Protocol.HTTP_1_1))
                .connectionPool(new okhttp3.ConnectionPool(32, 5, TimeUnit.MINUTES));
    }
}
