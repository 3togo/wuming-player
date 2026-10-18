package com.wuming.android.tv.cloud.opendrive;

import android.util.Log;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import okhttp3.Response;

/** Opt-in diagnostics: never log credentials, query strings, or signed media paths. */
final class OpenDriveHttpAudit {
    static void install() throws Exception {
        Thread target = Thread.currentThread();
        Thread watchdog = new Thread(() -> {
            try {
                for (int n = 0; n < 6; n++) {
                    Thread.sleep(15000);
                    for (StackTraceElement frame : target.getStackTrace())
                        Log.i("OpenDriveHttpAudit", "testThread " + frame);
                }
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }, "cloud-audit-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        for (String name : List.of("client", "explicitCookieClient")) {
            Log.i("OpenDriveHttpAudit", "install=" + name);
            java.lang.reflect.Method method = OpenDriveHttp.class.getDeclaredMethod(name);
            method.setAccessible(true);
            OkHttpClient base = (OkHttpClient) method.invoke(null);
            Field field = OpenDriveHttp.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, base.newBuilder().callTimeout(40, TimeUnit.SECONDS)
                    .eventListenerFactory(call -> new EventListener() {
                        final long started = System.nanoTime();
                        void event(String value) {
                            String host = call.request().url().host();
                            String path = host.equals("api.aliyundrive.com") || host.equals("pan.baidu.com")
                                    ? call.request().url().encodedPath() : "";
                            Log.i("OpenDriveHttpAudit", host + path + " " + value + " ms="
                                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                        }
                        @Override public void callStart(Call call) { event("start"); }
                        @Override public void dnsStart(Call call, String name) { event("dns"); }
                        @Override public void connectStart(Call call, InetSocketAddress address, Proxy proxy) { event("connect"); }
                        @Override public void responseHeadersEnd(Call call, Response response) { event("HTTP=" + response.code()); }
                        @Override public void callFailed(Call call, java.io.IOException error) { event("failed=" + error.getClass().getSimpleName()); }
                        @Override public void callEnd(Call call) { event("end"); }
                    }).addInterceptor(chain -> {
                        Response response = chain.proceed(chain.request());
                        if (chain.request().url().host().equals("api.aliyundrive.com")) {
                            try {
                                JsonObject root = JsonParser.parseString(response.peekBody(65536).string()).getAsJsonObject();
                                String code = OpenDriveHttp.string(root, "code").replaceAll("[^A-Za-z0-9_.-]", "");
                                Log.i("OpenDriveHttpAudit", "ali responseCode=" + code);
                                String reason = OpenDriveHttp.string(root, "message")
                                        .replaceAll("https?://[^\\s]+", "[url]")
                                        .replaceAll("[A-Za-z0-9_=-]{20,}", "[value]");
                                if (!code.isBlank()) Log.i("OpenDriveHttpAudit", "ali reason="
                                        + reason.substring(0, Math.min(200, reason.length())));
                                JsonObject info = OpenDriveHttp.object(root, "video_preview_play_info");
                                if (info.has("live_transcoding_task_list")) {
                                    for (var value : info.getAsJsonArray("live_transcoding_task_list")) {
                                        JsonObject task = value.getAsJsonObject();
                                        Log.i("OpenDriveHttpAudit", "ali rendition=" + OpenDriveHttp.string(task, "template_id")
                                                + " status=" + OpenDriveHttp.string(task, "status")
                                                + " hasUrl=" + !OpenDriveHttp.string(task, "url").isBlank()
                                                + " hasPreview=" + !OpenDriveHttp.string(task, "preview_url").isBlank()
                                                + " keys=" + task.keySet());
                                    }
                                }
                            } catch (Exception ignored) { }
                        }
                        return response;
                    }).build());
        }

    }
}
