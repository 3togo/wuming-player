package com.wuming.android.tv.api.projection;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProjectionManagerRouteTest {

    @Test
    public void originalQuarkPhoneProxyUsesRelay() {
        assertTrue(ProjectionManager.preferRelayForOriginalQuark("夸父原", true));
        assertTrue(ProjectionManager.preferRelayForOriginalQuark("夸克原", true));
    }

    @Test
    public void directOrNonQuarkStreamsKeepConfiguredMode() {
        assertFalse(ProjectionManager.preferRelayForOriginalQuark("夸父原", false));
        assertFalse(ProjectionManager.preferRelayForOriginalQuark("夸父智", true));
        assertFalse(ProjectionManager.preferRelayForOriginalQuark(null, true));
    }

    @Test
    public void quarkCastMarkerPreservesOpaquePlaybackHandle() {
        String original = "http://192.168.1.8:9978/proxy?provider=quark&stream=opaque";

        String marked = ProjectionManager.markParallelQuarkCast(original);

        assertTrue(marked.contains("provider=quark"));
        assertTrue(marked.contains("stream=opaque"));
        assertTrue(marked.contains("quarkCast=parallel"));
        assertEquals(marked, ProjectionManager.markParallelQuarkCast(marked));
    }

    @Test
    public void originalQualityResolutionUsesQueuedEpisodeId() {
        String playbackUrl = "https://video-play.drive.quark.cn/media.m3u8?token=resolved";
        String episodeId = "shareId+fileId+token";

        assertEquals(episodeId,
                ProjectionManager.resolutionSourceUrl(playbackUrl, episodeId));
    }

    @Test
    public void originalQualityResolutionFallsBackWithoutQueue() {
        String playbackUrl = "https://video-play.drive.quark.cn/media.m3u8?token=resolved";

        assertEquals(playbackUrl,
                ProjectionManager.resolutionSourceUrl(playbackUrl, null));
        assertEquals(playbackUrl,
                ProjectionManager.resolutionSourceUrl(playbackUrl, ""));
    }

    @Test
    public void builtinDlnaAlwaysUsesOriginalPathForQuarkSmartQuality() {
        assertTrue(ProjectionManager.shouldResolveOriginalForBuiltinDlna(
                "夸父智", "https://video-play.drive.quark.cn/media.mp4"));
        assertTrue(ProjectionManager.shouldResolveOriginalForBuiltinDlna(
                "夸克智", "https://video-play.drive.quark.cn/media.mp4"));
        assertTrue(ProjectionManager.shouldResolveOriginalForBuiltinDlna(
                "other", "https://media.example/stream.m3u8"));
        assertFalse(ProjectionManager.shouldResolveOriginalForBuiltinDlna(
                "夸父原", "http://192.168.1.8:9978/proxy?stream=opaque"));
    }

    @Test
    public void unresolvedRemoteMediaUsesGenericRelay() {
        String hls = "https://video-play.drive.quark.cn/media.m3u8?token=resolved";
        String progressive = "https://video-play.drive.quark.cn/media.mp4?token=resolved";

        assertTrue(ProjectionManager.shouldUseGenericRelay(hls, hls, false));
        assertTrue(ProjectionManager.shouldUseGenericRelay(progressive, progressive, false));
        assertFalse(ProjectionManager.shouldUseGenericRelay(hls, hls, true));
        assertFalse(ProjectionManager.shouldUseGenericRelay(
                hls, "http://192.168.1.8:9978/cast/token/proxy", false));
    }
}
