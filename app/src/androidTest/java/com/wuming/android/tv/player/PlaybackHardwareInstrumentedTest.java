package com.wuming.android.tv.player;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Debug;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.view.WindowManager;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.ui.PlayerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.wuming.android.tv.player.engine.PlaySpec;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileWriter;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in real decoder/render/seek/release exercise. Supply playbackFixture as a local 8K HEVC URI. */
@RunWith(AndroidJUnit4.class)
public class PlaybackHardwareInstrumentedTest {
    @Test public void eightKPlaybackAndRepeatedRelease() throws Exception {
        String uri = InstrumentationRegistry.getArguments().getString("playbackFixture");
        assumeTrue("Supply playbackFixture for the hardware test", uri != null);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONObject report = new JSONObject();
        JSONArray samples = new JSONArray();
        JSONArray cycles = new JSONArray();
        report.put("fixture", uri).put("heapLimitBytes", Runtime.getRuntime().maxMemory())
                .put("codecs", codecs()).put("samples", samples).put("cycles", cycles);
        AtomicReference<PlayerManager> manager = new AtomicReference<>();
        AtomicReference<PlayerView> view = new AtomicReference<>();
        long warmHeap = 0;
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(new Intent().setClassName(context,
                "com.wuming.android.tv.ui.activity.MemoryTestActivity"))) {
            scenario.onActivity(activity -> {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                PlayerView surface = new PlayerView(activity);
                surface.setUseController(false);
                ((ViewGroup) activity.findViewById(android.R.id.custom)).addView(surface,
                        new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                view.set(surface);
            });
            try {
                for (int cycle = 0; cycle < 10; cycle++) {
                    PlaybackProbe probe = new PlaybackProbe();
                    long start = SystemClock.elapsedRealtime();
                    scenario.onActivity(activity -> {
                        PlayerManager current = new PlayerManager(null);
                        manager.set(current);
                        ExoPlayer engine = (ExoPlayer) current.getPlayer();
                        engine.addAnalyticsListener(probe);
                        view.get().setPlayer(engine);
                        current.setRepeatOne(true);
                        current.start(PlaySpec.from("hardware-8k", uri, Collections.emptyMap(), null), 30_000);
                    });
                    long deadline = start + 30_000;
                    while (!probe.firstFrame && probe.error == null && SystemClock.elapsedRealtime() < deadline) {
                        SystemClock.sleep(100);
                    }
                    assertNull("Decoder failed: " + probe.error, probe.error);
                    assertTrue("No rendered frame within 30 seconds", probe.firstFrame);
                    long firstFrameMs = SystemClock.elapsedRealtime() - start;
                    long playingUntil = SystemClock.elapsedRealtime() + (cycle == 0 ? 90_000 : 6_000);
                    boolean seekDone = false;
                    while (SystemClock.elapsedRealtime() < playingUntil) {
                        SystemClock.sleep(2_000);
                        AtomicReference<JSONObject> sample = new AtomicReference<>();
                        scenario.onActivity(activity -> sample.set(snapshot((ExoPlayer) manager.get().getPlayer())));
                        samples.put(sample.get().put("cycle", cycle));
                        assertNull("Playback failed: " + probe.error, probe.error);
                        if (!seekDone) {
                            scenario.onActivity(activity -> manager.get().seekTo(5_000));
                            seekDone = true;
                        }
                    }
                    AtomicReference<JSONObject> stats = new AtomicReference<>();
                    scenario.onActivity(activity -> {
                        ExoPlayer engine = (ExoPlayer) manager.get().getPlayer();
                        assertEquals(7680, engine.getVideoSize().width);
                        assertEquals(4320, engine.getVideoSize().height);
                        assertTrue(engine.isPlaying());
                        stats.set(snapshot(engine));
                        view.get().setPlayer(null);
                        manager.get().release();
                        manager.get().release();
                        assertNull(manager.get().getPlayer());
                        manager.set(null);
                    });
                    JSONObject statsValue = stats.get().put("cycle", cycle).put("firstFrameMs", firstFrameMs)
                            .put("decoder", probe.decoder).put("decoderInitializations", probe.initializations);
                    cycles.put(statsValue);
                    assertTrue("8K must render through a hardware decoder: " + probe.decoder,
                            probe.decoder != null && !probe.decoder.startsWith("c2.android.")
                                    && !probe.decoder.startsWith("OMX.google."));
                    assertTrue("Decoder was repeatedly rebuilt", probe.initializations <= 2);
                    int rendered = statsValue.getInt("renderedFrames");
                    int dropped = statsValue.getInt("droppedFrames");
                    assertTrue("Too few rendered frames: " + rendered, rendered >= (cycle == 0 ? 1000 : 60));
                    assertTrue("Dropped frame ratio exceeds 5%: " + dropped + "/" + rendered,
                            dropped <= (rendered + dropped) * 0.05 + 3);
                    System.gc();
                    SystemClock.sleep(400);
                    long heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                    statsValue.put("releasedJavaHeapBytes", heap).put("releasedNativeHeapBytes", Debug.getNativeHeapAllocatedSize());
                    if (cycle == 0) warmHeap = heap;
                    if (cycle == 9) assertTrue("Java heap grew by more than 32 MiB after repeated release",
                            heap - warmHeap < 32L * 1024 * 1024);
                }
                report.put("result", "PASS");
            } finally {
                scenario.onActivity(activity -> {
                    view.get().setPlayer(null);
                    if (manager.get() != null) manager.get().release();
                });
            }
        } finally {
            if (!report.has("result")) report.put("result", "FAIL");
            try (FileWriter writer = new FileWriter(new File(context.getExternalFilesDir(null), "playback-hardware.json"))) {
                writer.write(report.toString(2));
            }
        }
    }

    private static JSONObject snapshot(ExoPlayer player) {
        try {
            DecoderCounters counters = player.getVideoDecoderCounters();
            if (counters != null) counters.ensureUpdated();
            Debug.MemoryInfo memory = new Debug.MemoryInfo();
            Debug.getMemoryInfo(memory);
            return new JSONObject().put("elapsedMs", SystemClock.elapsedRealtime())
                    .put("positionMs", player.getCurrentPosition()).put("bufferedMs", player.getTotalBufferedDuration())
                    .put("state", player.getPlaybackState()).put("pssKb", memory.getTotalPss())
                    .put("javaHeapBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                    .put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize())
                    .put("renderedFrames", counters == null ? 0 : counters.renderedOutputBufferCount)
                    .put("droppedFrames", counters == null ? 0 : counters.droppedBufferCount)
                    .put("maxConsecutiveDroppedFrames", counters == null ? 0 : counters.maxConsecutiveDroppedBufferCount);
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private static JSONArray codecs() throws Exception {
        JSONArray result = new JSONArray();
        for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (codec.isEncoder()) continue;
            for (String type : codec.getSupportedTypes()) {
                if (!type.equals("video/hevc")) continue;
                MediaCodecInfo.VideoCapabilities caps = codec.getCapabilitiesForType(type).getVideoCapabilities();
                result.put(new JSONObject().put("name", codec.getName())
                        .put("supports8k30", caps.areSizeAndRateSupported(7680, 4320, 30)));
            }
        }
        return result;
    }

    private static final class PlaybackProbe implements AnalyticsListener {
        volatile boolean firstFrame;
        volatile String decoder;
        volatile String error;
        volatile int initializations;
        @Override public void onRenderedFirstFrame(EventTime time, Object output, long renderTimeMs) { firstFrame = true; }
        @Override public void onVideoDecoderInitialized(EventTime time, String name, long atMs, long durationMs) {
            decoder = name;
            initializations++;
        }
        @Override public void onPlayerError(EventTime time, PlaybackException failure) {
            error = failure.getErrorCodeName() + ": " + failure.getMessage();
        }
    }
}
