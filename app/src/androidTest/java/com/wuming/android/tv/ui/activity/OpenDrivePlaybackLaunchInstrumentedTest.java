package com.wuming.android.tv.ui.activity;

import static org.junit.Assume.assumeTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wuming.android.tv.api.SiteApi;
import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.bean.Vod;
import com.wuming.android.tv.cloud.opendrive.OpenDriveDeviceFixtures;
import com.wuming.tv.core.cloud.OpenDrivePlayback;
import com.wuming.android.tv.cloud.opendrive.OpenDriveRuntime;
import com.wuming.android.tv.cloud.opendrive.OpenDriveSpider;
import com.wuming.tv.core.media.MediaSourceDescriptor;
import com.wuming.tv.core.media.OpenDriveUri;
import com.wuming.tv.core.media.catalog.MediaManifest;
import com.wuming.android.tv.media.catalog.RoomMediaCatalog;
import com.wuming.android.tv.player.PlayerManager;
import com.wuming.android.tv.server.Server;
import com.wuming.android.tv.ui.base.PlaybackActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

import okhttp3.Request;
import okhttp3.Response;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Collections;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/** Manual device smoke launcher; skipped unless a catalog URI is supplied. */
@RunWith(AndroidJUnit4.class)
public class OpenDrivePlaybackLaunchInstrumentedTest {

    private static final String TAG = "OpenDrivePlaybackSmoke";

    @Test
    public void launchConfiguredUcHistory() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String sourceName = arguments.getString("source_name", "玩偶哥哥");
        String query = arguments.getString("query", "野狗骨头");
        VodConfig.get().ensureLoaded();
        Site source = VodConfig.get().getSites().stream()
                .filter(item -> item.getName().contains(sourceName))
                .findFirst().orElse(null);
        assumeTrue(source != null);
        Vod match = SiteApi.searchContent(source, query, false, "1").getList().stream()
                .filter(item -> item.getVodName().contains(query))
                .findFirst().orElse(null);
        assumeTrue(match != null);
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), VideoActivity.class)
                .putExtra("collect", false)
                .putExtra("name", match.getVodName())
                .putExtra("pic", match.getVodPic())
                .putExtra("key", source.getKey())
                .putExtra("id", match.getVodId());
        try (ActivityScenario<VideoActivity> ignored = ActivityScenario.launch(intent)) {
            SystemClock.sleep(waitMillis(arguments));
        }
    }

    @Test
    public void launchPersistedOpenDriveUri() {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String mediaUri = mediaUri(arguments);
        assumeTrue(OpenDriveUri.isValid(mediaUri));
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), VideoActivity.class)
                .putExtra("collect", false)
                .putExtra("name", "OpenDrive device smoke test")
                .putExtra("key", SiteApi.PUSH)
                .putExtra("id", mediaUri);
        try (ActivityScenario<VideoActivity> ignored = ActivityScenario.launch(intent)) {
            SystemClock.sleep(waitMillis(arguments));
        }
    }

    @Test
    public void persistedOpenDriveUriRendersAndAdvancesVideo() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        assumeTrue("true".equalsIgnoreCase(arguments.getString("verify_playback", "false")));
        String mediaUri = mediaUri(arguments);
        assumeTrue(OpenDriveUri.isValid(mediaUri));
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), VideoActivity.class)
                .putExtra("collect", false)
                .putExtra("name", "OpenDrive provider playback smoke")
                .putExtra("key", SiteApi.PUSH)
                .putExtra("id", mediaUri);
        try (ActivityScenario<VideoActivity> scenario = ActivityScenario.launch(intent)) {
            long readyDeadline = SystemClock.elapsedRealtime() + 45_000L;
            PlaybackSample ready = PlaybackSample.empty();
            while (SystemClock.elapsedRealtime() < readyDeadline) {
                SystemClock.sleep(250L);
                ready = sample(scenario);
                assertTrue("Playback error: " + ready.error, ready.error.isBlank());
                if (ready.state == Player.STATE_READY && ready.playing
                        && ready.width > 0 && ready.height > 0) break;
            }
            assertEquals(Player.STATE_READY, ready.state);
            assertTrue(ready.playing);
            assertTrue(ready.width > 0 && ready.height > 0);
            long startPosition = ready.position;
            long maximumPosition = startPosition;
            long deadline = SystemClock.elapsedRealtime() + 15_000L;
            PlaybackSample latest = ready;
            while (SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(1_000L);
                latest = sample(scenario);
                assertTrue("Playback error: " + latest.error, latest.error.isBlank());
                assertTrue(latest.width > 0 && latest.height > 0);
                maximumPosition = Math.max(maximumPosition, latest.position);
            }
            assertTrue("Playback did not advance", maximumPosition - startPosition >= 5_000L);
            capture(arguments.getString("provider", "provider") + "-passed");
            Log.i(TAG, "phase=passed,provider=" + arguments.getString("provider", "")
                    + ",video=" + latest.width + "x" + latest.height
                    + ",positionDelta=" + (maximumPosition - startPosition)
                    + ",bufferedAhead=" + Math.max(0L, latest.bufferedPosition
                    - latest.position));
        }
    }

    @Test
    public void resolvePersistedOpenDriveUri() throws Exception {
        String mediaUri = mediaUri(InstrumentationRegistry.getArguments());
        assumeTrue(OpenDriveUri.isValid(mediaUri));
        MediaManifest manifest = new RoomMediaCatalog().find(OpenDriveUri.parse(mediaUri)).orElseThrow();
        MediaSourceDescriptor source = manifest.sources().stream()
                .filter(item -> "org.opendrive.provider".equals(item.adapterId()))
                .findFirst().orElseThrow();
        // Proxy-backed providers keep their short-lived stream handles in the process-wide
        // runtime. A throwaway engine resolves a URL whose handle the local proxy cannot see.
        VodConfig.get().ensureLoaded();
        Server.get().start();
        OpenDrivePlayback playback = OpenDriveRuntime.engine().resolve(
                source.locator().get("provider"), source.locator().get("handle"));
        Request.Builder request = new Request.Builder().url(playback.url());
        playback.headers().forEach(request::header);
        request.header("Range", "bytes=0-65535");
        try (Response response = new okhttp3.OkHttpClient().newCall(request.build()).execute()) {
            assertTrue("Playback range returned HTTP " + response.code(),
                    response.isSuccessful());
            assertNotNull(response.body());
            assertFalse(response.peekBody(65_536).string().isBlank());
        }
    }

    @Test
    public void siteApiRoutesPersistedOpenDriveUri() throws Exception {
        String mediaUri = mediaUri(InstrumentationRegistry.getArguments());
        assumeTrue(OpenDriveUri.isValid(mediaUri));

        Result result = SiteApi.playerContent(SiteApi.PUSH, "OpenDrive", mediaUri);

        assertTrue(result.getMsg().isBlank());
        assertFalse(result.getRealUrl().isBlank());
        assertEquals(mediaUri, result.getMediaUri());
    }

    @Test
    public void spiderRoutesPersistedOpenDriveUri() throws Exception {
        String mediaUri = mediaUri(InstrumentationRegistry.getArguments());
        assumeTrue(OpenDriveUri.isValid(mediaUri));

        JsonObject result = JsonParser.parseString(new OpenDriveSpider().playerContent(
                "OpenDrive", mediaUri, Collections.emptyList())).getAsJsonObject();

        assertTrue(result.get("url").getAsString().startsWith("https://"));
    }

    private static String mediaUri(Bundle arguments) {
        String value = arguments.getString("mediaUri", "");
        String provider = arguments.getString("provider", "baidu");
        if (OpenDriveUri.isValid(value)) return value;
        return arguments.getString("refresh", "false").equalsIgnoreCase("true")
                ? OpenDriveDeviceFixtures.refreshMediaUri(provider)
                : OpenDriveDeviceFixtures.ensureMediaUri(provider);
    }

    private static long waitMillis(Bundle arguments) {
        try {
            return Math.max(1_000, Math.min(60_000,
                    Long.parseLong(arguments.getString("wait_ms", "15000"))));
        } catch (NumberFormatException ignored) {
            return 15_000;
        }
    }

    private static PlaybackSample sample(ActivityScenario<VideoActivity> scenario) {
        AtomicReference<PlaybackSample> result = new AtomicReference<>(PlaybackSample.empty());
        scenario.onActivity(activity -> {
            try {
                Method method = PlaybackActivity.class.getDeclaredMethod("player");
                method.setAccessible(true);
                PlayerManager manager = (PlayerManager) method.invoke(activity);
                View errorView = activity.findViewById(com.wuming.android.tv.R.id.error);
                String error = errorView instanceof TextView text
                        && errorView.getVisibility() == View.VISIBLE
                        ? text.getText().toString().trim() : "";
                if (manager == null || manager.getPlayer() == null) {
                    result.set(PlaybackSample.empty(error));
                    return;
                }
                VideoSize video = manager.getPlayer().getVideoSize();
                result.set(new PlaybackSample(manager.getPlaybackState(), video.width,
                        video.height, manager.getPosition(),
                        manager.getPlayer().getBufferedPosition(),
                        manager.getPlayer().isPlaying(), error));
            } catch (Exception error) {
                result.set(PlaybackSample.empty(error.getClass().getSimpleName()));
            }
        });
        return result.get();
    }

    private static void capture(String name) {
        Bitmap bitmap = null;
        try {
            bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .takeScreenshot();
            File directory = new File(ApplicationProvider.getApplicationContext()
                    .getExternalFilesDir(null), "opendrive-playback-smoke");
            if (!directory.exists() && !directory.mkdirs()) return;
            try (FileOutputStream output = new FileOutputStream(
                    new File(directory, name.replaceAll("[^a-zA-Z0-9._-]", "_") + ".png"))) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            }
        } catch (Exception error) {
            Log.w(TAG, "Unable to capture playback smoke", error);
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    private record PlaybackSample(int state, int width, int height, long position,
                                  long bufferedPosition, boolean playing, String error) {
        private static PlaybackSample empty() {
            return empty("");
        }

        private static PlaybackSample empty(String error) {
            return new PlaybackSample(Player.STATE_IDLE, 0, 0, 0L, 0L, false, error);
        }
    }
}
