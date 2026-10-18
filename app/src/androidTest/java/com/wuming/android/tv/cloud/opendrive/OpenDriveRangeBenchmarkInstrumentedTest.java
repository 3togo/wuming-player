package com.wuming.android.tv.cloud.opendrive;

import com.wuming.tv.core.cloud.*;

import static org.junit.Assert.assertTrue;

import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import okhttp3.HttpUrl;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Opt-in device benchmark for deciding whether another provider may use parallel ranges.
 *
 * <p>It never logs a URL, query, file name, handle, cookie, or token. A provider is only measured
 * after a real response proves bounded HTTP 206 semantics. This test reports evidence; it does not
 * modify the production allowlist.</p>
 */
@RunWith(AndroidJUnit4.class)
public class OpenDriveRangeBenchmarkInstrumentedTest {

    private static final String TAG = "OpenDriveRangeBench";
    private static final long MINIMUM_FILE_BYTES = 16L * 1024L * 1024L;
    private static final long PROBE_BYTES = 64L * 1024L;
    private static final long TEST_BYTES = 2L * 1024L * 1024L;
    private static final int PARALLELISM = 4;

    @Test
    public void quarkConcurrencySweepReportsRepeatability() throws Exception {
        boolean enabled = Boolean.parseBoolean(InstrumentationRegistry.getArguments()
                .getString("run_quark_concurrency_sweep", "false"));
        Assume.assumeTrue("Quark concurrency sweep is opt-in", enabled);
        OpenDriveEngine engine = new OpenDriveEngine();
        OpenDriveProvider provider = engine.provider("quark");
        Assume.assumeTrue("Quark is not authenticated", provider.authenticated());
        OpenDriveItem item = firstLargeFile(engine, "quark");
        Assume.assumeNotNull(item);
        Target target = target(provider, engine.resolve("quark", item.id()));
        ReadResult probe = target.read(0L, PROBE_BYTES);
        requireRange(probe, 0L, PROBE_BYTES);

        long bytes = 1024L * 1024L;
        long start = PROBE_BYTES;
        int[] order = {1, 2, 4, 4, 2, 1};
        long[] speeds = new long[order.length];
        for (int index = 0; index < order.length; index++) {
            long elapsed = measureParallel(target, start, bytes, order[index]);
            speeds[index] = bytesPerSecond(bytes, elapsed);
            start += bytes;
        }
        Log.i(TAG, "provider=quark,result=concurrency_sweep"
                + ",c1FirstBps=" + speeds[0] + ",c1SecondBps=" + speeds[5]
                + ",c2FirstBps=" + speeds[1] + ",c2SecondBps=" + speeds[4]
                + ",c4FirstBps=" + speeds[2] + ",c4SecondBps=" + speeds[3]);
    }

    @Test
    public void authenticatedProvidersReportProvenRangeThroughput() throws Exception {
        boolean enabled = Boolean.parseBoolean(InstrumentationRegistry.getArguments()
                .getString("run_cloud_range_benchmark", "false"));
        Assume.assumeTrue("Cloud range benchmark is opt-in", enabled);

        OpenDriveEngine engine = new OpenDriveEngine();
        int candidates = 0;
        for (String providerId : List.of("alipan", "baidu", "quark", "uc")) {
            OpenDriveProvider provider = engine.provider(providerId);
            if (!provider.authenticated()) {
                Log.i(TAG, "provider=" + providerId + ",result=not_authenticated");
                continue;
            }
            try {
                OpenDriveItem item = firstLargeFile(engine, providerId);
                if (item == null) {
                    Log.i(TAG, "provider=" + providerId + ",result=no_large_media");
                    continue;
                }
                candidates++;
                OpenDrivePlayback playback = engine.resolve(providerId, item.id());
                Target target = target(provider, playback);
                ReadResult probe = target.read(0L, PROBE_BYTES);
                if (probe.code != 206 || !validContentRange(probe.contentRange, 0L)
                        || isSegmentedManifest(probe.contentType, target.path)) {
                    Log.i(TAG, "provider=" + providerId + ",topology=" + target.topology
                            + ",host=" + target.host + ",result=not_eligible,code="
                            + probe.code + ",contentType=" + safeType(probe.contentType)
                            + ",contentRange=" + !probe.contentRange.isBlank());
                    continue;
                }

                long sequentialFirstStart = PROBE_BYTES;
                long parallelSecondStart = sequentialFirstStart + TEST_BYTES;
                long parallelFirstStart = parallelSecondStart + TEST_BYTES;
                long sequentialSecondStart = parallelFirstStart + TEST_BYTES;
                long sequentialFirstMillis = measureSequential(
                        target, sequentialFirstStart, TEST_BYTES);
                long parallelSecondMillis = measureParallel(
                        target, parallelSecondStart, TEST_BYTES, PARALLELISM);
                long parallelFirstMillis = measureParallel(
                        target, parallelFirstStart, TEST_BYTES, PARALLELISM);
                long sequentialSecondMillis = measureSequential(
                        target, sequentialSecondStart, TEST_BYTES);
                long sequentialBps = bytesPerSecond(TEST_BYTES * 2L,
                        sequentialFirstMillis + sequentialSecondMillis);
                long parallelBps = bytesPerSecond(TEST_BYTES * 2L,
                        parallelFirstMillis + parallelSecondMillis);
                double ratio = sequentialBps == 0L ? 0D
                        : (double) parallelBps / (double) sequentialBps;
                Log.i(TAG, "provider=" + providerId + ",topology=" + target.topology
                        + ",host=" + target.host + ",result=measured,code=206"
                        + ",contentType=" + safeType(probe.contentType)
                        + ",sequentialBps=" + sequentialBps
                        + ",parallelBps=" + parallelBps
                        + ",sequentialFirstBps="
                        + bytesPerSecond(TEST_BYTES, sequentialFirstMillis)
                        + ",sequentialSecondBps="
                        + bytesPerSecond(TEST_BYTES, sequentialSecondMillis)
                        + ",parallelFirstBps="
                        + bytesPerSecond(TEST_BYTES, parallelFirstMillis)
                        + ",parallelSecondBps="
                        + bytesPerSecond(TEST_BYTES, parallelSecondMillis)
                        + ",ratio=" + String.format(Locale.ROOT, "%.2f", ratio));
            } catch (Exception error) {
                Log.i(TAG, "provider=" + providerId + ",result=failed,type="
                        + error.getClass().getSimpleName());
            }
        }
        assertTrue("No authenticated provider exposed a large media candidate", candidates > 0);
    }

    private static long measureSequential(Target target, long start, long length)
            throws Exception {
        long startedAt = SystemClock.elapsedRealtime();
        ReadResult result = target.read(start, length);
        requireRange(result, start, length);
        return Math.max(1L, SystemClock.elapsedRealtime() - startedAt);
    }

    private static long measureParallel(Target target, long start, long totalLength,
                                        int parallelism)
            throws Exception {
        long partLength = totalLength / parallelism;
        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        List<Future<ReadResult>> futures = new ArrayList<>();
        long startedAt = SystemClock.elapsedRealtime();
        try {
            for (int index = 0; index < parallelism; index++) {
                long partStart = start + index * partLength;
                futures.add(executor.submit(() -> target.read(partStart, partLength)));
            }
            for (int index = 0; index < futures.size(); index++) {
                requireRange(futures.get(index).get(), start + index * partLength, partLength);
            }
            return Math.max(1L, SystemClock.elapsedRealtime() - startedAt);
        } finally {
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
        }
    }

    private static void requireRange(ReadResult result, long start, long expectedBytes)
            throws IOException {
        if (result.code != 206 || result.bytes != expectedBytes
                || !validContentRange(result.contentRange, start)) {
            throw new IOException("Provider did not preserve bounded range semantics");
        }
    }

    private static Target target(OpenDriveProvider provider, OpenDrivePlayback playback) {
        HttpUrl url = HttpUrl.parse(playback.url());
        if (url == null) throw new IllegalArgumentException("Invalid playback URL");
        if ("127.0.0.1".equals(url.host())) {
            LinkedHashMap<String, String> base = new LinkedHashMap<>();
            for (String name : url.queryParameterNames()) {
                base.put(name, url.queryParameter(name));
            }
            return new Target("proxy", url.host(), url.encodedPath(), (start, length) -> {
                LinkedHashMap<String, String> params = new LinkedHashMap<>(base);
                params.put("range", range(start, length));
                OpenDriveProxyResponse response = provider.proxy(params);
                try (InputStream input = response.getStream()) {
                    return new ReadResult(response.getStatus(), readExactly(input, length),
                            header(response.getHeaders(), "Content-Range"), response.getMimeType());
                }
            });
        }
        LinkedHashMap<String, String> headers = new LinkedHashMap<>(playback.headers());
        headers.entrySet().removeIf(entry ->
                "X-OpenDrive-Content-Length".equalsIgnoreCase(entry.getKey()));
        return new Target("direct", url.host(), url.encodedPath(), (start, length) -> {
            try (Response response = OpenDriveHttp.openExplicit(
                    playback.url(), range(start, length), headers)) {
                ResponseBody body = response.body();
                if (body == null) throw new IOException("Provider returned an empty body");
                try (InputStream input = body.byteStream()) {
                    return new ReadResult(response.code(), readExactly(input, length),
                            response.header("Content-Range", ""),
                            response.header("Content-Type", ""));
                }
            }
        });
    }

    private static OpenDriveItem firstLargeFile(OpenDriveEngine engine, String providerId)
            throws Exception {
        ArrayDeque<String> folders = new ArrayDeque<>();
        folders.add("root");
        int visited = 0;
        while (!folders.isEmpty() && visited++ < 64) {
            for (OpenDriveItem item : engine.list(providerId, folders.removeFirst())) {
                if (item.directory()) folders.addLast(item.id());
                else if (item.size() >= MINIMUM_FILE_BYTES) return item;
            }
        }
        return null;
    }

    private static long readExactly(InputStream input, long expected) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0L;
        while (total < expected) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, expected - total));
            if (read == -1) break;
            total += read;
        }
        return total;
    }

    private static String range(long start, long length) {
        return "bytes=" + start + '-' + (start + length - 1L);
    }

    private static boolean validContentRange(String value, long start) {
        return value != null && value.toLowerCase(Locale.ROOT)
                .startsWith("bytes " + start + '-');
    }

    private static boolean isSegmentedManifest(String contentType, String path) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String normalizedPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return type.contains("mpegurl") || type.contains("dash")
                || normalizedPath.endsWith(".m3u8") || normalizedPath.endsWith(".mpd");
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) return "";
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return "";
    }

    private static String safeType(String value) {
        if (value == null || value.isBlank()) return "";
        int separator = value.indexOf(';');
        return (separator < 0 ? value : value.substring(0, separator))
                .replace(',', '_').replace(' ', '_');
    }

    private static long bytesPerSecond(long bytes, long elapsedMillis) {
        return elapsedMillis <= 0L ? 0L : bytes * 1_000L / elapsedMillis;
    }

    private interface RangeReader {
        ReadResult read(long start, long length) throws Exception;
    }

    private record Target(String topology, String host, String path, RangeReader reader) {
        private ReadResult read(long start, long length) throws Exception {
            return reader.read(start, length);
        }
    }

    private record ReadResult(int code, long bytes, String contentRange, String contentType) {
    }
}
