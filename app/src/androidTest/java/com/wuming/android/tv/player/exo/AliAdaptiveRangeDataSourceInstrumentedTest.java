package com.wuming.android.tv.player.exo;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSpec;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.*;
import okio.BufferedSource;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AliAdaptiveRangeDataSourceInstrumentedTest {
    @Test public void adaptiveReadsPreserveBytesAndRecoverAtLowerConcurrency() throws Exception {
        byte[] content = new byte[4 * 1024 * 1024 + 31]; new Random(7).nextBytes(content);
        var active = new AtomicInteger(); var peak = new AtomicInteger();
        var rejectedAt = new AtomicLong(); var resumedAt = new AtomicLong();
        var badHeaders = new AtomicBoolean();
        var client = new OkHttpClient.Builder().addInterceptor(chain -> {
            if (chain.request().header("X-OpenDrive-Copy-Lease") != null) badHeaders.set(true);
            int connections = active.incrementAndGet(); peak.accumulateAndGet(connections, Math::max);
            long began = android.os.SystemClock.elapsedRealtime();
            if (rejectedAt.get() > 0 && began - rejectedAt.get() > 1000) resumedAt.compareAndSet(0, began);
            try { Thread.sleep(60); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            String[] range = chain.request().header("Range").substring(6).split("-");
            int from = Integer.parseInt(range[0]), to = Integer.parseInt(range[1]);
            int status = connections > 4 ? 503 : 206;
            if (status == 503) rejectedAt.compareAndSet(0, android.os.SystemClock.elapsedRealtime());
            ResponseBody delegate = ResponseBody.create(null, status == 206
                    ? Arrays.copyOfRange(content, from, to + 1) : new byte[0]);
            ResponseBody body = new ResponseBody() {
                private boolean closed;
                public MediaType contentType() { return null; }
                public long contentLength() { return delegate.contentLength(); }
                public BufferedSource source() { return delegate.source(); }
                @Override public void close() {
                    if (!closed) { closed = true; active.decrementAndGet(); delegate.close(); }
                }
            };
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(status).message("fixture").header("Retry-After", "1")
                    .header("Content-Range", "bytes " + from + "-" + to + "/" + content.length).body(body).build();
        }).build();
        var source = new AliAdaptiveRangeDataSource(client, Map.of(), content.length);
        var output = new ByteArrayOutputStream();
        try {
            source.open(new DataSpec.Builder().setUri(Uri.parse("https://fixture.example/video"))
                    .setHttpRequestHeaders(Map.of("X-OpenDrive-Copy-Lease", "private")).build());
            byte[] buffer = new byte[8192]; int read;
            while ((read = source.read(buffer, 0, buffer.length)) != C.RESULT_END_OF_INPUT) output.write(buffer, 0, read);
        } finally { source.close(); }
        assertArrayEquals(content, output.toByteArray());
        assertTrue(peak.get() >= 5); assertTrue(peak.get() <= 6);
        assertTrue(resumedAt.get() - rejectedAt.get() >= 5000);
        assertEquals(0, active.get()); assertFalse(badHeaders.get());
        // A seek after close starts at the requested byte, not at the previous batch boundary.
        source.open(new DataSpec.Builder().setUri(Uri.parse("https://fixture.example/video"))
                .setPosition(333).setLength(4097).build());
        byte[] seek = new byte[4097]; int n = source.read(seek, 0, seek.length); source.close();
        assertEquals(seek.length, n); assertArrayEquals(Arrays.copyOfRange(content, 333, 4430), seek);
        assertEquals(0, source.open(new DataSpec.Builder().setUri(Uri.parse("https://fixture.example/video"))
                .setPosition(content.length).build()));
        assertEquals(C.RESULT_END_OF_INPUT, source.read(new byte[1], 0, 1)); source.close();
    }

    @Test public void cancellationStopsBlockedRequestPromptly() throws Exception {
        var entered = new CountDownLatch(1);
        var client = new OkHttpClient.Builder().addInterceptor(chain -> {
            entered.countDown();
            while (!chain.call().isCanceled()) {
                try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            throw new IOException("cancelled fixture");
        }).build();
        var source = new AliAdaptiveRangeDataSource(client, Map.of(), 1000000);
        source.open(new DataSpec(Uri.parse("https://fixture.example/video")));
        var worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> read = worker.submit(() -> {
                try { source.read(new byte[1], 0, 1); fail("Expected cancellation"); }
                catch (IOException expected) { }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS)); source.close();
            read.get(2, TimeUnit.SECONDS);
        } finally { source.close(); worker.shutdownNow(); }
    }

    @Test public void wrongContentRangeIsRejectedAndRetryAfterSupportsDates() throws Exception {
        var client = new OkHttpClient.Builder().addInterceptor(chain -> new Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("fixture")
                .header("Content-Range", "bytes 1-3/99").body(ResponseBody.create(null, new byte[3])).build()).build();
        var source = new AliAdaptiveRangeDataSource(client, Map.of(), 99);
        try {
            source.open(new DataSpec(Uri.parse("https://fixture.example/video")));
            source.read(new byte[1], 0, 1); fail("Invalid range accepted");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("inconsistent")); }
        finally { source.close(); }
        assertEquals(12000, AliAdaptiveRangeDataSource.retryAfterMillis(Map.of("retry-after", List.of("12")), 0));
        long date = java.time.Instant.parse("2026-09-11T00:00:00Z").toEpochMilli();
        assertEquals(15000, AliAdaptiveRangeDataSource.retryAfterMillis(
                Map.of("Retry-After", List.of("Fri, 11 Sep 2026 00:00:15 GMT")), date));
    }
}
