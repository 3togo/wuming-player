package com.wuming.android.tv.player.exo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import android.net.Uri;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.wuming.android.tv.media.OpenDriveUriResolver;
import com.wuming.tv.core.media.PlaybackTarget;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public class RefreshingDataSourceInstrumentedTest {

    @Test
    public void reopeningClosesThePreviousSourceAndPreservesRangeRequests() throws Exception {
        List<RecordingSource> sources = new ArrayList<>();
        DataSource source = factory(() -> {
            RecordingSource next = new RecordingSource();
            sources.add(next);
            return next;
        }, uri -> { throw new AssertionError("Unexpired URL must not refresh"); }).createDataSource();
        try {
            DataSpec range = spec().buildUpon().setPosition(4096L).setLength(1024L).build();
            source.open(range);
            source.open(range);
            assertEquals(1, sources.get(0).closes);
            assertEquals(4096L, sources.get(1).openedSpec.position);
            assertEquals(1024L, sources.get(1).openedSpec.length);
            assertEquals("https://example.test/initial", source.getUri().toString());
        } finally {
            source.close();
        }
        source.close();
        assertEquals(1, sources.get(1).closes);
        assertNull(source.getUri());
    }

    @Test
    public void failedOpenClosesTheSourceEvenWhenItThrowsARuntimeException() throws Exception {
        RecordingSource broken = new RecordingSource();
        RuntimeException failure = new IllegalStateException("open failed");
        broken.openFailure = failure;
        broken.closeFailure = new IOException("close failed");
        DataSource source = factory(() -> broken, uri -> null).createDataSource();
        assertSame(failure, assertThrows(RuntimeException.class, () -> source.open(spec())));
        assertSame(broken.closeFailure, failure.getSuppressed()[0]);
        assertEquals(1, broken.closes);
        assertNull(source.getUri());
        source.close();
        assertEquals(1, broken.closes);
    }

    @Test
    public void failedListenerSetupAlsoClosesTheNewSource() {
        RecordingSource broken = new RecordingSource();
        RuntimeException failure = new IllegalStateException("listener setup failed");
        broken.listenerFailure = failure;
        DataSource source = factory(() -> broken, uri -> null).createDataSource();
        source.addTransferListener(new NoOpListener());
        assertSame(failure, assertThrows(RuntimeException.class, () -> source.open(spec())));
        assertEquals(1, broken.closes);
        assertNull(source.getUri());
    }

    @Test
    public void duplicateListenersAreNotAddedToEachUnderlyingRequest() throws Exception {
        RecordingSource upstream = new RecordingSource();
        DataSource source = factory(() -> upstream, uri -> null).createDataSource();
        TransferListener listener = new NoOpListener();
        source.addTransferListener(listener);
        source.addTransferListener(listener);
        try {
            source.open(spec());
            source.addTransferListener(listener);
            assertEquals(1, upstream.listenerAdds);
        } finally {
            source.close();
        }
    }

    @Test
    public void expiredResponsesHaveAtMostOneRefreshAndOneFailover() throws Exception {
        AtomicInteger refreshes = new AtomicInteger();
        AtomicInteger failovers = new AtomicInteger();
        List<RecordingSource> attempts = new ArrayList<>();
        OpenDriveUriResolver resolver = new OpenDriveUriResolver() {
            @Override public PlaybackTarget resolve(String uri) {
                refreshes.incrementAndGet();
                return target("refreshed");
            }
            @Override public PlaybackTarget failover(String uri) {
                failovers.incrementAndGet();
                return target("backup");
            }
        };
        DataSource source = factory(() -> {
            RecordingSource upstream = new RecordingSource();
            upstream.openFailure = new HttpDataSource.InvalidResponseCodeException(
                    403, "Expired", null, Map.of(), spec(), new byte[0]);
            attempts.add(upstream);
            return upstream;
        }, resolver).createDataSource();
        assertThrows(IOException.class, () -> source.open(spec()));
        source.close();
        assertEquals(3, attempts.size());
        assertEquals(1, refreshes.get());
        assertEquals(1, failovers.get());
        for (RecordingSource attempt : attempts) assertEquals(1, attempt.closes);
    }

    @Test
    public void ordinaryNetworkErrorsDoNotTriggerProviderRetries() {
        RecordingSource broken = new RecordingSource();
        IOException failure = new IOException("connection lost");
        broken.openFailure = failure;
        DataSource source = factory(() -> broken,
                uri -> { throw new AssertionError("Unexpected refresh"); }).createDataSource();
        assertSame(failure, assertThrows(IOException.class, () -> source.open(spec())));
        assertEquals(1, broken.closes);
    }

    @Test
    public void targetHeadersReplaceOldValuesRegardlessOfTheirCasing() throws Exception {
        RecordingSource upstream = new RecordingSource();
        DataSource source = new RefreshingDataSource.Factory(() -> upstream,
                "https://example.test/initial", Map.of("Cookie", "fresh", "User-Agent", "agent"),
                Long.MAX_VALUE, "opendrive://local/test", uri -> null).createDataSource();
        try {
            source.open(spec().buildUpon().setHttpRequestHeaders(Map.of(
                    "cookie", "stale", "user-agent", "stale-agent", "X-Test", "keep")).build());
            Map<String, String> headers = upstream.openedSpec.httpRequestHeaders;
            assertEquals(3, headers.size());
            assertEquals("fresh", headers.get("cookie"));
            assertEquals("agent", headers.get("user-agent"));
            assertEquals("keep", headers.get("X-Test"));
        } finally {
            source.close();
        }
    }

    private static RefreshingDataSource.Factory factory(DataSource.Factory upstream,
                                                         OpenDriveUriResolver resolver) {
        return new RefreshingDataSource.Factory(upstream, "https://example.test/initial",
                Map.of(), Long.MAX_VALUE, "opendrive://local/test", resolver);
    }

    private static PlaybackTarget target(String name) {
        return new PlaybackTarget("https://example.test/" + name, Map.of(), Long.MAX_VALUE);
    }

    private static DataSpec spec() {
        return new DataSpec.Builder().setUri("https://example.test/request").build();
    }

    private static final class RecordingSource implements DataSource {
        Exception openFailure;
        IOException closeFailure;
        RuntimeException listenerFailure;
        DataSpec openedSpec;
        int closes;
        int listenerAdds;

        @Override public long open(DataSpec spec) throws IOException {
            openedSpec = spec;
            if (openFailure instanceof IOException error) throw error;
            if (openFailure instanceof RuntimeException error) throw error;
            return 1024L;
        }
        @Override public void addTransferListener(TransferListener listener) {
            if (listenerFailure != null) throw listenerFailure;
            listenerAdds++;
        }
        @Override public int read(byte[] buffer, int offset, int length) { return -1; }
        @Override public Uri getUri() { return openedSpec == null ? null : openedSpec.uri; }
        @Override public void close() throws IOException {
            closes++;
            if (closeFailure != null) throw closeFailure;
        }
    }

    private static final class NoOpListener implements TransferListener {
        @Override public void onTransferInitializing(DataSource source, DataSpec spec, boolean network) {}
        @Override public void onTransferStart(DataSource source, DataSpec spec, boolean network) {}
        @Override public void onBytesTransferred(DataSource source, DataSpec spec, boolean network, int bytes) {}
        @Override public void onTransferEnd(DataSource source, DataSpec spec, boolean network) {}
    }
}
