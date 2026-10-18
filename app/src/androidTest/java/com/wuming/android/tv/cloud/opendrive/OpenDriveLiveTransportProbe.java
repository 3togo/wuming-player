package com.wuming.android.tv.cloud.opendrive;

import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import com.wuming.tv.core.cloud.OpenDrivePlayback;
import com.wuming.tv.core.playback.PlaybackTransportPlan;
import com.github.catvod.net.OkHttp;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.Protocol;
import okhttp3.Request;

/** Explicit bounded device diagnostic; reports timings, never signed URLs or credentials. */
final class OpenDriveLiveTransportProbe {
    static void inspect(OpenDrivePlayback playback) {
        probe(playback, "clean-h1-256k", "bytes=0-262143", true);
        probe(playback, "h1-64k", "bytes=0-65535", true);
        probe(playback, "h1-256k", "bytes=0-262143", true);
        probe(playback, "h2-256k", "bytes=0-262143", false);
        probe(playback, "h1-open-ended", "bytes=0-", true);
    }

    static void compareProxy(OpenDrivePlayback playback) {
        probe(playback, "app-default-proxy", "bytes=0-262143", true);
        probe(playback, "forced-no-proxy", "bytes=0-262143", true);
    }

    private static void probe(OpenDrivePlayback playback, String label, String range, boolean h1) {
        var routeTypes = new java.util.concurrent.ConcurrentSkipListSet<String>();
        var builder = (label.startsWith("clean") ? new okhttp3.OkHttpClient.Builder()
                : OkHttp.player().newBuilder()).callTimeout(20, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .protocols(h1 ? List.of(Protocol.HTTP_1_1) : List.of(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .connectionPool(new okhttp3.ConnectionPool())
                .eventListener(new okhttp3.EventListener() {
                    @Override public void connectionAcquired(okhttp3.Call call, okhttp3.Connection connection) {
                        routeTypes.add(connection.route().proxy().type().name());
                    }
                });
        if (label.equals("forced-no-proxy")) builder.proxy(java.net.Proxy.NO_PROXY);
        var client = builder.build();
        var request = new Request.Builder().url(playback.url());
        PlaybackTransportPlan.parse(playback.url(), null, playback.headers()).networkHeaders()
                .forEach(request::header);
        request.header("Range", range);
        long started = SystemClock.elapsedRealtime();
        Bundle status = new Bundle();
        status.putString("transportProbe", label);
        int bytes = 0;
        try (var response = client.newCall(request.build()).execute()) {
            status.putInt("httpStatus", response.code());
            status.putString("qosDelay", response.header("x-oss-qos-delay-time", ""));
            status.putString("server", response.header("Server", ""));
            String limit = response.request().url().queryParameter("x-oss-traffic-limit");
            if (limit != null && limit.matches("[0-9]+")) status.putString("signedTrafficLimit", limit);
            status.putString("protocol", response.protocol().toString());
            status.putLong("headersMs", SystemClock.elapsedRealtime() - started);
            status.putString("contentRange", response.header("Content-Range", ""));
            if (response.isSuccessful() && response.body() != null) {
                var input = response.body().byteStream();
                byte[] buffer = new byte[16384];
                while (bytes < 262144) {
                    int read = input.read(buffer, 0, Math.min(buffer.length, 262144 - bytes));
                    if (read < 0) break;
                    bytes += read;
                }
            }
        } catch (Exception error) {
            status.putString("probeError", error.getClass().getSimpleName());
        }
        status.putString("proxyRouteTypes", routeTypes.toString());
        String host = okhttp3.HttpUrl.get(playback.url()).host();
        status.putBoolean("localMediaRelay", host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1"));
        client.connectionPool().evictAll();
        status.putInt("probeBytes", bytes);
        status.putLong("totalMs", SystemClock.elapsedRealtime() - started);
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }
}
