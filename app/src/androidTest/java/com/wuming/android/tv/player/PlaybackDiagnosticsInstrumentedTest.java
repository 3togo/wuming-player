package com.wuming.android.tv.player;

import static org.junit.Assert.*;
import android.os.SystemClock;
import android.view.ViewGroup;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.wuming.android.tv.ui.activity.MemoryTestActivity;
import fi.iki.elonen.NanoHTTPD;
import java.io.ByteArrayInputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PlaybackDiagnosticsInstrumentedTest {
    @Test public void loopbackStarvationAndPlayerResetRemainDistinct() throws Exception {
        CountDownLatch resume = new CountDownLatch(1);
        android.content.res.AssetManager assets = InstrumentationRegistry.getInstrumentation().getContext().getAssets();
        NanoHTTPD server = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession session) {
                try {
                    String path = session.getUri();
                    if (path.equals("/segment-01.ts")) resume.await(25, TimeUnit.SECONDS);
                    String asset = "playback-diagnostics" + path;
                    byte[] data;
                    try (java.io.InputStream input = assets.open(asset)) { data = input.readAllBytes(); }
                    return newFixedLengthResponse(Response.Status.OK, path.endsWith(".m3u8") ? "application/vnd.apple.mpegurl" : path.endsWith(".mp4") ? "video/mp4" : "video/mp2t",
                            new ByteArrayInputStream(data), data.length);
                } catch (Exception failure) { return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "fixture failure"); }
            }
        };
        PlaybackSmokeProbe probe = new PlaybackSmokeProbe();
        AtomicReference<ExoPlayer> player = new AtomicReference<>();
        AtomicReference<PlayerView> view = new AtomicReference<>();
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        String base = "http://127.0.0.1:" + server.getListeningPort();
        String outcome = "failed";
        try (ActivityScenario<MemoryTestActivity> scenario = ActivityScenario.launch(MemoryTestActivity.class)) {
            try {
                scenario.onActivity(activity -> {
                    PlayerView surface = new PlayerView(activity);
                    ((ViewGroup)activity.findViewById(android.R.id.custom)).addView(surface);
                    view.set(surface);
                    ExoPlayer engine = new ExoPlayer.Builder(activity).setLoadControl(new DefaultLoadControl.Builder()
                            .setBufferDurationsMs(500, 2000, 100, 100).build()).build();
                    player.set(engine);
                    surface.setPlayer(engine);
                    probe.sample(engine);
                    engine.setMediaItem(MediaItem.fromUri(base + "/fixture.m3u8"));
                    engine.prepare();
                    engine.play();
                });
                long deadline = SystemClock.elapsedRealtime() + 20_000;
                while (SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity(activity -> probe.sample(player.get()));
                    probe.save("observing-fixture");
                    assertNull("Fixture playback error", probe.errorCode);
                    if (probe.diagnostics.getStartupMillis() >= 0
                            && probe.diagnostics.getRebufferCount() > 0
                            && probe.diagnostics.getMaxStallMillis() >= 1000) break;
                    SystemClock.sleep(200);
                }
                assertTrue("Fixture must first render video", probe.diagnostics.getStartupMillis() >= 0);
                assertTrue("Delayed segment must rebuffer", probe.diagnostics.getRebufferCount() > 0);
                assertEquals("transport-starved", probe.diagnostics.getDiagnosis());
                int beforeReset = probe.diagnostics.getResetCount();
                resume.countDown();
                scenario.onActivity(activity -> {
                    player.get().stop();
                    player.get().clearMediaItems();
                    player.get().setMediaItem(MediaItem.fromUri(base + "/reset.mp4"));
                    player.get().prepare();
                    player.get().play();
                });
                deadline = SystemClock.elapsedRealtime() + 10_000;
                while (SystemClock.elapsedRealtime() < deadline && probe.diagnostics.getResetCount() == beforeReset) {
                    scenario.onActivity(activity -> probe.sample(player.get()));
                    SystemClock.sleep(100);
                }
                assertTrue("Reset must be distinct from transport starvation", probe.diagnostics.getResetCount() > beforeReset);
                // Cause details survive sampling/release and exclude private exception messages.
                PlaybackSmokeProbe contractProbe = new PlaybackSmokeProbe();
                contractProbe.onPlayerError(null, new androidx.media3.common.PlaybackException("token=private",
                        new java.io.IOException("https://private.example"),
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED));
                contractProbe.save("diagnostic-contract");
                String run = InstrumentationRegistry.getArguments().getString("smoke_run_id", "manual");
                java.io.File report = new java.io.File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                        .getCacheDir(), "smoke/" + run + ".json");
                String encoded = java.nio.file.Files.readString(report.toPath());
                assertTrue(encoded.contains("java.io.IOException"));
                assertFalse(encoded.contains("token=private"));
                assertFalse(encoded.contains("private.example"));
                outcome = "passed";
            } finally {
                resume.countDown();
                scenario.onActivity(activity -> {
                    if (view.get() != null) view.get().setPlayer(null);
                    if (player.get() != null) player.get().release();
                });
            }
        } finally { resume.countDown(); server.stop(); probe.save(outcome); }
    }
}
