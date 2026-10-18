package com.wuming.android.tv.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.wuming.android.tv.server.process.M3u8Proxy;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import fi.iki.elonen.NanoHTTPD;

/**
 * The proxy answers each session from its own playlist, and never from a stale one.
 */
public class M3u8ProxyResponseTest {

    @Test
    public void sessionsReceiveOnlyTheirOwnPlaylist() throws Exception {
        M3u8Proxy proxy = new M3u8Proxy();
        M3u8Proxy.setContent("episode-a", "#EXTM3U\na.ts");
        M3u8Proxy.setContent("episode-b", "#EXTM3U\nb.ts");

        NanoHTTPD.Response first = proxy.doResponse(com.wuming.android.tv.server.HttpSessionFixture.unused(), "/m3u8?key=episode-a", Collections.emptyMap());
        NanoHTTPD.Response second = proxy.doResponse(com.wuming.android.tv.server.HttpSessionFixture.unused(), "/m3u8?key=episode-b", Collections.emptyMap());

        assertEquals(NanoHTTPD.Response.Status.OK, first.getStatus());
        assertEquals("application/vnd.apple.mpegurl", first.getMimeType());
        assertEquals("#EXTM3U\na.ts", new String(first.getData().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals("#EXTM3U\nb.ts", new String(second.getData().readAllBytes(), StandardCharsets.UTF_8));
        M3u8Proxy.clearContent("episode-a");
        M3u8Proxy.clearContent("episode-b");
    }

    @Test
    public void missingOrClearedSessionReturnsAnError() {
        M3u8Proxy proxy = new M3u8Proxy();
        M3u8Proxy.setContent("gone", "#EXTM3U");
        M3u8Proxy.clearContent("gone");

        NanoHTTPD.Response response = proxy.doResponse(com.wuming.android.tv.server.HttpSessionFixture.unused(), "/m3u8?key=gone", Collections.emptyMap());

        assertNotEquals(NanoHTTPD.Response.Status.OK, response.getStatus());
    }
}
