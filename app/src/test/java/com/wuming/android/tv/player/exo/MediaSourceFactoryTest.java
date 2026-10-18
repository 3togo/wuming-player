package com.wuming.android.tv.player.exo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.wuming.tv.core.playback.PlaybackTransportPlan;

import java.util.Map;

public class MediaSourceFactoryTest {

    @Test
    public void parallelRangesUseIndependentConnectionsAndPreserveClientSettings() {
        okhttp3.OkHttpClient original = new okhttp3.OkHttpClient.Builder()
                .readTimeout(17, java.util.concurrent.TimeUnit.SECONDS)
                .cookieJar(okhttp3.CookieJar.NO_COOKIES).build();
        okhttp3.OkHttpClient parallel = MediaSourceFactory.parallelRangeClient(original);
        assertEquals(java.util.List.of(okhttp3.Protocol.HTTP_1_1), parallel.protocols());
        assertTrue(original.protocols().contains(okhttp3.Protocol.HTTP_2));
        assertEquals(original.cookieJar(), parallel.cookieJar());
        assertEquals(original.readTimeoutMillis(), parallel.readTimeoutMillis());
        assertEquals(original.connectionPool(), parallel.connectionPool());
    }

    @Test
    public void explicitUcCookieSelectsIsolatedPlayerTransport() {
        String download = "https://dl-pc-zb.drive.uc.cn/file?auth_key=signed&token=value";

        assertTrue(MediaSourceFactory.isExplicitCookieUcMedia(
                download, Map.of("Cookie", "__puus=rotated")));
        assertFalse(MediaSourceFactory.isExplicitCookieUcMedia(download, Map.of()));
        assertFalse(MediaSourceFactory.isExplicitCookieUcMedia(
                "https://dl-pc-zb.drive.uc.cn.attacker.example/file",
                Map.of("Cookie", "__puus=rotated")));
        assertFalse(MediaSourceFactory.isExplicitCookieUcMedia(
                "http://dl-pc-zb.drive.uc.cn/file",
                Map.of("Cookie", "__puus=rotated")));
    }

    @Test
    public void signedProgressiveUrlUsesParallelRangesWithoutRequiringCache() {
        String url = "https://cdn.example/video.mp4?token=secret&expires=9";
        PlaybackTransportPlan plan = PlaybackTransportPlan.parse(url, "video/mp4", Map.of());

        assertTrue(MediaSourceFactory.shouldUseGenericParallel(url, plan));
        assertEquals(PlaybackTransportPlan.CachePolicy.NONE, plan.cachePolicy());
    }

    @Test
    public void genericParallelRangesExcludeLoopbackAndAdaptiveMedia() {
        for (String loopback : new String[]{
                "http://127.0.0.1:9978/proxy?do=video",
                "http://127.0.0.2/video.mp4",
                "http://player.localhost/video.mp4",
                "http://[0:0:0:0:0:0:0:1]/video.mp4"}) {
            assertFalse(MediaSourceFactory.shouldUseGenericParallel(loopback,
                    PlaybackTransportPlan.parse(loopback, "video/mp4", Map.of())));
        }

        String hls = "https://cdn.example/live.m3u8?token=secret";
        assertFalse(MediaSourceFactory.shouldUseGenericParallel(hls,
                PlaybackTransportPlan.parse(hls, null, Map.of())));
    }
}
