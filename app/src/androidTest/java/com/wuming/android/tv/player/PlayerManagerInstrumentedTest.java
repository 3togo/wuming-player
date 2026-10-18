package com.wuming.android.tv.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Looper;
import android.os.SystemClock;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.MediaMetadata;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wuming.android.tv.bean.Sub;
import com.wuming.android.tv.bean.Track;
import com.wuming.android.tv.player.engine.PlaySpec;
import com.wuming.android.tv.player.engine.PlayerEngine;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class PlayerManagerInstrumentedTest {

    private PlayerManager manager;

    @After
    public void tearDown() {
        if (manager != null) manager.release();
    }

    @Test
    public void sourceAndEpisodeSwitchingReplaceTheActivePlaySpec() {
        FakeEngine engine = new FakeEngine();
        RecordingCallback callback = new RecordingCallback();
        manager = new PlayerManager(callback, engine);

        PlaySpec first = PlaySpec.from("episode-1", "https://example.test/one.m3u8",
                Collections.emptyMap(), new MediaMetadata.Builder().setTitle("One").build());
        manager.start(first, 60_000);
        manager.reset();

        assertSame(first, engine.startedSpec);
        assertEquals("episode-1", manager.getKey());
        assertEquals("https://example.test/one.m3u8", manager.getUrl());
        assertTrue(manager.getHeaders().keySet().stream().anyMatch("User-Agent"::equalsIgnoreCase));
        assertEquals(1, callback.prepareCount);

        PlaySpec second = PlaySpec.from("episode-2", "https://example.test/two.m3u8",
                Collections.singletonMap("Referer", "https://example.test/"), null);
        manager.start(second, 60_000);
        manager.reset();

        assertSame(second, engine.startedSpec);
        assertEquals("episode-2", manager.getKey());
        assertEquals(2, callback.prepareCount);
    }

    @Test
    public void playbackControlsTracksAndSubtitleChangesReachTheEngine() {
        FakeEngine engine = new FakeEngine();
        manager = new PlayerManager(new RecordingCallback(), engine);
        manager.start(PlaySpec.from("episode", "https://example.test/video.mp4",
                Collections.emptyMap(), null), 60_000);
        manager.reset();

        manager.play();
        manager.pause();
        manager.seekTo(12_345);
        manager.setRepeatOne(true);
        manager.setSpeed(1.5f);
        manager.setTrack(List.of(new Track(1, "English", "aac").key("episode")));
        manager.setSub(Sub.create("English", "https://example.test/sub.vtt", "en", "text/vtt"));
        manager.reset();

        assertEquals(1, engine.playerHandler.playCount);
        assertEquals(1, engine.playerHandler.pauseCount);
        assertEquals(12_345, engine.playerHandler.position);
        assertEquals(Player.REPEAT_MODE_ONE, engine.playerHandler.repeatMode);
        assertEquals(1.5f, manager.getSpeed(), 0.001f);
        assertEquals(1, engine.trackSetCount);
        assertEquals(2, engine.startCount);
    }

    @Test
    public void recoveredDecodeAndFatalErrorsFollowDifferentPaths() {
        FakeEngine engine = new FakeEngine();
        RecordingCallback callback = new RecordingCallback();
        manager = new PlayerManager(callback, engine);
        manager.start(PlaySpec.from("episode", "https://example.test/video.mp4",
                Collections.emptyMap(), null), 60_000);
        manager.reset();

        PlaybackException error = new PlaybackException("failure", null,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED);
        engine.errorAction = PlayerEngine.ErrorAction.RECOVERED;
        engine.playerHandler.listener.onPlayerError(error);
        assertEquals(0, callback.errorCount);
        assertEquals(0, callback.rebuildCount);

        engine.errorAction = PlayerEngine.ErrorAction.DECODE;
        engine.playerHandler.listener.onPlayerError(error);
        // DECODE schedules toggleDecode on the main thread after retry*1s; wait for it.
        awaitTrue(() -> !engine.hard, 5_000);
        manager.reset();
        assertFalse(engine.hard);
        assertEquals(1, callback.rebuildCount);

        engine.errorAction = PlayerEngine.ErrorAction.FATAL;
        engine.playerHandler.listener.onPlayerError(error);
        assertEquals(1, callback.errorCount);
    }

    @Test
    public void releaseDetachesListenerReleasesEngineAndIsIdempotent() {
        FakeEngine engine = new FakeEngine();
        manager = new PlayerManager(new RecordingCallback(), engine);
        assertNotNull(engine.playerHandler.listener);

        manager.release();
        manager.release();

        assertEquals(1, engine.releaseCount);
        assertNull(engine.playerHandler.listener);
        assertNull(manager.getPlayer());
        assertEquals(Player.STATE_IDLE, manager.getPlaybackState());
        manager = null;
    }

    @Test
    public void realMedia3EngineCanBeCreatedAndReleasedOnMainThread() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            manager = new PlayerManager(new RecordingCallback());
            assertNotNull(manager.getPlayer());
            assertEquals(Player.STATE_IDLE, manager.getPlaybackState());
            manager.release();
            assertNull(manager.getPlayer());
            manager = null;
        });
    }

    @Test
    public void playbackCanStopForBackgroundAndRestartAfterConnectivityReturns() {
        FakeEngine engine = new FakeEngine();
        manager = new PlayerManager(new RecordingCallback(), engine);
        PlaySpec spec = PlaySpec.from("episode", "https://example.test/video.m3u8",
                Collections.emptyMap(), null);

        manager.start(spec, 60_000);
        manager.reset();
        manager.stop();
        assertEquals(1, engine.playerHandler.stopCount);

        manager.start(spec, 60_000);
        manager.reset();
        assertEquals(2, engine.startCount);
        assertSame(spec, engine.startedSpec);
    }

    @Test
    public void repeatedDecoderFailuresStopRebuildingAndSurfaceAnError() {
        FakeEngine engine = new FakeEngine();
        RecordingCallback callback = new RecordingCallback();
        manager = new PlayerManager(callback, engine);
        manager.start(PlaySpec.from("episode", "https://example.test/video.mp4",
                Collections.emptyMap(), null), 60_000);
        manager.reset();
        engine.errorAction = PlayerEngine.ErrorAction.DECODE;
        PlaybackException error = new PlaybackException("decoder unavailable", null,
                PlaybackException.ERROR_CODE_DECODING_FAILED);

        for (int attempt = 1; attempt <= 3; attempt++) {
            engine.playerHandler.listener.onPlayerError(error);
            int expected = attempt;
            awaitTrue(() -> callback.rebuildCount == expected, 5_000);
        }
        engine.playerHandler.listener.onPlayerError(error);

        assertEquals(3, callback.rebuildCount);
        assertEquals(1, callback.errorCount);
    }

    @Test public void queuedDecoderRetryCannotRestartReplacementEpisodeOrStoppedPlayer() {
        FakeEngine engine = new FakeEngine();
        RecordingCallback callback = new RecordingCallback();
        manager = new PlayerManager(callback, engine);
        engine.errorAction = PlayerEngine.ErrorAction.DECODE;
        PlaybackException error = new PlaybackException("decoder", null,
                PlaybackException.ERROR_CODE_DECODING_FAILED);
        manager.start(PlaySpec.from("same-key", "https://example.test/first.mp4", Collections.emptyMap(), null), 60_000);
        manager.reset();
        engine.playerHandler.listener.onPlayerError(error);
        manager.start(PlaySpec.from("same-key", "https://example.test/second.mp4", Collections.emptyMap(), null), 60_000);
        manager.reset();
        SystemClock.sleep(1_200);
        assertEquals(0, callback.rebuildCount);
        assertEquals(2, engine.startCount);
        engine.playerHandler.listener.onPlayerError(error);
        manager.stop();
        SystemClock.sleep(1_200);
        assertEquals(0, callback.rebuildCount);
        assertEquals(2, engine.startCount);
    }

    @Test public void duplicateDecoderErrorsCoalesceAndReleaseCancelsPendingRetry() {
        FakeEngine engine = new FakeEngine();
        RecordingCallback callback = new RecordingCallback();
        manager = new PlayerManager(callback, engine);
        manager.start(PlaySpec.from("episode", "https://example.test/video.mp4", Collections.emptyMap(), null), 60_000);
        manager.reset();
        engine.errorAction = PlayerEngine.ErrorAction.DECODE;
        PlaybackException error = new PlaybackException("decoder", null, PlaybackException.ERROR_CODE_DECODING_FAILED);
        engine.playerHandler.listener.onPlayerError(error);
        engine.playerHandler.listener.onPlayerError(error);
        awaitTrue(() -> callback.rebuildCount == 1, 4_000);
        engine.playerHandler.listener.onPlayerError(error);
        manager.release();
        SystemClock.sleep(3_200);
        assertEquals(1, callback.rebuildCount);
        assertNull(manager.getPlayer());
    }

    private static void awaitTrue(Check check, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (!check.passed() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(20);
        }
        if (!check.passed()) org.junit.Assert.fail("timed out waiting for async condition");
    }

    private interface Check {
        boolean passed();
    }

    @Test
    public void synchronousBufferingCallbackCancelsStartupTimeout() {
        FakeEngine engine = new FakeEngine();
        RecordingCallback callback = new RecordingCallback();
        manager = new PlayerManager(callback, engine);
        engine.stateOnStart = Player.STATE_BUFFERING;

        manager.start(PlaySpec.from("cloud-episode", "http://127.0.0.1:9978/proxy",
                Collections.emptyMap(), null), 50);
        SystemClock.sleep(150);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        assertEquals(0, callback.errorCount);
    }

    @Test
    public void stoppingPlaybackCancelsThePendingStartupTimeout() {
        FakeEngine engine = new FakeEngine();
        RecordingCallback callback = new RecordingCallback();
        manager = new PlayerManager(callback, engine);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            manager.start(PlaySpec.from("episode", "https://example.test/video.mp4",
                    Collections.emptyMap(), null), 50);
            manager.stop();
        });
        SystemClock.sleep(150);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(0, callback.errorCount);
    }

    @Test
    public void fatalErrorIsNotFollowedByADuplicateTimeoutError() {
        FakeEngine engine = new FakeEngine();
        RecordingCallback callback = new RecordingCallback();
        manager = new PlayerManager(callback, engine);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            manager.start(PlaySpec.from("episode", "https://example.test/video.mp4",
                    Collections.emptyMap(), null), 50);
            engine.playerHandler.listener.onPlayerError(new PlaybackException("failed", null,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED));
        });
        SystemClock.sleep(150);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(1, callback.errorCount);
    }

    @Test
    public void lateControlsAndCallbacksCannotRestartAReleasedManager() {
        FakeEngine engine = new FakeEngine();
        RecordingCallback callback = new RecordingCallback();
        manager = new PlayerManager(callback, engine);
        manager.release();
        manager.start(PlaySpec.from("late", "https://example.test/video.mp4",
                Collections.emptyMap(), null), 50);
        manager.onParseSuccess(Collections.emptyMap(), "https://example.test/late.mp4", "");
        manager.onParseError();
        manager.setMetadata(MediaMetadata.EMPTY);
        manager.setTrack(List.of(new Track(1, "English", "aac")));
        manager.toggleDecode();
        assertEquals(0, engine.startCount);
        assertEquals(0, callback.errorCount);
        assertNull(manager.getUrl());
    }

    @Test
    public void parsedHeadersAreCopiedAndRangeIsRemovedCaseInsensitively() {
        FakeEngine engine = new FakeEngine();
        manager = new PlayerManager(new RecordingCallback(), engine);
        manager.start(PlaySpec.from("episode", "https://example.test/video.mp4",
                Collections.emptyMap(), null), 60_000);
        java.util.Map<String, String> headers = java.util.Map.of(
                "rAnGe", "bytes=100-", "X-Test", "value");
        manager.onParseSuccess(headers, "https://example.test/resolved.mp4", "");
        manager.reset();
        assertTrue(headers.containsKey("rAnGe"));
        assertFalse(manager.getHeaders().keySet().stream().anyMatch("Range"::equalsIgnoreCase));
        assertEquals("value", manager.getHeaders().get("X-Test"));
    }

    @Test
    public void endingMarkerCannotBeSetBeyondTheMediaDuration() {
        manager = new PlayerManager(new RecordingCallback(), new FakeEngine());
        assertTrue(manager.canSetEnding(59_000L, 60_000L));
        assertFalse(manager.canSetEnding(61_000L, 60_000L));
    }

    @Test
    public void oldTrackLookupCannotApplyToAnotherSourceWithTheSameKey() {
        FakeEngine engine = new FakeEngine();
        List<Runnable> lookups = new ArrayList<>();
        manager = new PlayerManager(new RecordingCallback(), engine, lookups::add,
                key -> List.of(new Track(1, "English", "aac").key(key)));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            manager.start(PlaySpec.from("episode", "https://example.test/first.mp4",
                    Collections.emptyMap(), null), 60_000);
            engine.playerHandler.listener.onTracksChanged(audioTracks());
            manager.start(PlaySpec.from("episode", "https://example.test/second.mp4",
                    Collections.emptyMap(), null), 60_000);
            engine.playerHandler.listener.onTracksChanged(audioTracks());
            lookups.get(0).run();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(0, engine.trackSetCount);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(lookups.get(1));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(1, engine.trackSetCount);
    }

    @Test
    public void trackCallbackCanStartAnotherPlaybackWithoutSuppressingItsTracks() {
        FakeEngine engine = new FakeEngine();
        RecordingCallback callback = new RecordingCallback();
        List<Runnable> lookups = new ArrayList<>();
        manager = new PlayerManager(callback, engine, lookups::add, key -> Collections.emptyList());
        callback.tracksAction = () -> {
            if (callback.trackCount == 1) manager.start(PlaySpec.from("next", "https://example.test/next.mp4",
                    Collections.emptyMap(), null), 60_000);
        };
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            manager.start(PlaySpec.from("first", "https://example.test/first.mp4",
                    Collections.emptyMap(), null), 60_000);
            engine.playerHandler.listener.onTracksChanged(audioTracks());
            engine.playerHandler.listener.onTracksChanged(audioTracks());
        });
        assertEquals(2, callback.trackCount);
        assertEquals(2, lookups.size());
    }

    @Test
    public void stoppingInvalidatesPendingTrackLookup() {
        FakeEngine engine = new FakeEngine();
        List<Runnable> lookups = new ArrayList<>();
        manager = new PlayerManager(new RecordingCallback(), engine, lookups::add,
                key -> List.of(new Track(1, "English", "aac").key(key)));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            manager.start(PlaySpec.from("episode", "https://example.test/first.mp4",
                    Collections.emptyMap(), null), 60_000);
            engine.playerHandler.listener.onTracksChanged(audioTracks());
            manager.stop();
            lookups.get(0).run();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(0, engine.trackSetCount);
    }

    private static Tracks audioTracks() {
        TrackGroup group = new TrackGroup(new Format.Builder().setSampleMimeType("audio/mp4a-latm").build());
        return new Tracks(ImmutableList.of(new Tracks.Group(group, false,
                new int[]{C.FORMAT_HANDLED}, new boolean[]{true})));
    }

    private static final class RecordingCallback implements PlayerManager.Callback {
        int prepareCount;
        int errorCount;
        int rebuildCount;
        int trackCount;
        Runnable tracksAction;

        @Override public void onPrepare() { prepareCount++; }
        @Override public void onTracksChanged() {
            trackCount++;
            if (tracksAction != null) tracksAction.run();
        }
        @Override public void onTitlesChanged() {}
        @Override public void onError(String msg) { errorCount++; }
        @Override public void onPlayerRebuild(Player newPlayer) { rebuildCount++; }
    }

    private static final class FakeEngine implements PlayerEngine {
        final PlayerHandler playerHandler = new PlayerHandler();
        final Player player = playerHandler.create();
        Player.Listener listener;
        PlaySpec startedSpec;
        ErrorAction errorAction = ErrorAction.FATAL;
        boolean hard = true;
        int startCount;
        int trackSetCount;
        int releaseCount;
        int stateOnStart = Player.STATE_IDLE;

        @Override public Player getPlayer() { return player; }
        @Override public void release() { releaseCount++; }
        @Override public Player rebuild(Player.Listener listener) { this.listener = listener; return player; }
        @Override public int getDecode() { return hard ? HARD : SOFT; }
        @Override public void setDecode(int decode) { hard = decode == HARD; }
        @Override public boolean isHard() { return hard; }
        @Override public String getDecodeText() { return hard ? "Hard" : "Soft"; }
        @Override public void start(PlaySpec spec) {
            startedSpec = spec;
            startCount++;
            if (stateOnStart != Player.STATE_IDLE && playerHandler.listener != null) {
                playerHandler.listener.onPlaybackStateChanged(stateOnStart);
            }
        }
        @Override public void setMetadata(MediaMetadata data) {}
        @Override public long pendingRetryDelayMs() { return 0L; }
        @Override public void retryAfterError() {}
        @Override public boolean isLive() { return false; }
        @Override public boolean isVod() { return true; }
        @Override public void setTrack(List<Track> tracks) { trackSetCount++; }
        @Override public void resetTrack() {}
        @Override public boolean haveTrack(int type) { return false; }
        @Override public Tracks getCurrentTracks() { return Tracks.EMPTY; }
        @Override public String getErrorMessage(PlaybackException e) { return e.getMessage(); }
        @Override public ErrorAction handleError(PlaybackException e) { return errorAction; }
    }

    private static final class PlayerHandler implements java.lang.reflect.InvocationHandler {
        Player.Listener listener;
        PlaybackParameters parameters = PlaybackParameters.DEFAULT;
        long position;
        int repeatMode;
        int playCount;
        int pauseCount;
        int stopCount;

        Player create() {
            return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                    new Class<?>[]{Player.class}, this);
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            return switch (method.getName()) {
                case "addListener" -> { listener = (Player.Listener) args[0]; yield null; }
                case "removeListener" -> { if (listener == args[0]) listener = null; yield null; }
                case "getApplicationLooper" -> Looper.getMainLooper();
                case "getPlaybackParameters" -> parameters;
                case "setPlaybackParameters" -> { parameters = (PlaybackParameters) args[0]; yield null; }
                case "isCommandAvailable" -> true;
                case "play" -> { playCount++; yield null; }
                case "pause" -> { pauseCount++; yield null; }
                case "stop" -> { stopCount++; yield null; }
                case "seekTo" -> { if (args.length == 1) position = (long) args[0]; yield null; }
                case "getCurrentPosition" -> position;
                case "setRepeatMode" -> { repeatMode = (int) args[0]; yield null; }
                case "getRepeatMode" -> repeatMode;
                case "getPlaybackState" -> Player.STATE_READY;
                case "getCurrentTracks" -> Tracks.EMPTY;
                case "isPlaying", "isCurrentMediaItemLive" -> false;
                case "getDuration" -> 60_000L;
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "FakePlayer";
                default -> defaultValue(method.getReturnType());
            };
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) return null;
            if (type == boolean.class) return false;
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0f;
            if (type == double.class) return 0d;
            if (type == char.class) return (char) 0;
            return null;
        }
    }
}
