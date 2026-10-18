package com.wuming.android.tv.player.exo;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wuming.android.tv.App;
import com.wuming.android.tv.cloud.opendrive.OpenDriveDeviceFixtures;
import com.wuming.android.tv.cloud.opendrive.OpenDriveEngine;
import com.wuming.tv.core.cloud.OpenDrivePlayback;
import com.wuming.tv.core.playback.OpenDrivePlaybackHints;
import com.wuming.tv.core.media.MediaSourceDescriptor;
import com.wuming.tv.core.media.OpenDriveUri;
import com.wuming.tv.core.media.catalog.MediaManifest;
import com.wuming.android.tv.media.catalog.RoomMediaCatalog;
import com.github.catvod.net.OkHttp;

import org.junit.Test;
import org.junit.Assume;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.LinkedHashMap;

@RunWith(AndroidJUnit4.class)
public class ParallelRangeDataSourceInstrumentedTest {

    @Test
    public void optInPlaybackProtocolComparison() throws Exception {
        String url = InstrumentationRegistry.getArguments().getString("startup_media_url", "");
        Assume.assumeTrue("Live protocol comparison is opt-in", !url.isEmpty());
        String agent = InstrumentationRegistry.getArguments().getString("startup_user_agent", "");
        for (boolean http1 : new boolean[]{false, true}) {
            java.util.concurrent.atomic.AtomicReference<String> protocol =
                    new java.util.concurrent.atomic.AtomicReference<>("unknown");
            okhttp3.OkHttpClient.Builder builder = OkHttp.player().newBuilder()
                    .connectionPool(new okhttp3.ConnectionPool())
                    .callTimeout(0, TimeUnit.SECONDS)
                    .addNetworkInterceptor(chain -> {
                        okhttp3.Response response = chain.proceed(chain.request());
                        protocol.set(response.protocol().toString());
                        return response;
                    });
            if (http1) builder.protocols(List.of(okhttp3.Protocol.HTTP_1_1));
            okhttp3.OkHttpClient client = builder.build();
            long started = android.os.SystemClock.elapsedRealtime();
            long total = 0;
            DataSource source = new ParallelRangeDataSource.Factory(
                    new OkHttpDataSource.Factory(client).setDefaultRequestProperties(
                            Map.of("User-Agent", agent)), 327349016L,
                    OpenDrivePlaybackHints.PROFILE_GENERIC_HTTP).createDataSource();
            try {
                source.open(new DataSpec(Uri.parse(url)));
                byte[] buffer = new byte[8192];
                while (total < 1024 * 1024 && android.os.SystemClock.elapsedRealtime() - started < 30000) {
                    int read = source.read(buffer, 0, buffer.length);
                    if (read == C.RESULT_END_OF_INPUT) break;
                    total += read;
                }
            } finally {
                source.close();
                client.connectionPool().evictAll();
            }
            android.util.Log.i("StartupProtocol", "parallel=true,http1=" + http1 + ",protocol=" + protocol.get()
                    + ",bytes=" + total + ",elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - started));
            assertEquals(true, total > 0);
        }
    }

    @Test
    public void optInGenericStartupComparison() throws Exception {
        String url = InstrumentationRegistry.getArguments().getString("startup_media_url", "");
        Assume.assumeTrue("Live startup comparison is opt-in", !url.isEmpty());
        long contentLength = Long.parseLong(InstrumentationRegistry.getArguments()
                .getString("startup_content_length", "0"));
        Assume.assumeTrue(contentLength >= 10L * 1024 * 1024);
        // Match the production range size/concurrency; vary only the startup policy.
        for (String profile : List.of("startup-baseline", OpenDrivePlaybackHints.PROFILE_GENERIC_HTTP)) {
            OkHttpDataSource.Factory http = new OkHttpDataSource.Factory(
                    new okhttp3.OkHttpClient.Builder()
                            .connectTimeout(10, TimeUnit.SECONDS)
                            .readTimeout(10, TimeUnit.SECONDS).build());
            DataSource source = new ParallelRangeDataSource(http, contentLength, profile,
                    ParallelRangeDataSource.RangePolicy.buffered(10L * 1024 * 1024, 5));
            long started = android.os.SystemClock.elapsedRealtime();
            long total = 0;
            byte[] buffer = new byte[32 * 1024];
            try {
                source.open(new DataSpec(Uri.parse(url)));
                while (total < 2500000L) {
                    if (android.os.SystemClock.elapsedRealtime() - started > 120000L) {
                        throw new IOException("Startup comparison exceeded time budget");
                    }
                    int read = source.read(buffer, 0, (int) Math.min(buffer.length, 2500000L - total));
                    if (read == C.RESULT_END_OF_INPUT) break;
                    total += read;
                }
                assertEquals(2500000L, total);
                android.util.Log.i("StartupComparison", "profile=" + profile + ",bytes=" + total
                        + ",elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - started));
            } finally {
                source.close();
            }
        }
    }

    @Test
    public void liveAliPanProfileReadsOneContinuousParallelWindow() throws Exception {
        boolean enabled = Boolean.parseBoolean(InstrumentationRegistry.getArguments()
                .getString("run_live_alipan_parallel", "false"));
        Assume.assumeTrue("Live Aliyun parallel test is opt-in", enabled);
        String mediaUri = OpenDriveDeviceFixtures.refreshMediaUri("alipan");
        Assume.assumeTrue(OpenDriveUri.isValid(mediaUri));
        MediaManifest manifest = new RoomMediaCatalog().find(OpenDriveUri.parse(mediaUri))
                .orElseThrow();
        MediaSourceDescriptor descriptor = manifest.sources().stream()
                .filter(item -> "org.opendrive.provider".equals(item.adapterId())
                        && "alipan".equals(item.locator().get("provider")))
                .findFirst().orElseThrow();
        OpenDrivePlayback playback = new OpenDriveEngine().resolve(
                "alipan", descriptor.locator().get("handle"));
        LinkedHashMap<String, String> headers = new LinkedHashMap<>(playback.headers());
        long contentLength = Long.parseLong(headers.remove(OpenDrivePlaybackHints.CONTENT_LENGTH));
        String profile = headers.remove(OpenDrivePlaybackHints.PARALLEL_RANGE_PROFILE);
        assertEquals(OpenDrivePlaybackHints.PROFILE_ALIPAN, profile);

        OkHttpDataSource.Factory http = new OkHttpDataSource.Factory(OkHttp.player());
        http.setDefaultRequestProperties(headers);
        DataSource source = new ParallelRangeDataSource.Factory(
                new DefaultDataSource.Factory(App.get(), http), contentLength, profile)
                .createDataSource();
        long bytesToRead = ParallelRangeDataSource.ALIPAN_WINDOW_BYTES;
        source.open(new DataSpec.Builder().setUri(playback.url()).setPosition(0L)
                .setLength(bytesToRead).build());
        byte[] buffer = new byte[64 * 1024];
        long total = 0L;
        try {
            while (total < bytesToRead) {
                int read = source.read(buffer, 0,
                        (int) Math.min(buffer.length, bytesToRead - total));
                if (read == C.RESULT_END_OF_INPUT) break;
                total += read;
            }
        } finally {
            source.close();
        }
        assertEquals(bytesToRead, total);
    }

    @Test
    public void boundedRangesRemainOneContinuousResource() throws Exception {
        byte[] content = "abcdefghijklmnopqrstuvwxy".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        RecordingFactory upstream = new RecordingFactory(content, 2);
        ParallelRangeDataSource source = new ParallelRangeDataSource(
                upstream, 10L, 2, content.length, "test");

        long opened = source.open(new DataSpec(Uri.parse(
                "https://dl-uf-zb.pds.uc.cn/signed-media")));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[7];
        int read;
        while ((read = source.read(buffer, 0, buffer.length)) != C.RESULT_END_OF_INPUT) {
            output.write(buffer, 0, read);
        }
        source.close();

        assertEquals(content.length, opened);
        assertArrayEquals(content, output.toByteArray());
        List<String> requests = new ArrayList<>(upstream.requests);
        Collections.sort(requests);
        assertEquals(List.of("000:5", "005:5", "010:5", "015:5", "020:3", "023:2"),
                requests);
    }

    @Test
    public void bufferedProfileActivelyReadsAllPartsConcurrently() throws Exception {
        byte[] content = "abcdefghijkl".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ConcurrentReadingFactory upstream = new ConcurrentReadingFactory(content, 3);
        ParallelRangeDataSource source = new ParallelRangeDataSource(
                upstream, content.length, "buffered-test",
                ParallelRangeDataSource.RangePolicy.buffered(content.length, 3));

        source.open(new DataSpec(Uri.parse("https://example.com/media")));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[5];
        try {
            int read;
            while ((read = source.read(buffer, 0, buffer.length)) != C.RESULT_END_OF_INPUT) {
                output.write(buffer, 0, read);
            }
        } finally {
            source.close();
        }

        assertArrayEquals(content, output.toByteArray());
        assertEquals(0L, upstream.firstReads.getCount());
    }

    @Test
    public void closingBufferedProfileDoesNotRaceTheActiveReader() throws Exception {
        ConcurrentCloseFactory upstream = new ConcurrentCloseFactory();
        ParallelRangeDataSource source = new ParallelRangeDataSource(
                upstream, 3L, "buffered-close-test",
                ParallelRangeDataSource.RangePolicy.buffered(3L, 1));

        source.open(new DataSpec(Uri.parse("https://example.com/media")));
        assertEquals(true, upstream.readStarted.await(2L, TimeUnit.SECONDS));
        source.close();

        assertEquals(true, upstream.sourceClosed.await(2L, TimeUnit.SECONDS));
        assertEquals(false, upstream.closedDuringRead.get());
    }

    @Test
    public void slowTricklingRangeIsReplacedWithoutDuplicatingBytes() throws Exception {
        verifyTricklingRange(false);
    }

    @Test
    public void failedSlowRangeReplacementKeepsOriginalReadable() throws Exception {
        verifyTricklingRange(true);
    }

    private void verifyTricklingRange(boolean failReplacement) throws Exception {
        byte[] content = new byte[12 * 1024 * 1024];
        for (int i = 0; i < content.length; i++) content[i] = (byte) (i * 31 + i / 251);
        RecordingFactory recording = new RecordingFactory(content);
        AtomicInteger replacements = new AtomicInteger();
        AtomicInteger firstBytes = new AtomicInteger();
        DataSource.Factory upstream = () -> new DataSource() {
            final DataSource delegate = recording.createDataSource();
            boolean first;
            long started;

            @Override public void addTransferListener(TransferListener listener) {
                delegate.addTransferListener(listener);
            }
            @Override public long open(DataSpec spec) throws IOException {
                first = spec.position == 0;
                started = System.nanoTime();
                if (spec.position > 0 && spec.position < 256L * 1024) {
                    replacements.incrementAndGet();
                    try { Thread.sleep(200); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new java.io.InterruptedIOException();
                    }
                    if (failReplacement) throw new IOException("replacement unavailable");
                }
                return delegate.open(spec);
            }
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                if (first && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(4)) {
                    try { Thread.sleep(40); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new java.io.InterruptedIOException();
                    }
                    length = Math.min(length, 1024);
                }
                int count = delegate.read(buffer, offset, length);
                if (first && count > 0) firstBytes.addAndGet(count);
                return count;
            }
            @Override public Uri getUri() { return delegate.getUri(); }
            @Override public Map<String, List<String>> getResponseHeaders() {
                return delegate.getResponseHeaders();
            }
            @Override public void close() throws IOException { delegate.close(); }
        };
        DataSource source = new ParallelRangeDataSource.Factory(upstream, content.length,
                OpenDrivePlaybackHints.PROFILE_GENERIC_HTTP).createDataSource();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try {
            source.open(new DataSpec(Uri.parse("https://example.com/trickle")));
            while (output.size() < 512 * 1024) {
                int read = source.read(buffer, 0, Math.min(buffer.length, 512 * 1024 - output.size()));
                if (read == C.RESULT_END_OF_INPUT) break;
                output.write(buffer, 0, read);
            }
        } finally { source.close(); }
        assertArrayEquals(java.util.Arrays.copyOf(content, 512 * 1024), output.toByteArray());
        assertEquals(true, replacements.get() > 0);
        if (!failReplacement) assertEquals(true, firstBytes.get() < 256 * 1024);
    }

    @Test
    public void genericStartupUsesShortRangesThenResumesBulkWithoutGaps() throws Exception {
        byte[] content = new byte[12 * 1024 * 1024];
        for (int i = 0; i < content.length; i++) content[i] = (byte) (i * 31 + i / 251);
        RecordingFactory upstream = new RecordingFactory(content);
        ParallelRangeDataSource source = new ParallelRangeDataSource(
                upstream, content.length, OpenDrivePlaybackHints.PROFILE_GENERIC_HTTP,
                ParallelRangeDataSource.RangePolicy.buffered(10L * 1024 * 1024, 5));
        // A non-zero start also exercises the startup policy on a seek/reopen.
        int start = 123;
        source.open(new DataSpec.Builder().setUri("https://example.com/startup")
                .setPosition(start).build());
        byte[] buffer = new byte[32 * 1024];
        int position = start;
        try {
            int read;
            while ((read = source.read(buffer, 0, buffer.length)) != C.RESULT_END_OF_INPUT) {
                assertArrayEquals(java.util.Arrays.copyOfRange(content, position, position + read),
                        java.util.Arrays.copyOf(buffer, read));
                position += read;
            }
        } finally {
            source.close();
        }
        assertEquals(content.length, position);
        boolean bulkSeen = false;
        for (String request : upstream.requests) {
            String[] values = request.split(":");
            long offset = Long.parseLong(values[0]);
            long length = Long.parseLong(values[1]);
            if (offset < start + 4L * 1024 * 1024) {
                assertEquals(true, length <= 256L * 1024);
                assertEquals(true, offset + length <= start + 4L * 1024 * 1024);
            } else if (length > 256L * 1024) {
                bulkSeen = true;
            }
        }
        assertEquals(true, bulkSeen);
    }

    @Test
    public void bufferedProfileRollsAheadWithoutLosingFileOrder() throws Exception {
        byte[] content = "abcdefghijklmnopqrstuvwx".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        RecordingFactory upstream = new RecordingFactory(content);
        ParallelRangeDataSource source = new ParallelRangeDataSource(
                upstream, content.length, "rolling-test",
                ParallelRangeDataSource.RangePolicy.buffered(4L, 4));

        source.open(new DataSpec(Uri.parse("https://example.com/media")));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[3];
        try {
            int read;
            while ((read = source.read(buffer, 0, buffer.length)) != C.RESULT_END_OF_INPUT) {
                output.write(buffer, 0, read);
            }
        } finally {
            source.close();
        }

        assertArrayEquals(content, output.toByteArray());
        List<String> requests = new ArrayList<>(upstream.requests);
        Collections.sort(requests);
        assertEquals(24, requests.size());
        for (int index = 0; index < requests.size(); index++) {
            assertEquals(String.format(java.util.Locale.ROOT, "%03d:1", index),
                    requests.get(index));
        }
    }

    @Test
    public void declaredSizeClampsTailRangeAndIsNotSentUpstream() throws Exception {
        byte[] content = "abcdefghijklmnopqrstuvwxy".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        RecordingFactory upstream = new RecordingFactory(content);
        ParallelRangeDataSource source = new ParallelRangeDataSource(
                upstream, 10L, 1, 0L, "test");
        DataSpec spec = new DataSpec.Builder()
                .setUri("https://dl-uf-zb.pds.uc.cn/signed-media")
                .setPosition(22L)
                .setHttpRequestHeaders(Map.of(
                        com.wuming.tv.core.playback.OpenDrivePlaybackHints.CONTENT_LENGTH,
                        "25"))
                .build();

        assertEquals(3L, source.open(spec));
        assertEquals(List.of("022:3"), upstream.requests);
        assertFalse(upstream.headers.get(0).containsKey(
                com.wuming.tv.core.playback.OpenDrivePlaybackHints.CONTENT_LENGTH));
        source.close();
    }

    @Test
    public void tailSeekAboveFourGiBKeepsExactLongRangePositions() throws Exception {
        long start = 4L * 1024L * 1024L * 1024L + 123L;
        long total = start + 32L;
        List<String> requests = Collections.synchronizedList(new ArrayList<>());
        DataSource.Factory upstream = () -> new DataSource() {
            private DataSpec spec;

            @Override
            public void addTransferListener(TransferListener transferListener) {
            }

            @Override
            public long open(DataSpec dataSpec) {
                spec = dataSpec;
                requests.add(dataSpec.position + ":" + dataSpec.length);
                return dataSpec.length;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                return C.RESULT_END_OF_INPUT;
            }

            @Nullable
            @Override
            public Uri getUri() {
                return spec == null ? null : spec.uri;
            }

            @Override
            public Map<String, List<String>> getResponseHeaders() {
                return Map.of();
            }

            @Override
            public void close() {
                spec = null;
            }
        };
        ParallelRangeDataSource source = new ParallelRangeDataSource(
                upstream, 16L, 2, total, "test");

        assertEquals(32L, source.open(new DataSpec.Builder()
                .setUri("https://example.com/large.mp4")
                .setPosition(start)
                .build()));
        List<String> sorted = new ArrayList<>(requests);
        Collections.sort(sorted);
        assertEquals(List.of(start + ":8", (start + 8L) + ":8"), sorted);
        source.close();
    }

    @Test
    public void contentRangeIntegrityRequiresExactRequestedBounds() {
        assertEquals(true, ParallelRangeDataSource.matchesContentRange(
                Map.of("content-range", List.of("bytes 4096-8191/10000")),
                4096L, 4096L));
        assertEquals(false, ParallelRangeDataSource.matchesContentRange(
                Map.of("Content-Range", List.of("bytes 4096-8192/10000")),
                4096L, 4096L));
        assertEquals(false, ParallelRangeDataSource.matchesContentRange(
                Map.of("Content-Range", List.of("bytes 9223372036854775800-9223372036854775807/*")),
                9_223_372_036_854_775_800L, 16L));
    }

    @Test
    public void adaptiveWindowRequiresMaterialGain() {
        assertEquals(true, AdaptiveWindowController.significantGain(0L, 500_000L));
        assertEquals(true, AdaptiveWindowController.significantGain(500_000L, 550_000L));
        assertEquals(false, AdaptiveWindowController.significantGain(500_000L, 549_999L));
    }

    @Test
    public void failedWindowClosesEverySourceOpenedBySiblingTasks() throws Exception {
        FailingWindowFactory upstream = new FailingWindowFactory();
        ParallelRangeDataSource source = new ParallelRangeDataSource(
                upstream, 10L, 2, 10L, "test");

        try {
            source.open(new DataSpec(Uri.parse("https://example.com/media")));
            fail("Expected the first range to fail");
        } catch (IOException expected) {
            assertEquals("first range failed", expected.getMessage());
        }

        // A buffered part is closed by the worker that owns it: Part.close() hands the close
        // off to the in-flight buffering task instead of closing from another thread, because
        // OkHttp/Okio reports "Unbalanced enter/exit" when a response body is closed while
        // still being read. Wait for that hand-off instead of assuming it is synchronous.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (upstream.closed.get() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(2, upstream.closed.get());
        source.close();
        assertEquals(2, upstream.closed.get());
    }

    @Test
    public void validatedProfilesFallBackWhenRangeMetadataIsUnsafe() throws Exception {
        byte[] content = "safe".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (String profile : List.of(OpenDrivePlaybackHints.PROFILE_QUARK_ORIGINAL,
                OpenDrivePlaybackHints.PROFILE_UC_PDS,
                OpenDrivePlaybackHints.PROFILE_ALIPAN)) {
            MismatchedRangeFactory upstream = new MismatchedRangeFactory(content);
            DataSource source = new ParallelRangeDataSource.Factory(
                    upstream, content.length, profile).createDataSource();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[content.length];
            try {
                source.open(new DataSpec(Uri.parse("https://example.com/media")));
                int read;
                while ((read = source.read(buffer, 0, buffer.length))
                        != C.RESULT_END_OF_INPUT) {
                    output.write(buffer, 0, read);
                }
            } finally {
                source.close();
            }
            assertArrayEquals(content, output.toByteArray());
            assertEquals(true, upstream.opens.get() > 1);
        }
    }

    private static final class FailingWindowFactory implements DataSource.Factory {
        private final CountDownLatch siblingOpened = new CountDownLatch(1);
        private final AtomicInteger closed = new AtomicInteger();

        @Override
        public DataSource createDataSource() {
            return new DataSource() {
                @Override
                public void addTransferListener(TransferListener transferListener) {
                }

                @Override
                public long open(DataSpec dataSpec) throws IOException {
                    if (dataSpec.position == 0L) {
                        try {
                            if (!siblingOpened.await(2L, TimeUnit.SECONDS)) {
                                throw new IOException("sibling range did not open");
                            }
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IOException("interrupted", error);
                        }
                        throw new IOException("first range failed");
                    }
                    siblingOpened.countDown();
                    return dataSpec.length;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return C.RESULT_END_OF_INPUT;
                }

                @Nullable
                @Override
                public Uri getUri() {
                    return null;
                }

                @Override
                public Map<String, List<String>> getResponseHeaders() {
                    return Map.of();
                }

                @Override
                public void close() {
                    closed.incrementAndGet();
                }
            };
        }
    }

    private static final class ConcurrentReadingFactory implements DataSource.Factory {
        private final byte[] content;
        private final CountDownLatch firstReads;

        private ConcurrentReadingFactory(byte[] content, int expectedReaders) {
            this.content = content;
            this.firstReads = new CountDownLatch(expectedReaders);
        }

        @Override
        public DataSource createDataSource() {
            return new DataSource() {
                private DataSpec spec;
                private int cursor;
                private int end;
                private boolean firstRead = true;

                @Override
                public void addTransferListener(TransferListener transferListener) {
                }

                @Override
                public long open(DataSpec dataSpec) {
                    spec = dataSpec;
                    cursor = (int) dataSpec.position;
                    end = (int) Math.min(content.length,
                            dataSpec.position + dataSpec.length);
                    return end - cursor;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    if (firstRead) {
                        firstRead = false;
                        firstReads.countDown();
                        try {
                            if (!firstReads.await(2L, TimeUnit.SECONDS)) {
                                throw new IOException("Range bodies were not read concurrently");
                            }
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted while checking concurrent reads", error);
                        }
                    }
                    if (cursor >= end) return C.RESULT_END_OF_INPUT;
                    int count = Math.min(length, end - cursor);
                    System.arraycopy(content, cursor, buffer, offset, count);
                    cursor += count;
                    return count;
                }

                @Nullable
                @Override
                public Uri getUri() {
                    return spec == null ? null : spec.uri;
                }

                @Override
                public Map<String, List<String>> getResponseHeaders() {
                    if (spec == null) return Map.of();
                    long rangeEnd = spec.position + spec.length - 1L;
                    return Map.of("Content-Range", List.of("bytes " + spec.position + "-"
                            + rangeEnd + "/" + content.length));
                }

                @Override
                public void close() {
                    spec = null;
                }
            };
        }
    }

    private static final class ConcurrentCloseFactory implements DataSource.Factory {
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch sourceClosed = new CountDownLatch(1);
        private final AtomicBoolean reading = new AtomicBoolean();
        private final AtomicBoolean closedDuringRead = new AtomicBoolean();

        @Override
        public DataSource createDataSource() {
            return new DataSource() {
                private DataSpec spec;

                @Override
                public void addTransferListener(TransferListener transferListener) {
                }

                @Override
                public long open(DataSpec dataSpec) {
                    spec = dataSpec;
                    return dataSpec.length;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    reading.set(true);
                    readStarted.countDown();
                    try {
                        new CountDownLatch(1).await();
                        return C.RESULT_END_OF_INPUT;
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IOException("cancelled", error);
                    } finally {
                        reading.set(false);
                    }
                }

                @Nullable
                @Override
                public Uri getUri() {
                    return spec == null ? null : spec.uri;
                }

                @Override
                public Map<String, List<String>> getResponseHeaders() {
                    return Map.of("Content-Range", List.of("bytes 0-2/3"));
                }

                @Override
                public void close() {
                    if (reading.get()) closedDuringRead.set(true);
                    spec = null;
                    sourceClosed.countDown();
                }
            };
        }
    }

    private static final class MismatchedRangeFactory implements DataSource.Factory {
        private final byte[] content;
        private final AtomicInteger opens = new AtomicInteger();

        private MismatchedRangeFactory(byte[] content) {
            this.content = content;
        }

        @Override
        public DataSource createDataSource() {
            return new DataSource() {
                private DataSpec spec;
                private int cursor;
                private int end;

                @Override
                public void addTransferListener(TransferListener transferListener) {
                }

                @Override
                public long open(DataSpec dataSpec) {
                    spec = dataSpec;
                    cursor = (int) dataSpec.position;
                    end = (int) Math.min(content.length,
                            dataSpec.position + dataSpec.length);
                    opens.incrementAndGet();
                    return end - cursor;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    if (cursor >= end) return C.RESULT_END_OF_INPUT;
                    int count = Math.min(length, end - cursor);
                    System.arraycopy(content, cursor, buffer, offset, count);
                    cursor += count;
                    return count;
                }

                @Nullable
                @Override
                public Uri getUri() {
                    return spec == null ? null : spec.uri;
                }

                @Override
                public Map<String, List<String>> getResponseHeaders() {
                    return Map.of();
                }

                @Override
                public void close() {
                    spec = null;
                }
            };
        }
    }

    private static final class RecordingFactory implements DataSource.Factory {
        private final byte[] content;
        private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        private final List<Map<String, String>> headers =
                Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch firstWindowOpens;

        private RecordingFactory(byte[] content) {
            this(content, 0);
        }

        private RecordingFactory(byte[] content, int expectedConcurrentOpens) {
            this.content = content;
            this.firstWindowOpens = expectedConcurrentOpens > 0
                    ? new CountDownLatch(expectedConcurrentOpens) : null;
        }

        @Override
        public DataSource createDataSource() {
            return new DataSource() {
                private DataSpec spec;
                private long cursor;
                private long end;

                @Override
                public void addTransferListener(TransferListener transferListener) {
                }

                @Override
                public long open(DataSpec dataSpec) throws IOException {
                    spec = dataSpec;
                    cursor = dataSpec.position;
                    end = Math.min(content.length, dataSpec.position + dataSpec.length);
                    requests.add(String.format(java.util.Locale.ROOT, "%03d:%d",
                            dataSpec.position, dataSpec.length));
                    headers.add(dataSpec.httpRequestHeaders);
                    if (firstWindowOpens != null && firstWindowOpens.getCount() > 0L) {
                        firstWindowOpens.countDown();
                        try {
                            if (!firstWindowOpens.await(2L, TimeUnit.SECONDS)) {
                                throw new IOException("Ranges were not opened concurrently");
                            }
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted while checking concurrency", error);
                        }
                    }
                    return end - cursor;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    if (cursor >= end) return C.RESULT_END_OF_INPUT;
                    int count = (int) Math.min(length, end - cursor);
                    System.arraycopy(content, (int) cursor, buffer, offset, count);
                    cursor += count;
                    return count;
                }

                @Nullable
                @Override
                public Uri getUri() {
                    return spec == null ? null : spec.uri;
                }

                @Override
                public Map<String, List<String>> getResponseHeaders() {
                    if (spec == null) return Map.of();
                    long rangeEnd = Math.min(content.length - 1L,
                            spec.position + spec.length - 1L);
                    return Map.of("Content-Range", List.of("bytes " + spec.position + "-"
                            + rangeEnd + "/" + content.length));
                }

                @Override
                public void close() throws IOException {
                    spec = null;
                }
            };
        }
    }
}
