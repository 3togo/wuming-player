package com.wuming.android.tv.player.offline;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

public class PlaybackDownloadsTest {

    @Test
    public void acceptsOnlySelfContainedPublicNetworkLinks() {
        assertTrue(PlaybackDownloads.supported(
                "https://media.example/video.mp4", "video/mp4", Map.of()));
        assertTrue(PlaybackDownloads.supported(
                "https://media.example/master.m3u8", "application/x-mpegURL", Map.of()));
        assertFalse(PlaybackDownloads.supported(
                "https://media.example/video.mp4", "video/mp4",
                Map.of("Cookie", "session=secret")));
        assertFalse(PlaybackDownloads.supported(
                "http://127.0.0.1:9978/proxy", "video/mp4", Map.of()));
        assertFalse(PlaybackDownloads.supported(
                "rtsp://camera.example/live", null, Map.of()));
    }

    @Test
    public void keepsAFreeSpaceReserve() {
        long gib = 1024L * 1024L * 1024L;

        assertTrue(PlaybackDownloads.hasStorage(2L * gib, gib));
        assertFalse(PlaybackDownloads.hasStorage(gib, gib));
        assertFalse(PlaybackDownloads.hasStorage(Long.MAX_VALUE, Long.MAX_VALUE));
        assertFalse(PlaybackDownloads.hasStorage(2L * gib, -1L));
    }
}
