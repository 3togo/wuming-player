package com.wuming.android.tv.player;

import static org.junit.Assert.*;

import android.content.Intent;
import android.os.SystemClock;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.wuming.android.tv.api.SiteApi;
import com.wuming.android.tv.R;
import com.wuming.android.tv.ui.activity.VideoActivity;
import com.wuming.android.tv.ui.base.PlaybackActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/** Explicit opt-in: resolves an exact bundled live channel and observes real decoded playback. */
@RunWith(AndroidJUnit4.class)
public class LiveChannelSmokeInstrumentedTest {
    @Test public void bundledLiveChannelRendersAndAdvances() throws Exception {
        String name = InstrumentationRegistry.getArguments().getString("live_channel");
        assertNotNull("Explicit live_channel argument required", name);
        android.net.ConnectivityManager connectivity = ApplicationProvider.getApplicationContext()
                .getSystemService(android.net.ConnectivityManager.class);
        assertNotNull("No active device network for the real-provider smoke", connectivity.getActiveNetwork());
        String url = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                ApplicationProvider.getApplicationContext().getAssets()
                        .open("live/public_samples.m3u8"), java.nio.charset.StandardCharsets.UTF_8))) {
            boolean selected = false;
            for (String line; (line = reader.readLine()) != null;) {
                if (line.startsWith("#EXTINF:")) selected = line.substring(line.lastIndexOf(',') + 1).equals(name);
                else if (selected && line.startsWith("http")) { url = line; break; }
            }
        }
        assertNotNull("Channel not found in bundled playlist", url);
        assertFalse("Direct playback route discarded the supplied URL",
                SiteApi.playerContent(SiteApi.PUSH, "推送", url).getUrl().isEmpty());
        Method accessor = PlaybackActivity.class.getDeclaredMethod("player");
        accessor.setAccessible(true);
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), VideoActivity.class)
                .putExtra("key", SiteApi.PUSH).putExtra("id", url)
                .putExtra("name", "Live provider smoke: " + name).putExtra("collect", false);
        int seconds = Integer.parseInt(InstrumentationRegistry.getArguments().getString("live_seconds", "15"));
        assertTrue("Observation must be 15–300 seconds", seconds >= 15 && seconds <= 300);
        PlaybackSmokeProbe probe = new PlaybackSmokeProbe();
        String outcome = "failed";
        try (ActivityScenario<VideoActivity> scenario = ActivityScenario.launch(intent)) {
            long deadline = SystemClock.elapsedRealtime() + 60_000;
            while (SystemClock.elapsedRealtime() < deadline) {
                sample(scenario, accessor, probe);
                probe.save("starting");
                assertFalse("Activity displayed a playback error", probe.uiError);
                assertNull("Player error code", probe.errorCode);
                if (probe.frames >= 30 && probe.width > 0 && probe.height > 0 && probe.playing) break;
                SystemClock.sleep(250);
            }
            assertTrue("No decoded video within 60 seconds", probe.frames >= 30 && probe.playing);
            long initialProgress = probe.diagnostics.getProgressMillis();
            long initialFrames = probe.diagnostics.getRenderedFrames();
            long started = SystemClock.elapsedRealtime();
            while (SystemClock.elapsedRealtime() - started < seconds * 1000L) {
                SystemClock.sleep(1000);
                sample(scenario, accessor, probe);
                probe.save("observing");
                assertFalse("Activity displayed a playback error", probe.uiError);
                assertNull("Player error code", probe.errorCode);
            }
            assertTrue("Observed playback did not advance enough", probe.diagnostics.getProgressMillis()
                    - initialProgress >= Math.max(10_000, seconds * 600L));
            assertTrue("Decoder did not continue rendering", probe.diagnostics.getRenderedFrames() - initialFrames >= 60);
            assertTrue("Playback stalled for eight seconds; inspect diagnostic samples", probe.diagnostics.getMaxStallMillis() < 8000);
            assertTrue("Video stopped playing", probe.playing);
            outcome = "passed";
        } finally { probe.save(outcome); }
    }

    static void sample(ActivityScenario<VideoActivity> scenario, Method accessor, PlaybackSmokeProbe probe) {
        scenario.onActivity(activity -> {
            try {
                android.view.View errorView = activity.findViewById(R.id.error);
                probe.uiError = errorView != null && errorView.isShown();
                PlayerManager manager = (PlayerManager) accessor.invoke(activity);
                if (manager != null && manager.getPlayer() instanceof ExoPlayer player) probe.sample(player);
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
    }
}
