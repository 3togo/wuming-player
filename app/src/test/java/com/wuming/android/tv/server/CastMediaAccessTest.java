package com.wuming.android.tv.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.junit.After;

import org.junit.Test;

public class CastMediaAccessTest {

    @After
    public void clear() {
        CastMediaAccess.clear();
    }

    @Test
    public void dashProxyKeepsManifestExtensionForRendererDetection() {
        assertEquals("/stream.mpd", CastMediaAccess.streamSuffix("http://127.0.0.1:9978/proxy?do=bili&type=mpd", false));
    }

    @Test
    public void relayRetainsTransportCapabilitiesWithoutSendingInternalHeaders() {
        String profile = com.wuming.tv.core.playback.OpenDrivePlaybackHints.PROFILE_QUARK_ORIGINAL;
        CastMediaAccess.Relay relay = new CastMediaAccess.Relay("https://media.example/file.mp4",
                Map.of("Cookie", "private", "X-OpenDrive-Content-Length", "99999999",
                        "X-OpenDrive-Parallel-Range", profile,
                        "X-OpenDrive-Explicit-Cookie", "true"), Long.MAX_VALUE);
        assertTrue(relay.playbackPlan().parallelRanges());
        assertTrue(relay.playbackPlan().explicitCookie());
        assertEquals(profile, relay.playbackPlan().parallelProfile());
        assertEquals(Map.of("Cookie", "private"), relay.headers());
    }

    @Test
    public void unknownLiveProxyIsAdvertisedAsHls() {
        assertEquals("/stream.m3u8", CastMediaAccess.streamSuffix("http://127.0.0.1:9978/proxy?do=live", true));
    }

    @Test
    public void knownLiveContainerKeepsItsSuffix() {
        assertEquals("/stream.ts", CastMediaAccess.streamSuffix("https://example.com/live/channel.ts?token=1", true));
    }

    @Test
    public void newAuthorizationDoesNotInvalidateExistingCast() {
        long now = System.currentTimeMillis();
        String first = CastMediaAccess.registerAuthorization("/proxy/first", now);
        String second = CastMediaAccess.registerAuthorization("/proxy/second", now);

        assertEquals("/proxy/first", CastMediaAccess.resolve(first));
        assertEquals("/proxy/second", CastMediaAccess.resolve(second));
    }

    @Test
    public void identifiesInternalPlaybackHintsThatMustNotReachCastUpstream() {
        assertTrue(CastMediaAccess.isInternalPlaybackHeader("X-OpenDrive-Content-Length"));
        assertTrue(CastMediaAccess.isInternalPlaybackHeader("x-opendrive-parallel-range"));
        assertFalse(CastMediaAccess.isInternalPlaybackHeader("Cookie"));
        assertFalse(CastMediaAccess.isInternalPlaybackHeader(null));
    }

    @Test
    public void relayAcceptsOnlyExplicitlyRegisteredPlaylistChildren() {
        CastMediaAccess.Relay relay = new CastMediaAccess.Relay(
                "https://media.example/master.m3u8", Map.of("Cookie", "private"),
                Long.MAX_VALUE);

        String segment = "https://cdn.example/segment.ts";
        assertFalse(relay.allowsChild(segment));
        assertTrue(relay.allowChild(segment));
        assertTrue(relay.allowsChild(segment));
        assertFalse(relay.allowsChild("http://127.0.0.1:9978/private"));
    }

    @Test
    public void encodedRelayPathRejectsForgeryUntilChildIsRegistered() {
        CastMediaAccess.Relay relay = new CastMediaAccess.Relay(
                "https://media.example/master.m3u8", Map.of("Cookie", "private"),
                Long.MAX_VALUE);
        String token = CastMediaAccess.registerRelay(relay);
        String child = "https://cdn.example/segment.ts";
        String path = "/cast-relay/" + token + "/u/" + Base64.getUrlEncoder()
                .withoutPadding().encodeToString(child.getBytes(StandardCharsets.UTF_8));

        assertNull(CastMediaAccess.getRelayTarget(path));
        assertTrue(relay.allowChild(child));
        assertEquals(child, CastMediaAccess.getRelayTarget(path));
    }

    @Test
    public void expiredRelayCannotResolveRootOrRegisteredChild() {
        CastMediaAccess.Relay relay = new CastMediaAccess.Relay(
                "https://media.example/master.m3u8", Map.of(), 0L);
        String token = CastMediaAccess.registerRelay(relay);
        relay.allowChild("https://cdn.example/segment.ts");

        assertNull(CastMediaAccess.getRelayTarget("/cast-relay/" + token + "/stream.m3u8"));
        assertNull(CastMediaAccess.getRelay("/cast-relay/" + token));
    }

    @Test
    public void relayChildrenUseBoundedLeastRecentlyUsedEviction() {
        CastMediaAccess.Relay relay = new CastMediaAccess.Relay(
                "https://media.example/master.m3u8", Map.of(), Long.MAX_VALUE);
        for (int index = 0; index < CastMediaAccess.MAX_RELAY_CHILDREN; index++) {
            assertTrue(relay.allowChild("https://cdn.example/segment-" + index + ".ts"));
        }
        String retained = "https://cdn.example/segment-0.ts";
        String evicted = "https://cdn.example/segment-1.ts";
        assertTrue(relay.allowsChild(retained));

        assertTrue(relay.allowChild("https://cdn.example/new-segment.ts"));

        assertEquals(CastMediaAccess.MAX_RELAY_CHILDREN, relay.allowedChildCount());
        assertTrue(relay.allowsChild(retained));
        assertFalse(relay.allowsChild(evicted));
    }
}
