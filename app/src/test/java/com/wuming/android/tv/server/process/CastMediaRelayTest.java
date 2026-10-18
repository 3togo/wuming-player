package com.wuming.android.tv.server.process;

import android.media.MediaCodec;
import android.media.MediaExtractor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import okhttp3.HttpUrl;
import okhttp3.Request;

public class CastMediaRelayTest {

    @Test
    public void manifestReadsAcceptExactLimitAndRejectLyingOrUnknownLengths() throws Exception {
        int limit = 2 * 1024 * 1024;
        String exact = "#EXTM3U\n" + "x".repeat(limit - 8);
        try (okhttp3.Response response = manifestResponse(exact, -1)) {
            assertEquals(exact, CastMediaRelay.readManifest(response));
        }
        for (long declared : new long[]{-1, 1, limit + 1L}) {
            try (okhttp3.Response response = manifestResponse(exact + "x", declared)) {
                org.junit.Assert.assertThrows(java.io.IOException.class,
                        () -> CastMediaRelay.readManifest(response));
            }
        }
    }

    @Test
    public void manifestLimitsCountUtf8BytesForDashToo() {
        String oversized = "<MPD>" + "字".repeat(700000) + "</MPD>";
        try (okhttp3.Response response = manifestResponse(oversized, -1)) {
            org.junit.Assert.assertThrows(java.io.IOException.class,
                    () -> CastMediaRelay.readManifest(response));
        }
    }

    private static okhttp3.Response manifestResponse(String text, long declaredLength) {
        return new okhttp3.Response.Builder().request(new Request.Builder().url("https://fixture.test/manifest").build())
                .protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(okhttp3.ResponseBody.create(new okio.Buffer().writeUtf8(text),
                        okhttp3.MediaType.get("text/plain; charset=utf-8"), declaredLength)).build();
    }

    @Test
    public void stagingCallPreservesAuthenticationAndRequestHeaders() {
        Request request = RelayHttpClient.upstreamCall("https://media.example/segment.ts",
                java.util.Map.of("Authorization", "Bearer fixture", "Cookie", "session=fixture",
                        "User-Agent", "fixture-agent", "Range", "bytes=99-"), "HEAD", "bytes=0-0").request();
        assertEquals("HEAD", request.method());
        assertEquals("Bearer fixture", request.header("Authorization"));
        assertEquals("session=fixture", request.header("Cookie"));
        assertEquals("fixture-agent", request.header("User-Agent"));
        assertEquals("bytes=0-0", request.header("Range"));
    }

    @Test
    public void translatesExtractorFlagsForMediaMuxer() {
        assertEquals(MediaCodec.BUFFER_FLAG_KEY_FRAME,
                TsToMp4Remuxer.muxerSampleFlags(MediaExtractor.SAMPLE_FLAG_SYNC));
        assertEquals(MediaCodec.BUFFER_FLAG_PARTIAL_FRAME,
                TsToMp4Remuxer.muxerSampleFlags(MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME));
        assertEquals(MediaCodec.BUFFER_FLAG_KEY_FRAME | MediaCodec.BUFFER_FLAG_PARTIAL_FRAME,
                TsToMp4Remuxer.muxerSampleFlags(MediaExtractor.SAMPLE_FLAG_SYNC
                        | MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME));
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesEncryptedSamplesDuringRemux() {
        TsToMp4Remuxer.muxerSampleFlags(MediaExtractor.SAMPLE_FLAG_ENCRYPTED);
    }

    @Test
    public void rewritesSegmentsVariantsAndKeysThroughRelay() {
        HttpUrl base = new Request.Builder().url("https://media.example/live/master/index.m3u8?token=abc").build().url();
        String playlist = "#EXTM3U\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"../keys/live.key\"\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=1000000\n"
                + "720/playlist.m3u8\n"
                + "#EXTINF:6,\n"
                + "https://cdn.example/segment.ts";

        String rewritten = HlsPlaylistRewriter.rewritePlaylist(playlist, base, url -> "relay:" + url);

        assertTrue(rewritten.contains("URI=\"relay:https://media.example/live/keys/live.key\""));
        assertTrue(rewritten.contains("relay:https://media.example/live/master/720/playlist.m3u8"));
        assertTrue(rewritten.contains("relay:https://cdn.example/segment.ts"));
    }

    @Test
    public void keepsOnlyHighestResolutionAndBitrateVariant() {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=600000,RESOLUTION=640x360,CODECS=\"avc1\"\nlow.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080,CODECS=\"avc1\"\nhigh-avc.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,CODECS=\"hvc1\"\nhigh-hevc.m3u8\n";

        String preferred = HlsPlaylistRewriter.preferHighestResolution(playlist);

        assertFalse(preferred.contains("low.m3u8"));
        assertTrue(preferred.contains("high-avc.m3u8"));
        assertFalse(preferred.contains("high-hevc.m3u8"));
    }

    @Test
    public void detectsHlsReturnedAsPlainTextFromPhpEndpoint() {
        assertTrue(HlsPlaylistRewriter.isHlsPrefix("#EXTM3U"));
        assertFalse(HlsPlaylistRewriter.isHlsPrefix("\u0000\u0000\u0000\u001cftyp"));
    }

    @Test
    public void neverRewritesAnUpstreamErrorPageAsAnHlsPlaylist() {
        assertTrue(HlsPlaylistRewriter.canRewritePlaylist(200));
        assertFalse(HlsPlaylistRewriter.canRewritePlaylist(403));
        assertFalse(HlsPlaylistRewriter.canRewritePlaylist(500));
    }

    @Test
    public void preservesLargeProgressiveLengthAndOnlyChunksUnknownBodies() {
        assertEquals(5L * 1024L * 1024L * 1024L,
                HlsPlaylistRewriter.relayContentLength(5L * 1024L * 1024L * 1024L));
        assertEquals(0L, HlsPlaylistRewriter.relayContentLength(0L));
        assertEquals(-1L, HlsPlaylistRewriter.relayContentLength(-1L));
    }
}
