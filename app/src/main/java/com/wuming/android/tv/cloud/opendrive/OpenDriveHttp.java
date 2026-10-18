package com.wuming.android.tv.cloud.opendrive;

import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wuming.tv.core.cloud.CloudJson;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

final class OpenDriveHttp {

    static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final long MAX_JSON_BYTES = 5L * 1024L * 1024L;
    private static volatile okhttp3.OkHttpClient client;
    private static volatile okhttp3.OkHttpClient explicitCookieClient;

    private OpenDriveHttp() {
    }

    static JsonObject get(String url, Map<String, String> query, Map<String, String> headers)
            throws Exception {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) throw new IllegalArgumentException("Invalid cloud API URL");
        HttpUrl.Builder builder = parsed.newBuilder();
        if (query != null) query.forEach(builder::addQueryParameter);
        return executeJson(new Request.Builder().url(builder.build()).headers(headers(headers)).get().build());
    }

    static String getText(String url, Map<String, String> headers) throws Exception {
        return executeText(new Request.Builder().url(requireUrl(url)).headers(headers(headers)).get().build());
    }

    static String getTextExplicit(String url, Map<String, String> headers) throws Exception {
        return executeText(new Request.Builder().url(requireUrl(url)).headers(headers(headers))
                .get().build(), explicitCookieClient());
    }

    static String getText(String url, Map<String, String> query, Map<String, String> headers)
            throws Exception {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) throw new IllegalArgumentException("Invalid cloud API URL");
        HttpUrl.Builder builder = parsed.newBuilder();
        if (query != null) query.forEach(builder::addQueryParameter);
        return executeText(new Request.Builder().url(builder.build()).headers(headers(headers)).get().build());
    }

    static JsonObject postJson(String url, JsonObject body, Map<String, String> headers)
            throws Exception {
        RequestBody requestBody = RequestBody.create(body.toString(), MediaType.parse("application/json"));
        return executeJson(new Request.Builder().url(requireUrl(url)).headers(headers(headers))
                .post(requestBody).build());
    }

    static JsonObject postJsonAllowNoContent(String url, JsonObject body, Map<String, String> headers)
            throws Exception {
        RequestBody requestBody = RequestBody.create(body.toString(), MediaType.parse("application/json"));
        Request request = new Request.Builder().url(requireUrl(url)).headers(headers(headers))
                .post(requestBody).build();
        return executeJsonResponse(request, client(), true).body();
    }

    static JsonObject postForm(String url, Map<String, String> form, Map<String, String> headers)
            throws Exception {
        FormBody.Builder body = new FormBody.Builder();
        form.forEach(body::add);
        return executeJson(new Request.Builder().url(requireUrl(url)).headers(headers(headers))
                .post(body.build()).build());
    }

    /**
     * Executes a JSON request without the shared CookieJar and exposes its
     * Set-Cookie headers. Providers whose reviewed protocol carries one
     * explicit cookie string across sibling hosts use these methods so OkHttp
     * cannot replace that string with host-scoped jar cookies.
     */
    static JsonResponse getExplicit(String url, Map<String, String> query,
                                    Map<String, String> headers) throws Exception {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) throw new IllegalArgumentException("Invalid cloud API URL");
        HttpUrl.Builder builder = parsed.newBuilder();
        if (query != null) query.forEach(builder::addQueryParameter);
        Request request = new Request.Builder().url(builder.build())
                .headers(headers(headers)).get().build();
        return executeJsonResponse(request, explicitCookieClient());
    }

    static JsonResponse postFormExplicit(String url, Map<String, String> form,
                                         Map<String, String> headers) throws Exception {
        FormBody.Builder body = new FormBody.Builder();
        form.forEach(body::add);
        Request request = new Request.Builder().url(requireUrl(url)).headers(headers(headers))
                .post(body.build()).build();
        return executeJsonResponse(request, explicitCookieClient());
    }

    static JsonResponse postJsonExplicit(String url, JsonObject body,
                                         Map<String, String> headers) throws Exception {
        RequestBody requestBody = RequestBody.create(body.toString(),
                MediaType.parse("application/json"));
        Request request = new Request.Builder().url(requireUrl(url)).headers(headers(headers))
                .post(requestBody).build();
        return executeJsonResponse(request, explicitCookieClient());
    }

    /**
     * Like {@link #getText(String, Map, Map)} but uses the explicit-cookie client
     * and exposes Set-Cookie headers, so a login flow can replay one cookie string
     * across sibling hosts while still observing what the login host returned.
     */
    static TextResponse getTextExplicit(String url, Map<String, String> query,
                                        Map<String, String> headers) throws Exception {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) throw new IllegalArgumentException("Invalid cloud API URL");
        HttpUrl.Builder builder = parsed.newBuilder();
        if (query != null) query.forEach(builder::addQueryParameter);
        Request request = new Request.Builder().url(builder.build())
                .headers(headers(headers)).get().build();
        return executeTextResponse(request, explicitCookieClient());
    }

    private static TextResponse executeTextResponse(Request request, okhttp3.OkHttpClient client)
            throws Exception {
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw statusException(response);
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Cloud provider returned an empty response");
            return new TextResponse(readLimited(body), List.copyOf(response.headers("Set-Cookie")));
        }
    }

    static Response open(String url, String range, Map<String, String> headers) throws Exception {
        return open(url, range, headers, client());
    }

    static Response openExplicit(String url, String range, Map<String, String> headers)
            throws Exception {
        return open(url, range, headers, explicitCookieClient());
    }

    private static Response open(String url, String range, Map<String, String> headers,
                                 okhttp3.OkHttpClient client) throws Exception {
        Request.Builder request = new Request.Builder().url(requireUrl(url)).headers(headers(headers));
        if (range != null && !range.isBlank()) request.header("Range", range);
        return client.newCall(request.build()).execute();
    }

    static String string(JsonObject object, String key) {
        return CloudJson.string(object, key);
    }

    static long number(JsonObject object, String key) {
        return CloudJson.number(object, key);
    }

    static boolean bool(JsonObject object, String key) {
        return CloudJson.bool(object, key);
    }

    static JsonObject object(JsonObject parent, String key) {
        return CloudJson.jsonObject(parent, key);
    }

    static JsonArray array(JsonObject object, String key) {
        return CloudJson.array(object, key);
    }

    static String first(JsonObject object, String... keys) {
        return CloudJson.first(object, keys);
    }

    static String name(JsonObject object, String key) {
        String value = string(object, key);
        return value.isBlank() ? "Cloud file" : value;
    }

    static String required(JsonObject object, String key, String providerName) {
        String value = string(object, key);
        if (value.isBlank()) throw new IllegalArgumentException(
                providerName + " response is missing file metadata");
        return value;
    }

    static JsonObject data(JsonObject root, String providerName) throws IOException {
        JsonObject data = object(root, "data");
        if (data.size() == 0) throw new IOException(providerName + " API request failed");
        return data;
    }

    static boolean isVideo(JsonObject item, String categoryKey, String nameKey) {
        if ("video".equalsIgnoreCase(string(item, categoryKey))) return true;
        return com.wuming.tv.core.media.MediaFileClassifier.isCloudVideoFileName(string(item, nameKey));
    }

    static JsonObject json(Object... entries) {
        JsonObject result = new JsonObject();
        for (int index = 0; index < entries.length; index += 2) {
            String key = (String) entries[index];
            Object value = entries[index + 1];
            if (value instanceof String item) result.addProperty(key, item);
            else if (value instanceof Number item) result.addProperty(key, item);
            else if (value instanceof Boolean item) result.addProperty(key, item);
            else if (value instanceof JsonElement item) result.add(key, item);
            else if (value instanceof Iterable<?> items) {
                com.google.gson.JsonArray array = new com.google.gson.JsonArray();
                for (Object item : items) array.add(String.valueOf(item));
                result.add(key, array);
            }
        }
        return result;
    }

    static boolean validHttpsUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getPort() == -1;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static JsonObject executeJson(Request request) throws Exception {
        return executeJsonResponse(request, client()).body();
    }

    private static JsonResponse executeJsonResponse(Request request, okhttp3.OkHttpClient client)
            throws Exception {
        return executeJsonResponse(request, client, false);
    }

    static JsonResponse executeJsonResponse(Request request, okhttp3.OkHttpClient client,
            boolean allowNoContent) throws Exception {
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw statusException(response);
            if (allowNoContent && response.code() == 204)
                return new JsonResponse(new JsonObject(), List.copyOf(response.headers("Set-Cookie")));
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Cloud provider returned an empty response");
            String content = readLimited(body);
            JsonElement value;
            try {
                value = JsonParser.parseString(stripJsonp(content));
            } catch (Exception error) {
                throw new IOException("Cloud provider returned invalid JSON", error);
            }
            if (!value.isJsonObject()) throw new IOException("Cloud provider returned invalid JSON");
            OpenDriveDiagnostics.api(request.url().host(), value.getAsJsonObject());
            return new JsonResponse(value.getAsJsonObject(),
                    List.copyOf(response.headers("Set-Cookie")));
        }
    }

    private static String executeText(Request request) throws Exception {
        return executeText(request, client());
    }

    private static String executeText(Request request, okhttp3.OkHttpClient client) throws Exception {
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw statusException(response);
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Cloud provider returned an empty response");
            return readLimited(body);
        }
    }

    static String readLimited(ResponseBody body) throws IOException {
        long declared = body.contentLength();
        if (declared > MAX_JSON_BYTES) throw new IOException("Cloud response is too large");
        BufferedSource source = body.source();
        source.request(MAX_JSON_BYTES + 1L);
        if (source.getBuffer().size() > MAX_JSON_BYTES) {
            throw new IOException("Cloud response is too large");
        }
        return source.readString(StandardCharsets.UTF_8);
    }

    private static StatusException statusException(Response response) {
        int code = response.code();
        String bodyPreview = "";
        try (ResponseBody body = response.body()) {
            if (body != null) {
                String raw = body.string();
                bodyPreview = raw.substring(0, Math.min(raw.length(), 2048));
            }
        } catch (Exception ignored) {
        }
        OpenDriveLog.httpBody(OpenDriveDiagnostics.provider(response.request().url().host()), code, bodyPreview);
        return new StatusException(code, bodyPreview);
    }

    static final class StatusException extends IOException {
        private final int code;
        private final String responseBody;

        StatusException(int code) {
            this(code, "");
        }

        StatusException(int code, String responseBody) {
            super("Cloud API request failed (" + code + ")");
            this.code = code;
            String value = responseBody == null ? "" : responseBody;
            this.responseBody = value.substring(0, Math.min(value.length(), 2048));
        }

        int code() {
            return code;
        }

        String responseBody() {
            return responseBody;
        }
    }

    private static String stripJsonp(String value) {
        String content = value == null ? "" : value.trim();
        int open = content.indexOf('(');
        if (open > 0 && content.endsWith(")")) return content.substring(open + 1, content.length() - 1);
        return content;
    }

    private static HttpUrl requireUrl(String value) {
        HttpUrl url = HttpUrl.parse(value);
        if (url == null || !"https".equals(url.scheme())) throw new IllegalArgumentException("Invalid cloud API URL");
        return url;
    }

    private static Headers headers(Map<String, String> values) {
        Headers.Builder result = new Headers.Builder();
        result.add("Accept", "application/json, text/plain, */*");
        result.add("User-Agent", USER_AGENT);
        if (values != null) values.forEach((key, value) -> {
            if (key != null && value != null && !value.isBlank()) result.set(key, value);
        });
        return result.build();
    }

    private static okhttp3.OkHttpClient client() {
        if (client != null) return client;
        synchronized (OpenDriveHttp.class) {
            if (client == null) {
                // Credential and playback traffic must retain Android's normal
                // certificate and hostname verification even if a legacy
                // external spider enabled compatibility TLS globally.
                client = new okhttp3.OkHttpClient.Builder()
                        .eventListenerFactory(OpenDriveDiagnostics.factory())
                        .cookieJar(OkHttp.cookieJar())
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .build();
            }
        }
        return client;
    }

    private static okhttp3.OkHttpClient explicitCookieClient() {
        if (explicitCookieClient != null) return explicitCookieClient;
        synchronized (OpenDriveHttp.class) {
            if (explicitCookieClient == null) {
                explicitCookieClient = client().newBuilder()
                        .cookieJar(CookieJar.NO_COOKIES)
                        .build();
            }
        }
        return explicitCookieClient;
    }

    record JsonResponse(JsonObject body, List<String> setCookieHeaders) {
    }

    record TextResponse(String body, List<String> setCookieHeaders) {
    }
}
