package com.wuming.android.tv.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.wuming.tv.core.playback.HlsPlaylistPurifier;
import java.net.URI;
import java.util.List;
import org.junit.Test;

public class HlsPlaylistLocationTest {
    private static final String PROXY = "http://127.0.0.1:9978/proxy?do=m3u8"
            + "&url=https%3A%2F%2Fvip.ffzy-online3.com%2Fepisode%2Findex.m3u8";

    @Test
    public void allowsProviderPlaylistsAndDirectHlsButNotOtherLocalRoutes() {
        assertTrue(HlsPlaylistLocation.shouldPurify(PROXY));
        assertTrue(HlsPlaylistLocation.shouldPurify(PROXY.replace("127.0.0.1", "localhost")));
        assertTrue(HlsPlaylistLocation.shouldPurify(PROXY.replace("127.0.0.1", "[::1]")));
        assertTrue(HlsPlaylistLocation.shouldPurify("http://127.0.0.1:9978/live/index.m3u8"));
        assertTrue(HlsPlaylistLocation.shouldPurify("https://example.com/index.m3u8"));
        assertFalse(HlsPlaylistLocation.shouldPurify(null));
        assertFalse(HlsPlaylistLocation.shouldPurify("http://127.0.0.1:9978/m3u8"));
        assertFalse(HlsPlaylistLocation.shouldPurify(PROXY.replace("do=m3u8", "do=other")));
        assertFalse(HlsPlaylistLocation.shouldPurify("http://127.0.0.1:9978/proxy?do=stream&url=movie.mp4"));
    }

    @Test
    public void matchesUpstreamRulesWithoutUsingUpstreamAsTransportBase() {
        assertEquals("https://vip.ffzy-online3.com/episode/", HlsPlaylistLocation.ruleBaseUrl(PROXY));
        assertEquals("http://127.0.0.1:9978/", HlsPlaylistLocation.baseUrl(PROXY));
        String unescaped = "http://127.0.0.1:9978/proxy?do=m3u8&url=https://media.example/nested/index.m3u8";
        assertEquals("http://127.0.0.1:9978/", HlsPlaylistLocation.baseUrl(unescaped));
        assertEquals("https://media.example/nested/", HlsPlaylistLocation.ruleBaseUrl(unescaped));
    }

    @Test
    public void onlyUnwrapsTheKnownLocalProviderEndpoint() {
        String remote = PROXY.replace("127.0.0.1:9978", "relay.example");
        assertEquals("http://relay.example/", HlsPlaylistLocation.ruleBaseUrl(remote));
        assertEquals("http://127.0.0.1:9978/", HlsPlaylistLocation.ruleBaseUrl(PROXY.replace("do=m3u8", "do=other")));
        assertEquals("http://127.0.0.1:9978/", HlsPlaylistLocation.ruleBaseUrl("http://127.0.0.1:9978/proxy?do=m3u8&url=file%3A%2F%2F%2Findex.m3u8"));
        assertEquals("https://media.example/path/", HlsPlaylistLocation.ruleBaseUrl("https://media.example/path/index.m3u8?url=https://other.example/a.m3u8"));
    }

    @Test
    public void followsLocalMasterWithOriginalRuleScopeButRemoteMasterWithItsOwnScope() {
        String localChild = "http://127.0.0.1:9978/nested/media.m3u8";
        assertEquals("https://vip.ffzy-online3.com/episode/", HlsPlaylistLocation.ruleBaseUrl(localChild, PROXY));
        assertEquals("http://127.0.0.1:9978/nested/", HlsPlaylistLocation.baseUrl(localChild));
        String remoteChild = "https://cdn.example/hls/media.m3u8";
        assertEquals("https://cdn.example/hls/", HlsPlaylistLocation.ruleBaseUrl(remoteChild, PROXY));
        String proxiedChild = PROXY.replace("vip.ffzy-online3.com", "cdn.example");
        assertEquals("https://cdn.example/episode/", HlsPlaylistLocation.ruleBaseUrl(proxiedChild, PROXY));
    }

    @Test
    public void existingDurationRuleFiltersProviderOutputAndPreservesLocalReferences() {
        String playlist = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n"
                + "#EXT-X-DISCONTINUITY\n#EXTINF:8.0,\ncontent.ts\n"
                + "#EXT-X-DISCONTINUITY\n#EXTINF:6.96,\na.ts\n#EXTINF:4.0,\nb.ts\n"
                + "#EXTINF:2.56,\nc.ts\n#EXTINF:3.84,\nd.ts\n"
                + "#EXT-X-DISCONTINUITY\n#EXTINF:9.0,\nend.ts\n#EXT-X-ENDLIST\n";
        String result = HlsPlaylistPurifier.purify(HlsPlaylistLocation.baseUrl(PROXY), playlist,
                List.of("17.36"), (base, reference) -> URI.create(base).resolve(reference).toString());
        assertTrue(result.contains("http://127.0.0.1:9978/content.ts"));
        assertTrue(result.contains("http://127.0.0.1:9978/end.ts"));
        assertTrue(result.contains("URI=\"http://127.0.0.1:9978/key.bin\""));
        assertFalse(result.contains("#EXTINF:6.96"));
        assertFalse(result.contains("#EXTINF:3.84"));
        assertTrue(result.contains("#EXT-X-ENDLIST"));
        assertNull(HlsPlaylistPurifier.purify(HlsPlaylistLocation.baseUrl(PROXY), playlist, List.of()));
        assertNull(HlsPlaylistPurifier.purify(HlsPlaylistLocation.baseUrl(PROXY), "not HLS", List.of("17.36")));
    }
}
