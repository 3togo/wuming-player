package com.wuming.android.tv.cloud.opendrive;

import com.wuming.tv.core.playback.OpenDrivePlaybackHints;
import java.util.*;
import okhttp3.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class OpenDriveStreamCheckTest {
    private static Response response(Request request, int code, String type, String text) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .header("Content-Type", type).body(ResponseBody.create(text, MediaType.parse(type))).build();
    }
    @Test public void reachablePlaylistWithForbiddenSegmentIsNotSuccess() {
        var client = new OkHttpClient.Builder().addInterceptor(chain ->
                chain.request().url().encodedPath().equals("/playlist")
                        ? response(chain.request(), 200, "application/vnd.apple.mpegurl", "#EXTM3U\n#EXTINF:1\nmissing.ts\n")
                        : response(chain.request(), 403, "text/plain", "denied")).build();
        var result = new OpenDriveStreamCheck(client).check("https://fixture.example/playlist", Map.of());
        assertEquals(OpenDriveStreamCheck.State.HTTP_ERROR, result.state());
        assertEquals(403, result.httpStatus());
    }

    @Test public void oversizedPlaylistIsBounded() {
        var body = "#EXTM3U\n#comment" + "x".repeat(300_000);
        var client = new OkHttpClient.Builder().addInterceptor(chain ->
                response(chain.request(), 200, "application/vnd.apple.mpegurl", body)).build();
        assertEquals(OpenDriveStreamCheck.State.LIMIT,
                new OpenDriveStreamCheck(client).check("https://fixture.example/playlist", Map.of()).state());
    }

    @Test public void readsActualHlsSegmentAndStripsInternalHints() {
        var requests = new ArrayList<Request>();
        var client = new OkHttpClient.Builder().addInterceptor(chain -> {
            var request = chain.request(); requests.add(request);
            if (request.url().encodedPath().equals("/master"))
                return response(request, 200, "application/vnd.apple.mpegurl", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=123\nmedia\n");
            if (request.url().encodedPath().equals("/media"))
                return response(request, 200, "application/vnd.apple.mpegurl", "#EXTM3U\n#EXTINF:1\nsegment.ts\n");
            return response(request, 206, "video/mp2t", "binary fixture bytes".repeat(100));
        }).build();
        var result = new OpenDriveStreamCheck(client).check("https://fixture.example/master", Map.of(
                "Cookie", "session=fixture", OpenDrivePlaybackHints.COPY_LEASE, "opaque",
                OpenDrivePlaybackHints.FALLBACK_REASON, "UC_QUOTA"));
        assertEquals(OpenDriveStreamCheck.State.HLS_SEGMENT, result.state());
        assertEquals(5, requests.size());
        assertEquals("/segment.ts", requests.get(4).url().encodedPath());
        for (var request : requests) {
            assertNull(request.header(OpenDrivePlaybackHints.COPY_LEASE));
            assertNull(request.header(OpenDrivePlaybackHints.FALLBACK_REASON));
            assertEquals("session=fixture", request.header("Cookie"));
        }
    }
    @Test public void crossOriginRedirectCannotLeakCredentials() {
        var requests = new ArrayList<Request>();
        var client = new OkHttpClient.Builder().addInterceptor(chain -> {
            var request = chain.request(); requests.add(request);
            if (request.url().host().equals("fixture.example")) return response(request, 302, "text/plain", "")
                    .newBuilder().header("Location", "https://cdn.example/video").build();
            return response(request, 206, "video/mp4", "media bytes".repeat(200));
        }).build();
        assertEquals(OpenDriveStreamCheck.State.MEDIA_BYTES, new OpenDriveStreamCheck(client).check(
                "https://fixture.example/video", Map.of("Cookie", "secret", "Authorization", "secret", "X-Token", "secret")).state());
        assertNull(requests.get(1).header("Cookie")); assertNull(requests.get(1).header("Authorization"));
        assertNull(requests.get(1).header("X-Token"));
    }
    @Test public void textErrorsAndMissingSegmentsCannotPass() {
        for (int status : new int[]{200, 403}) {
            var client = new OkHttpClient.Builder().addInterceptor(chain -> response(chain.request(), status, "text/plain", "access denied by service")).build();
            var result = new OpenDriveStreamCheck(client).check("https://fixture.example/video", Map.of());
            assertEquals(status == 200 ? OpenDriveStreamCheck.State.TEXT_RESPONSE : OpenDriveStreamCheck.State.HTTP_ERROR, result.state());
        }
    }
    @Test public void cancellationAndPlaylistLoopsAreBounded() {
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var client = new OkHttpClient.Builder().addInterceptor(chain -> {
            count.incrementAndGet(); return response(chain.request(), 200, "application/vnd.apple.mpegurl", "#EXTM3U\nloop\n");
        }).build();
        var check = new OpenDriveStreamCheck(client); check.close();
        assertEquals(OpenDriveStreamCheck.State.CANCELLED, check.check("https://fixture.example/loop", Map.of()).state());
        assertEquals(0, count.get());
        assertEquals(OpenDriveStreamCheck.State.LIMIT, new OpenDriveStreamCheck(client).check("https://fixture.example/loop", Map.of()).state());
        assertTrue(count.get() <= 8);
    }
    @Test public void progressiveReadsAreBoundedEvenWhenServerIgnoresRange() {
        var client = new OkHttpClient.Builder().addInterceptor(chain -> response(chain.request(), 200, "video/mp4", "x".repeat(1_000_000))).build();
        assertEquals(1024, new OpenDriveStreamCheck(client).check("https://fixture.example/video", Map.of()).bytes());
    }
}
