package com.wuming.android.tv.cloud.opendrive;

import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import com.wuming.tv.core.cloud.OpenDrivePlayback;
import com.wuming.tv.core.playback.PlaybackTransportPlan;
import com.github.catvod.net.OkHttp;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.*;

/** Opt-in direct, bounded concurrency experiment; never changes the production downloader. */
final class AliConnectionRampProbe {
    private static final int PART = 128 * 1024;
    private record Result(long rate, boolean healthy, long retryAfterSeconds) { }

    static void inspect(OpenDrivePlayback playback) throws Exception {
        Result previous = null;
        long backoff = 5;
        for (int lanes : new int[]{1, 2, 4, 6, 8, 10, 12, 16}) {
            Result result = measure(playback, lanes, "ramp");
            if (!result.healthy()) {
                report("rampStop", "HTTP/transport rejection; no further increase");
                backoff = Math.max(backoff, result.retryAfterSeconds());
                break;
            }
            if (previous != null && result.rate() < previous.rate() * 9 / 10) {
                report("rampStop", "Aggregate rate fell by more than 10 percent");
                break;
            }
            previous = result;
            SystemClock.sleep(2000);
        }
        if (backoff > 60) {
            report("baselineRecheck", "Deferred to respect Retry-After");
        } else {
            SystemClock.sleep(backoff * 1000);
            measure(playback, 1, "baseline-after");
        }
    }

    private static Result measure(OpenDrivePlayback playback, int lanes, String phase) throws Exception {
        var bytes = new AtomicLong();
        var completed = new AtomicInteger();
        var errors = new AtomicInteger();
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        var qosTotal = new AtomicLong();
        var qosCount = new AtomicInteger();
        var retryAfter = new AtomicLong();
        var statuses = new ConcurrentSkipListSet<Integer>();
        var failures = new ConcurrentSkipListSet<String>();
        var calls = new ConcurrentLinkedQueue<Call>();
        var pool = new ConnectionPool();
        var client = OkHttp.player().newBuilder().proxy(Proxy.NO_PROXY)
                .connectionPool(pool).protocols(List.of(Protocol.HTTP_1_1))
                .retryOnConnectionFailure(false).callTimeout(15, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .eventListenerFactory(call -> new EventListener() {
                    @Override public void connectionAcquired(Call call, Connection connection) {
                        peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                    }
                    @Override public void connectionReleased(Call call, Connection connection) {
                        active.decrementAndGet();
                    }
                }).build();
        var headers = PlaybackTransportPlan.parse(playback.url(), null, playback.headers()).networkHeaders();
        var workers = Executors.newFixedThreadPool(lanes);
        long started = SystemClock.elapsedRealtime();
        long lastSample = started;
        long lastBytes = 0;
        try {
            for (int wave = 0; wave < 2 && errors.get() == 0; wave++) {
                var ready = new CountDownLatch(lanes);
                var start = new CountDownLatch(1);
                List<Future<?>> tasks = new ArrayList<>();
                for (int lane = 0; lane < lanes; lane++) {
                    final long offset = (long) (wave * lanes + lane) * PART;
                    tasks.add(workers.submit(() -> {
                        ready.countDown();
                        try {
                            start.await();
                            var request = new Request.Builder().url(playback.url());
                            headers.forEach(request::header);
                            request.header("Range", "bytes=" + offset + "-" + (offset + PART - 1));
                            Call call = client.newCall(request.build());
                            calls.add(call);
                            try (var response = call.execute()) {
                                statuses.add(response.code());
                                String delay = response.header("x-oss-qos-delay-time", "");
                                if (delay.matches("[0-9]{1,10}")) {
                                    qosTotal.addAndGet(Long.parseLong(delay)); qosCount.incrementAndGet();
                                }
                                String retry = response.header("Retry-After", "");
                                if (retry.matches("[0-9]{1,8}")) retryAfter.accumulateAndGet(Long.parseLong(retry), Math::max);
                                else if (!retry.isEmpty()) {
                                    try {
                                        long seconds = (java.time.ZonedDateTime.parse(retry,
                                                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                                                - System.currentTimeMillis() + 999) / 1000;
                                        retryAfter.accumulateAndGet(Math.max(0, seconds), Math::max);
                                    } catch (Exception ignored) { retryAfter.accumulateAndGet(61, Math::max); }
                                }
                                if (response.code() != 206 || response.body() == null
                                        || !response.header("Content-Range", "").startsWith(
                                        "bytes " + offset + "-" + (offset + PART - 1) + "/")) {
                                    errors.incrementAndGet(); return;
                                }
                                int readTotal = 0;
                                byte[] buffer = new byte[16384];
                                var input = response.body().byteStream();
                                while (readTotal < PART) {
                                    int read = input.read(buffer, 0, Math.min(buffer.length, PART - readTotal));
                                    if (read < 0) throw new java.io.EOFException();
                                    readTotal += read; bytes.addAndGet(read);
                                }
                                completed.incrementAndGet();
                            } finally { calls.remove(call); }
                        } catch (Exception error) {
                            errors.incrementAndGet(); failures.add(error.getClass().getSimpleName());
                        }
                    }));
                }
                ready.await(); start.countDown();
                while (tasks.stream().anyMatch(task -> !task.isDone())) {
                    SystemClock.sleep(250);
                    if (errors.get() > 0) calls.forEach(Call::cancel);
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastSample >= 2000) {
                        Bundle sample = new Bundle();
                        sample.putString("rampPhase", phase);
                        sample.putInt("rampConnections", lanes);
                        sample.putLong("rampElapsedMs", now - started);
                        sample.putLong("rampReceivedBytes", bytes.get());
                        sample.putLong("rampIntervalBytesPerSecond", (bytes.get() - lastBytes) * 1000 / (now - lastSample));
                        send(sample); lastSample = now; lastBytes = bytes.get();
                    }
                }
                for (var task : tasks) task.get();
            }
        } finally {
            calls.forEach(Call::cancel); workers.shutdownNow(); pool.evictAll();
        }
        long elapsed = Math.max(1, SystemClock.elapsedRealtime() - started);
        long rate = bytes.get() * 1000 / elapsed;
        Bundle result = new Bundle();
        result.putString("rampResultPhase", phase);
        result.putInt("rampConnections", lanes);
        result.putInt("peakActiveConnections", peak.get());
        result.putLong("totalBytes", bytes.get());
        result.putLong("elapsedMs", elapsed);
        result.putLong("aggregateBytesPerSecond", rate);
        result.putInt("completedRequests", completed.get());
        result.putInt("failedRequests", errors.get());
        result.putString("httpStatuses", statuses.toString());
        result.putString("transportErrors", failures.toString());
        result.putLong("meanQosDelayMs", qosCount.get() == 0 ? 0 : qosTotal.get() / qosCount.get());
        result.putLong("retryAfterSeconds", retryAfter.get());
        send(result);
        return new Result(rate, errors.get() == 0 && completed.get() == lanes * 2, retryAfter.get());
    }

    private static void report(String key, String value) {
        Bundle status = new Bundle(); status.putString(key, value); send(status);
    }
    private static void send(Bundle status) { InstrumentationRegistry.getInstrumentation().sendStatus(0, status); }
}
