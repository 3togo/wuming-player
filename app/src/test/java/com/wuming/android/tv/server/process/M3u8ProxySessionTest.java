package com.wuming.android.tv.server.process;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

public class M3u8ProxySessionTest {

    private final M3u8Proxy proxy = new M3u8Proxy();

    @After
    public void clearSessions() {
        for (int index = 0; index <= 25; index++) M3u8Proxy.clearContent("episode-" + index);
        M3u8Proxy.clearContent("gone");
        M3u8Proxy.clearContent("default");
    }

    @Test
    public void sessionsReceiveOnlyTheirOwnPlaylist() throws IOException {
        M3u8Proxy.setContent("episode-0", "#EXTM3U\nfirst.ts");
        M3u8Proxy.setContent("episode-1", "#EXTM3U\nsecond.ts");

        assertEquals("#EXTM3U\nfirst.ts", serve("episode-0"));
        assertEquals("#EXTM3U\nsecond.ts", serve("episode-1"));

        M3u8Proxy.clearContent("episode-0");
        assertNotEquals(Status.OK, respond("episode-0").getStatus());
        assertEquals("#EXTM3U\nsecond.ts", serve("episode-1"));
    }

    @Test
    public void oldestUntouchedSessionLeavesFirstWhenTheCacheIsFull() throws IOException {
        for (int index = 0; index < 20; index++) {
            M3u8Proxy.setContent("episode-" + index, "#EXTM3U\n" + index + ".ts");
        }
        // Reading a playlist marks it as recently used, so it must outlive older idle sessions.
        assertEquals("#EXTM3U\n1.ts", serve("episode-1"));

        M3u8Proxy.setContent("episode-20", "#EXTM3U\n20.ts");

        assertNotEquals(Status.OK, respond("episode-0").getStatus());
        assertEquals("#EXTM3U\n1.ts", serve("episode-1"));
        assertEquals("#EXTM3U\n20.ts", serve("episode-20"));
    }

    @Test
    public void playbackPlaylistSurvivesSessionChurn() throws IOException {
        M3u8Proxy.setContent("#EXTM3U\nowned.ts");
        for (int index = 0; index < 25; index++) {
            M3u8Proxy.setContent("episode-" + index, "#EXTM3U\n" + index + ".ts");
        }

        assertEquals("#EXTM3U\nowned.ts", M3u8Proxy.getContent());
        assertEquals("#EXTM3U\nowned.ts", serve(null));

        M3u8Proxy.clearContent("default");
        assertNull(M3u8Proxy.getContent());
        assertNotEquals(Status.OK, respond(null).getStatus());
    }

    private String serve(String key) throws IOException {
        Response response = respond(key);
        assertEquals(response.toString(), Status.OK, response.getStatus());
        return new String(response.getData().readAllBytes(), StandardCharsets.UTF_8);
    }

    private Response respond(String key) {
        return proxy.doResponse(com.wuming.android.tv.server.HttpSessionFixture.unused(), key == null ? "/m3u8" : "/m3u8?key=" + key, Collections.emptyMap());
    }

    @Test
    public void missingSessionReportsTheDocumentedText() throws IOException {
        Response response = respond("gone");

        assertEquals(Status.INTERNAL_ERROR, response.getStatus());
        assertEquals("No M3U8 content",
                new String(response.getData().readAllBytes(), StandardCharsets.UTF_8));
    }
}
