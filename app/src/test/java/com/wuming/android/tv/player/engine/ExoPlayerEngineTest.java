package com.wuming.android.tv.player.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.Player;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Tracks;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

public class ExoPlayerEngineTest {

    @Test
    public void deniedOrExpiredLiveRequestSurfacesErrorInsteadOfEmptyRefreshCallback() throws Exception {
        ExoPlayerEngine engine = new ExoPlayerEngine(PlayerEngine.HARD, player(() -> {}));
        java.lang.reflect.Field spec = ExoPlayerEngine.class.getDeclaredField("spec");
        spec.setAccessible(true);
        spec.set(engine, PlaySpec.from("channel", "https://example.invalid/live.m3u8", null, null));
        for (int status : new int[]{403, 410}) {
            assertSame(PlayerEngine.ErrorAction.FATAL, engine.handleError(new PlaybackException(
                    "Response code: " + status, null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    null, 0L) {}));
        }
    }

    @Test
    public void knownHdrRejectionDoesNotRebuildTheDecoder() throws Exception {
        ExoPlayerEngine engine = new ExoPlayerEngine(PlayerEngine.HARD, player(() -> {}));
        java.lang.reflect.Field spec = ExoPlayerEngine.class.getDeclaredField("spec");
        spec.setAccessible(true);
        spec.set(engine, PlaySpec.from("channel", "https://example.invalid/live.m3u8", null, null));
        Throwable rejected = new com.wuming.android.tv.player.exo.UnsupportedVideoException(
                new androidx.media3.common.Format.Builder().setSampleMimeType("video/hevc").build(),
                androidx.media3.common.C.FORMAT_EXCEEDS_CAPABILITIES,
                com.wuming.android.tv.player.exo.UnsupportedVideoException.Reason.HDR_DISPLAY);
        assertSame(PlayerEngine.ErrorAction.FATAL, engine.handleError(new PlaybackException(
                "display rejected", rejected, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                null, 0L) {}));
    }

    @Test
    public void exhaustedConnectionFailuresDoNotRestartTheSource() throws Exception {
        ExoPlayerEngine engine = new ExoPlayerEngine(PlayerEngine.HARD, player(() -> {}));
        java.lang.reflect.Field spec = ExoPlayerEngine.class.getDeclaredField("spec");
        spec.setAccessible(true);
        spec.set(engine, PlaySpec.from("channel", "https://example.invalid/live.m3u8", null, null));
        for (int code : new int[]{PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT}) {
            assertSame(PlayerEngine.ErrorAction.FATAL,
                    // Supply a timestamp so this JVM test does not call Android's clock.
                    engine.handleError(new PlaybackException("connection failed", null, code, null, 0L) {}));
        }
    }

    @Test
    public void recoverableHttpErrorResumesSeekableVideoAtItsPosition() throws Exception {
        AtomicInteger restarts = new AtomicInteger();
        long[] resumedAt = {-1L};
        Player player = retryPlayer(false, true, 42_000L, restarts, resumedAt);
        ExoPlayerEngine engine = new ExoPlayerEngine(PlayerEngine.HARD, player, spec -> MediaItem.EMPTY);
        setSpec(engine, "https://example.invalid/movie.mp4");

        PlaybackException error = new PlaybackException("Response code: 503", null,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, null, 0L) {};
        assertSame(PlayerEngine.ErrorAction.RETRY, engine.handleError(error));
        assertEquals(8_000L, engine.pendingRetryDelayMs());
        assertEquals(0, restarts.get());
        engine.retryAfterError();
        assertEquals(42_000L, resumedAt[0]);
        assertEquals(1, restarts.get());
        engine.retryAfterError();
        assertEquals(1, restarts.get());
        assertSame(PlayerEngine.ErrorAction.RETRY, engine.handleError(error));
        assertEquals(16_000L, engine.pendingRetryDelayMs());
        engine.retryAfterError();
        assertSame(PlayerEngine.ErrorAction.FATAL, engine.handleError(error));
        assertEquals(2, restarts.get());
    }

    @Test
    public void recoverableHttpErrorRestartsLiveVideoAtTheLiveEdge() throws Exception {
        AtomicInteger restarts = new AtomicInteger();
        long[] resumedAt = {-1L};
        ExoPlayerEngine engine = new ExoPlayerEngine(PlayerEngine.HARD,
                retryPlayer(true, true, 42_000L, restarts, resumedAt), spec -> MediaItem.EMPTY);
        setSpec(engine, "https://example.invalid/live.m3u8");

        assertSame(PlayerEngine.ErrorAction.RETRY, engine.handleError(new PlaybackException(
                "Response code: 503", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                null, 0L) {}));
        engine.retryAfterError();
        assertEquals(0L, resumedAt[0]);
        assertEquals(1, restarts.get());
    }

    @Test
    public void requestTimeoutUsesTheSameRecoveryDecisionAsTheLoader() throws Exception {
        AtomicInteger restarts = new AtomicInteger();
        ExoPlayerEngine engine = new ExoPlayerEngine(PlayerEngine.HARD,
                retryPlayer(false, true, 9_000L, restarts, new long[]{-1L}), spec -> MediaItem.EMPTY);
        setSpec(engine, "https://example.invalid/movie.mp4");
        assertSame(PlayerEngine.ErrorAction.RETRY, engine.handleError(new PlaybackException(
                "Response code: 408", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                null, 0L) {}));
        assertEquals(8_000L, engine.pendingRetryDelayMs());
        assertEquals(0, restarts.get());
    }

    @Test
    public void engineRestartRespectsRetryAfterHeader() throws Exception {
        AtomicInteger restarts = new AtomicInteger();
        ExoPlayerEngine engine = new ExoPlayerEngine(PlayerEngine.HARD,
                retryPlayer(false, true, 9_000L, restarts, new long[]{-1L}), spec -> MediaItem.EMPTY);
        setSpec(engine, "https://example.invalid/movie.mp4");
        var response = new androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException(
                429, "Too Many Requests", null,
                java.util.Map.of("Retry-After", java.util.List.of("30")), null, new byte[0]);
        assertSame(PlayerEngine.ErrorAction.RETRY, engine.handleError(new PlaybackException(
                "throttled", response, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                null, 0L) {}));
        assertEquals(30_000L, engine.pendingRetryDelayMs());
        assertEquals(0, restarts.get());
    }

    @Test
    public void replacingTheVideoCancelsItsPendingRestart() throws Exception {
        AtomicInteger restarts = new AtomicInteger();
        ExoPlayerEngine engine = new ExoPlayerEngine(PlayerEngine.HARD,
                retryPlayer(false, true, 9_000L, restarts, new long[]{-1L}), spec -> MediaItem.EMPTY);
        setSpec(engine, "https://example.invalid/old.mp4");
        assertSame(PlayerEngine.ErrorAction.RETRY, engine.handleError(new PlaybackException(
                "Response code: 503", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                null, 0L) {}));
        engine.start(PlaySpec.from("new", "https://example.invalid/new.mp4", null, null));
        engine.retryAfterError();
        assertEquals(1, restarts.get());
        assertEquals(0L, engine.pendingRetryDelayMs());
    }

    @Test
    public void releaseDropsPlayerOwnershipAndIsIdempotent() {
        AtomicInteger releases = new AtomicInteger();
        ExoPlayerEngine engine = new ExoPlayerEngine(PlayerEngine.HARD, player(() -> releases.incrementAndGet()));
        engine.release();
        engine.release();
        assertEquals(1, releases.get());
        assertNull(engine.getPlayer());
        assertFalse(engine.isLive());
        assertFalse(engine.isVod());
        assertSame(Tracks.EMPTY, engine.getCurrentTracks());
        engine.resetTrack();
    }

    @Test
    public void failingReleaseStillDropsThePlayerReference() {
        RuntimeException failure = new IllegalStateException("release failed");
        ExoPlayerEngine engine = new ExoPlayerEngine(PlayerEngine.HARD, player(() -> { throw failure; }));
        assertSame(failure, assertThrows(RuntimeException.class, engine::release));
        assertNull(engine.getPlayer());
        engine.release();
    }

    private static Player player(Runnable release) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, arguments) -> {
                    if ("release".equals(method.getName())) {
                        release.run();
                        return null;
                    }
                    throw new AssertionError("Unexpected player call: " + method.getName());
                });
    }

    private static void setSpec(ExoPlayerEngine engine, String url) throws Exception {
        java.lang.reflect.Field spec = ExoPlayerEngine.class.getDeclaredField("spec");
        spec.setAccessible(true);
        spec.set(engine, PlaySpec.from("channel", url, null, null));
    }

    private static Player retryPlayer(boolean live, boolean seekable, long positionMs,
            AtomicInteger restarts, long[] resumedAt) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, arguments) -> {
                    return switch (method.getName()) {
                        case "isCurrentMediaItemLive" -> live;
                        case "isCurrentMediaItemSeekable" -> seekable;
                        case "getCurrentPosition" -> positionMs;
                        case "setMediaItem" -> {
                            resumedAt[0] = arguments.length == 2 ? (long) arguments[1] : 0L;
                            restarts.incrementAndGet();
                            yield null;
                        }
                        case "prepare", "play" -> null;
                        default -> throw new AssertionError("Unexpected player call: " + method.getName());
                    };
                });
    }
}
