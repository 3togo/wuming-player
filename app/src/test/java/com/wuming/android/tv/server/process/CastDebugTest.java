package com.wuming.android.tv.server.process;

import com.wuming.android.tv.utils.AppLog;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class CastDebugTest {
    @Test public void routeCorrelatesRootAndChildWithoutExposingAuthorization() {
        String root = "http://user:password@192.0.2.1:9978/cast-relay/secret-token/stream?key=private";
        String child = "/cast-relay/secret-token/u/encoded-secret-url";
        assertEquals(CastDebug.route(root), CastDebug.route(child));
        assertEquals(12, CastDebug.route(root).length());
        assertFalse(CastDebug.route(root).contains("secret"));
        assertEquals("http://192.0.2.1", CastDebug.endpoint(root));
        assertEquals("non_relay", CastDebug.route("https://cdn.example/movie?key=private"));
    }

    @Test public void unchangedDashReportsZeroRewritesWithoutLoggingManifestSecrets() {
        List<String> messages = new ArrayList<>();
        AppLog.Level previous = AppLog.getMinLevel();
        AppLog.setMinLevel(AppLog.Level.DEBUG);
        AppLog.setSink((level, tag, message, error) -> messages.add(message));
        try {
            String manifest = "<MPD><BaseURL>https://cdn.example/video?token=secret</BaseURL></MPD>";
            assertEquals(manifest, MpdPlaylistRewriter.rewriteManifest(manifest, "/unused", "request=1"));
            assertTrue(messages.stream().anyMatch(s -> s.contains("rewrittenUrls=0")
                    && s.contains("untouchedHttpReferences=1") && s.contains("changed=false")));
            assertFalse(messages.toString().contains("secret"));
            assertFalse(messages.toString().contains("cdn.example"));
            messages.clear();
            String loopback = "<MPD><BaseURL>http://127.0.0.1:9978/proxy?secret=1</BaseURL></MPD>";
            assertEquals(loopback, MpdPlaylistRewriter.rewriteManifest(loopback, "/unused", "request=2"));
            assertTrue(messages.stream().anyMatch(m -> m.contains("rewrittenUrls=0") && m.contains("changed=false")));
        } finally {
            AppLog.setSink(null);
            AppLog.setMinLevel(previous);
        }
    }
}
