package com.wuming.android.tv.cloud.opendrive;

import android.os.Bundle;
import android.os.SystemClock;
import android.view.ViewGroup;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.wuming.android.tv.player.PlayerManager;
import com.wuming.android.tv.player.engine.PlaySpec;
import com.wuming.android.tv.ui.activity.HomeActivity;
import com.wuming.tv.core.cloud.OpenDrivePlayback;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Opt-in real decoder check using the exact live-provider result, without another resolve. */
final class OpenDriveLiveDecoder {
    static void verify(OpenDrivePlayback playback, String provider, String route) throws Exception {
        int progressTarget = Math.max(3000, Math.min(30000, Integer.parseInt(
                InstrumentationRegistry.getArguments().getString("fixture_progress_ms", "3000"))));
        int observationLimit = Math.max(15000, Math.min(90000, Integer.parseInt(
                InstrumentationRegistry.getArguments().getString("fixture_observation_ms", "15000"))));
        var firstFrame = new CountDownLatch(1);
        var error = new AtomicReference<String>();
        var manager = new AtomicReference<PlayerManager>();
        var position = new long[2];
        var dimensions = new int[2];
        var state = new long[3];
        PlayerManager.Callback callback = new PlayerManager.Callback() {
            public void onPrepare() { }
            public void onTracksChanged() { }
            public void onTitlesChanged() { }
            public void onError(String message) { error.set("player-error"); }
            public void onPlayerRebuild(Player player) { }
        };
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            try {
                scenario.onActivity(activity -> {
                    var player = new PlayerManager(callback); manager.set(player);
                    var view = new PlayerView(activity);
                    activity.addContentView(view, new ViewGroup.LayoutParams(-1, -1));
                    view.setPlayer(player.getPlayer());
                    player.getPlayer().addListener(new Player.Listener() {
                        @Override public void onRenderedFirstFrame() { firstFrame.countDown(); }
                    });
                    player.start(PlaySpec.from("live-cloud-" + provider + "-" + route,
                            playback.url(), playback.headers(), MediaMetadata.EMPTY), 45000);
                });
                assertTrue("No decoded frame for " + provider + ":" + route,
                        firstFrame.await(45, TimeUnit.SECONDS));
                scenario.onActivity(activity -> position[0] = manager.get().getPlayer().getCurrentPosition());
                long observationStarted = SystemClock.elapsedRealtime();
                do {
                    SystemClock.sleep(1000);
                    scenario.onActivity(activity -> {
                        var player = manager.get().getPlayer();
                        position[1] = player.getCurrentPosition();
                        dimensions[0] = player.getVideoSize().width;
                        dimensions[1] = player.getVideoSize().height;
                        state[0] = player.getPlaybackState();
                        state[1] = player.getDuration();
                        state[2] = player.getBufferedPosition();
                    });
                } while (SystemClock.elapsedRealtime() - observationStarted < observationLimit
                        && position[1] - position[0] < progressTarget && error.get() == null);
                Bundle observation = new Bundle();
                observation.putString("observedRoute", provider + ":" + route);
                observation.putLong("observationMs", SystemClock.elapsedRealtime() - observationStarted);
                observation.putLong("positionStartMs", position[0]);
                observation.putLong("positionEndMs", position[1]);
                observation.putLong("durationMs", state[1]);
                observation.putLong("bufferedPositionMs", state[2]);
                observation.putLong("playbackState", state[0]);
                InstrumentationRegistry.getInstrumentation().sendStatus(0, observation);
                assertNull("Player error for " + provider + ":" + route, error.get());
                assertTrue("Playback did not advance for " + provider + ":" + route, position[1] - position[0] >= progressTarget - 1000);
                assertTrue("Missing video dimensions", dimensions[0] > 0 && dimensions[1] > 0);
                var bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
                var directory = new java.io.File(context.getExternalFilesDir(null), "live-cloud-smoke");
                directory.mkdirs();
                try (var output = new java.io.FileOutputStream(new java.io.File(directory, provider + "-" + route + ".png"))) {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
                } finally { bitmap.recycle(); }
                Bundle result = new Bundle();
                result.putString("decodedRoute", provider + ":" + route);
                result.putString("decodedSize", dimensions[0] + "x" + dimensions[1]);
                result.putLong("positionDeltaMs", position[1] - position[0]);
                InstrumentationRegistry.getInstrumentation().sendStatus(0, result);
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(
                        () -> { if (manager.get() != null) manager.get().release(); });
            }
        }
    }
}
