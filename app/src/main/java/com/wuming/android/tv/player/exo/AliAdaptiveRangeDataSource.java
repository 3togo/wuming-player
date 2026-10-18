package com.wuming.android.tv.player.exo;

import android.net.Uri;
import com.wuming.tv.core.playback.AdaptiveRangeConcurrency;
import android.os.SystemClock;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import com.wuming.android.tv.utils.AppLog;
import com.wuming.android.tv.utils.NetworkEnvironmentMonitor;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;

/** Bounded Ali original downloads. No speculative batch or recovery overlaps an active batch. */
final class AliAdaptiveRangeDataSource extends BaseDataSource {
    static final int RANGE_BYTES = 128 * 1024;
    static final class Factory implements DataSource.Factory {
        private final OkHttpClient client;
        private final Map<String, String> headers;
        private final long length;
        Factory(OkHttpClient client, Map<String, String> headers, long length) {
            this.client = client; this.headers = headers; this.length = length;
        }
        @Override public DataSource createDataSource() {
            return new AliAdaptiveRangeDataSource(client, headers, length);
        }
    }
    private final OkHttpClient client;
    private final Map<String, String> headers;
    private final long length;
    private final Set<Call> calls = ConcurrentHashMap.newKeySet();
    private final Object lifecycle = new Object();
    private volatile boolean closed = true;
    private ExecutorService executor, retiring;
    private AdaptiveRangeConcurrency control = new AdaptiveRangeConcurrency();
    private long generation = -1, position, remaining;
    private DataSpec spec;
    private byte[] buffered;
    private int cursor;
    private boolean opened;
    private volatile Map<String, List<String>> responseHeaders = Map.of();

    AliAdaptiveRangeDataSource(OkHttpClient client, Map<String, String> headers, long length) {
        super(true);
        this.client = client.newBuilder().retryOnConnectionFailure(false)
                .callTimeout(30, TimeUnit.SECONDS).build();
        this.headers = headers; this.length = length;
    }

    @Override public long open(DataSpec dataSpec) throws IOException {
        close();
        awaitRetired();
        long current = NetworkEnvironmentMonitor.generation();
        if (generation != current) { control = new AdaptiveRangeConcurrency(); generation = current; }
        if (dataSpec.position > length) throw new IOException("Ali range position exceeds original length");
        synchronized (lifecycle) {
            closed = false; spec = dataSpec; position = dataSpec.position;
            remaining = dataSpec.length == C.LENGTH_UNSET ? length - position
                    : Math.min(dataSpec.length, length - position);
            executor = Executors.newFixedThreadPool(AdaptiveRangeConcurrency.MAX_CONNECTIONS);
        }
        transferInitializing(dataSpec);
        opened = true; transferStarted(dataSpec);
        return remaining;
    }

    @Override public int read(byte[] buffer, int offset, int requested) throws IOException {
        if (requested == 0) return 0;
        ensureOpen();
        if (remaining == 0) return C.RESULT_END_OF_INPUT;
        if (buffered == null || cursor == buffered.length) loadBatch();
        int count = Math.min(requested, buffered.length - cursor);
        System.arraycopy(buffered, cursor, buffer, offset, count);
        cursor += count; position += count; remaining -= count;
        bytesTransferred(count);
        return count;
    }

    private void loadBatch() throws IOException {
        int recoveries = 0;
        while (true) {
            ensureOpen();
            while (SystemClock.elapsedRealtime() < control.notBeforeMs()) {
                ensureOpen();
                try { Thread.sleep(Math.max(1, Math.min(100, control.notBeforeMs() - SystemClock.elapsedRealtime()))); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw cancelled(); }
            }
            long current = NetworkEnvironmentMonitor.generation();
            if (current != generation) { control = new AdaptiveRangeConcurrency(); generation = current; }
            int concurrency = control.connections();
            int total = (int) Math.min(remaining, (long) concurrency * RANGE_BYTES);
            List<Future<byte[]>> futures = new ArrayList<>();
            var failureSignal = new java.util.concurrent.atomic.AtomicReference<IOException>();
            long started = SystemClock.elapsedRealtime();
            try {
                synchronized (lifecycle) {
                    ensureOpen();
                    for (int part = 0; part < total; part += RANGE_BYTES) {
                        long start = position + part;
                        int size = Math.min(RANGE_BYTES, total - part);
                        futures.add(executor.submit(() -> {
                            try { return fetch(start, size); }
                            catch (IOException error) { failureSignal.compareAndSet(null, error); throw error; }
                        }));
                    }
                }
                byte[] batch = new byte[total];
                int write = 0;
                for (Future<byte[]> future : futures) {
                    byte[] part;
                    while (true) {
                        ensureOpen();
                        if (failureSignal.get() != null) throw new ExecutionException(failureSignal.get());
                        try { part = future.get(100, TimeUnit.MILLISECONDS); break; }
                        catch (TimeoutException ignored) { }
                    }
                    System.arraycopy(part, 0, batch, write, part.length); write += part.length;
                }
                ensureOpen();
                long elapsed = Math.max(1, SystemClock.elapsedRealtime() - started);
                // Only a full batch is comparable. Extractor tail reads and final fragments
                // must not teach the controller that fewer requests made a faster download.
                String decision = total == concurrency * RANGE_BYTES
                        ? control.complete(total, elapsed) : "partial";
                AppLog.i("AliRange", "event=" + decision + ",connections=" + concurrency
                        + ",nextConnections=" + control.connections() + ",bytes=" + total
                        + ",elapsedMs=" + elapsed + ",aggregateBps=" + total * 1000L / elapsed);
                buffered = batch; cursor = 0; return;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt(); retireWorkers(); throw cancelled();
            } catch (CancellationException error) { retireWorkers(); throw cancelled();
            } catch (ExecutionException error) {
                Throwable cause = error.getCause();
                IOException failure = cause instanceof IOException io ? io : new IOException("Ali range failed", cause);
                retireWorkers();
                awaitRetired();
                ensureOpen();
                if (failure instanceof HttpDataSource.InvalidResponseCodeException http
                        && (http.responseCode == 429 || http.responseCode == 503)) {
                    long delay = retryAfterMillis(http.headerFields, System.currentTimeMillis());
                    control.rejected(SystemClock.elapsedRealtime(), delay);
                    AppLog.i("AliRange", "event=backoff,status=" + http.responseCode
                            + ",nextConnections=" + control.connections() + ",retryAfterMs=" + Math.max(5000, delay));
                    if (++recoveries > 2) throw failure;
                    synchronized (lifecycle) {
                        ensureOpen();
                        executor = Executors.newFixedThreadPool(AdaptiveRangeConcurrency.MAX_CONNECTIONS);
                    }
                    continue;
                }
                throw failure;
            }
        }
    }

    private byte[] fetch(long start, int size) throws IOException {
        var request = new Request.Builder().url(spec.uri.toString());
        headers.forEach(request::header);
        com.wuming.tv.core.playback.PlaybackTransportPlan.parse(spec.uri.toString(), null,
                spec.httpRequestHeaders).networkHeaders().forEach(request::header);
        request.header("Range", "bytes=" + start + "-" + (start + size - 1));
        Call call = client.newCall(request.build());
        synchronized (lifecycle) { ensureOpen(); calls.add(call); }
        try (Response response = call.execute()) {
            if (response.code() != 206) {
                throw new HttpDataSource.InvalidResponseCodeException(response.code(), response.message(),
                        null, response.headers().toMultimap(), spec, new byte[0]);
            }
            String expected = "bytes " + start + "-" + (start + size - 1) + "/" + length;
            if (!expected.equals(response.header("Content-Range")) || response.body() == null)
                throw new IOException("Ali returned an inconsistent byte range");
            responseHeaders = response.headers().toMultimap();
            byte[] result = new byte[size];
            var input = response.body().byteStream();
            int count = 0;
            while (count < size) {
                ensureOpen();
                int read = input.read(result, count, size - count);
                if (read < 0) throw new java.io.EOFException("Ali range ended early");
                count += read;
            }
            return result;
        } finally { calls.remove(call); }
    }

    static long retryAfterMillis(Map<String, List<String>> headers, long now) {
        for (var entry : headers.entrySet()) {
            if (!"Retry-After".equalsIgnoreCase(entry.getKey())) continue;
            for (String value : entry.getValue()) {
                try {
                    long delay = value.trim().matches("[0-9]+")
                            ? Math.multiplyExact(Long.parseLong(value.trim()), 1000L)
                            : java.time.ZonedDateTime.parse(value.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                                    .toInstant().toEpochMilli() - now;
                    return Math.max(0, delay);
                } catch (Exception ignored) { }
            }
        }
        return 0;
    }

    private void retireWorkers() {
        synchronized (lifecycle) {
            calls.forEach(Call::cancel);
            if (executor != null) { executor.shutdownNow(); retiring = executor; executor = null; }
        }
    }
    private void awaitRetired() throws IOException {
        if (retiring == null) return;
        try {
            if (!retiring.awaitTermination(5, TimeUnit.SECONDS)) throw new IOException("Ali ranges are still closing");
            retiring = null;
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw cancelled(); }
    }
    private void ensureOpen() throws InterruptedIOException { if (closed) throw cancelled(); }
    private static InterruptedIOException cancelled() { return new InterruptedIOException("Ali playback cancelled"); }
    @Override public Uri getUri() { return spec == null ? null : spec.uri; }
    @Override public Map<String, List<String>> getResponseHeaders() { return responseHeaders; }
    @Override public void close() {
        synchronized (lifecycle) { closed = true; retireWorkers(); buffered = null; cursor = 0; }
        if (opened) { opened = false; transferEnded(); }
    }
}
