package com.wuming.android.tv.player.danmaku;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;

import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.Okio;

public class LoaderTest {

    @Test
    public void exactLimitAndPartialBuffersPreserveEveryByte() throws Exception {
        for (int size : new int[]{0, 31, 32, 8191, 8192, 8193, Loader.MAX_BYTES}) {
            byte[] bytes = new byte[size];
            for (int i = 0; i < size; i++) bytes[i] = (byte) (i * 17);
            try (Loader loader = new Loader()) {
                loader.load(new ByteArrayInputStream(bytes));
                org.junit.Assert.assertArrayEquals(bytes, loader.getDataSource().data().readAllBytes());
            }
        }
    }

    @Test
    public void oversizedUnknownLengthInputClosesAndPreservesPreviousSource() throws Exception {
        try (Loader loader = new Loader()) {
            loader.load(new ByteArrayInputStream(new byte[]{42}));
            var previous = loader.getDataSource();
            boolean[] closed = {false};
            InputStream oversized = new ByteArrayInputStream(new byte[Loader.MAX_BYTES + 1]) {
                @Override public void close() { closed[0] = true; }
            };
            assertThrows(IOException.class, () -> loader.load(oversized));
            assertTrue(closed[0]);
            org.junit.Assert.assertSame(previous, loader.getDataSource());
            assertEquals(42, previous.data().read());
        }
    }

    @Test
    public void cancellationDuringReadClosesInputAndPublishesNothing() {
        Loader loader = new Loader();
        boolean[] closed = {false};
        InputStream input = new ByteArrayInputStream(new byte[32]) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                loader.close();
                return super.read(bytes, offset, length);
            }
            @Override public void close() { closed[0] = true; }
        };
        assertThrows(InterruptedIOException.class, () -> loader.load(input));
        assertTrue(closed[0]);
        assertNull(loader.getDataSource());
    }

    @Test
    public void responseClosesBeforeTheDeferredParserReadsItsBufferedData() throws Exception {
        TrackingStream stream = new TrackingStream();
        Loader loader = new Loader();
        loader.loadResponse(response(200, stream));
        assertTrue(stream.closed);
        assertEquals('d', loader.getDataSource().data().read());
        loader.getDataSource().release();
        assertTrue(stream.closed);
    }

    @Test
    public void rejectedResponseClosesItsBodyWithoutHandingItToTheParser() {
        TrackingStream stream = new TrackingStream();
        Loader loader = new Loader();
        assertThrows(IOException.class, () -> loader.loadResponse(response(403, stream)));
        assertTrue(stream.closed);
        assertNull(loader.getDataSource());
    }

    @Test
    public void replacingALoadedStreamReleasesTheOldSource() throws Exception {
        TrackingStream first = new TrackingStream();
        TrackingStream second = new TrackingStream();
        Loader loader = new Loader();
        loader.load(first);
        var previous = loader.getDataSource();
        loader.load(second);
        assertNull(previous.data());
        assertTrue(first.closed);
        assertTrue(second.closed);
        loader.getDataSource().release();
        assertTrue(second.closed);
    }

    private static Response response(int code, TrackingStream stream) {
        BufferedSource source = Okio.buffer(Okio.source(stream));
        ResponseBody body = new ResponseBody() {
            @Override public MediaType contentType() { return null; }
            @Override public long contentLength() { return 7L; }
            @Override public BufferedSource source() { return source; }
        };
        return new Response.Builder().request(new Request.Builder().url("https://example.test/dan").build())
                .protocol(Protocol.HTTP_1_1).code(code).message("test").body(body).build();
    }

    private static final class TrackingStream extends ByteArrayInputStream {
        boolean closed;

        TrackingStream() {
            super("danmaku".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
