package com.wuming.android.tv.server.process;

import fi.iki.elonen.NanoHTTPD;
import java.io.ByteArrayInputStream;
import org.junit.Test;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class SegmentRangeStreamTest {
    @Test(timeout = 10000)
    public void seeksWithUpstreamRangeAndPreservesCredentials() throws Exception {
        var requested = new java.util.concurrent.atomic.AtomicReference<String>();
        byte[] bytes = new byte[100000];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i % 251);
        NanoHTTPD server = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession session) {
                requested.set(session.getHeaders().get("range"));
                if (!"fixture".equals(session.getHeaders().get("cookie")))
                    return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "bad cookie");
                Response response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, "video/mp2t",
                        new ByteArrayInputStream(bytes, 90000, 100), 100);
                response.addHeader("Content-Range", "bytes 90000-90099/100000");
                return response;
            }
        };
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getListeningPort() + "/segment";
            var media = new AggregatedMedia(List.of(url), new long[]{bytes.length});
            try (var input = CastSegmentStreams.range(media, Map.of("Cookie", "fixture", "Range", "bytes=0-"), 90000, 100)) {
                assertArrayEquals(java.util.Arrays.copyOfRange(bytes, 90000, 90100), input.readAllBytes());
            }
            assertEquals("bytes=90000-90099", requested.get());
        } finally { server.stop(); }
    }

    @Test(timeout = 10000)
    public void rejectsWrongRangesAndTruncatedBodiesWithoutPadding() throws Exception {
        for (boolean wrongRange : new boolean[]{true, false}) {
            NanoHTTPD server = new NanoHTTPD("127.0.0.1", 0) {
                @Override public Response serve(IHTTPSession session) {
                    Response response = newChunkedResponse(wrongRange ? Response.Status.PARTIAL_CONTENT : Response.Status.OK,
                            "video/mp2t", new ByteArrayInputStream(new byte[3]));
                    if (wrongRange) response.addHeader("Content-Range", "bytes 0-9/100");
                    return response;
                }
            };
            server.start();
            try {
                String url = "http://127.0.0.1:" + server.getListeningPort() + "/segment";
                try (var input = CastSegmentStreams.range(new AggregatedMedia(List.of(url), new long[]{100}), Map.of(), 50, 10)) {
                    assertThrows(java.io.IOException.class, input::readAllBytes);
                }
            } finally { server.stop(); }
        }
    }

    @Test(timeout = 10000)
    public void unknownLengthsStreamInOrderAndPropagateHttpErrors() throws Exception {
        NanoHTTPD server = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession session) {
                return session.getUri().equals("/bad")
                        ? newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "denied")
                        : newChunkedResponse(Response.Status.OK, "video/mp2t",
                            new ByteArrayInputStream(session.getUri().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
        };
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getListeningPort();
            try (var input = CastSegmentStreams.unknown(List.of(base + "/one", base + "/two"), Map.of())) {
                assertArrayEquals("/one/two".getBytes(java.nio.charset.StandardCharsets.UTF_8), input.readAllBytes());
            }
            try (var input = CastSegmentStreams.unknown(List.of(base + "/one", base + "/bad", base + "/two"), Map.of())) {
                assertThrows(java.io.IOException.class, input::readAllBytes);
            }
        } finally { server.stop(); }
    }

    @Test(timeout = 10000)
    public void seekingStopsAtRequestedOffsetAndCrossesSegmentBoundaryExactly() throws Exception {
        byte[] first = new byte[65536], second = new byte[4096];
        for (int i = 0; i < first.length; i++) first[i] = (byte) (i % 251);
        for (int i = 0; i < second.length; i++) second[i] = (byte) (i % 137);
        NanoHTTPD server = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession session) {
                byte[] bytes = session.getUri().equals("/first") ? first : second;
                return newFixedLengthResponse(Response.Status.OK, "video/mp2t",
                        new ByteArrayInputStream(bytes), bytes.length);
            }
        };
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getListeningPort();
            AggregatedMedia media = new AggregatedMedia(List.of(base + "/first", base + "/second"),
                    new long[]{first.length, second.length});
            try (var stream = CastSegmentStreams.range(media, Map.of(), first.length - 99, 199)) {
                byte[] actual = stream.readAllBytes();
                assertEquals(199, actual.length);
                for (int i = 0; i < 99; i++) assertEquals(first[first.length - 99 + i], actual[i]);
                for (int i = 0; i < 100; i++) assertEquals(second[i], actual[99 + i]);
            }
        } finally { server.stop(); }
    }
}
