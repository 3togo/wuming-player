package com.wuming.android.tv.cloud.opendrive;

import android.app.Activity;
import android.content.Intent;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.wuming.android.tv.BuildConfig;
import com.wuming.android.tv.R;
import com.wuming.android.tv.player.PlayerManager;
import com.wuming.android.tv.player.engine.PlaySpec;
import com.wuming.android.tv.ui.dialog.InfoDialog;
import com.wuming.tv.core.playback.OpenDrivePlaybackHints;
import fi.iki.elonen.NanoHTTPD;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;

/** Isolated on-device smoke checks: no cloud credentials, saves, or deletions. */
@RunWith(AndroidJUnit4.class)
public class OpenDriveBatchSmokeInstrumentedTest {
    @Test public void streamDialogReadsBytesAndCanRunAgain() throws Exception {
        try (Fixture server = new Fixture(false); ActivityScenario<Activity> scenario = launch()) {
            scenario.onActivity(activity -> InfoDialog.create(activity).title("Cloud stream smoke")
                    .headers(Map.of()).url(server.url()).show());
            onView(withText(R.string.cloud_check_stream)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());
            awaitText(R.string.cloud_check_bytes);
            capture("stream-check");
            onView(withText(R.string.cloud_check_stream)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());
            awaitText(R.string.cloud_check_bytes);
            pressBack();
        }
    }

    @Test public void backCancelsPendingStreamCheckAndReleasesCopyPin() throws Exception {
        String fid = "smoke-cancel-copy";
        String token = OpenDriveCopyUsage.stage("uc", "smoke-account", fid);
        try (Fixture server = new Fixture(true); ActivityScenario<Activity> scenario = launch()) {
            scenario.onActivity(activity -> InfoDialog.create(activity).title("Cancellation smoke")
                    .headers(Map.of(OpenDrivePlaybackHints.COPY_LEASE, token)).url(server.url()).show());
            onView(withText(R.string.cloud_check_stream)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());
            assertTrue(server.entered.await(5, TimeUnit.SECONDS));
            OpenDriveCopyUsage.retire("uc", fid);
            assertTrue(OpenDriveCopyUsage.busy("uc", fid));
            pressBack();
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertFalse(OpenDriveCopyUsage.busy("uc", fid));
        } finally { OpenDriveCopyUsage.retire("uc", fid); }
    }

    @Test public void decodedPlaybackProtectsCopyUntilPlayerStops() throws Exception {
        String fid = "smoke-decoder-copy";
        String token = OpenDriveCopyUsage.stage("alipan", "smoke-account", fid);
        var firstFrame = new CountDownLatch(1);
        var errors = new AtomicReference<String>();
        var manager = new AtomicReference<PlayerManager>();
        PlayerManager.Callback callback = new PlayerManager.Callback() {
            public void onPrepare() { }
            public void onTracksChanged() { }
            public void onTitlesChanged() { }
            public void onError(String message) { errors.set(message); }
            public void onPlayerRebuild(Player player) { }
        };
        try (Fixture server = new Fixture(false); ActivityScenario<Activity> scenario = launch()) {
            try {
                scenario.onActivity(activity -> {
                    PlayerManager player = new PlayerManager(callback); manager.set(player);
                    PlayerView view = new PlayerView(activity);
                    ((FrameLayout) activity.findViewById(android.R.id.custom)).addView(view,
                            new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    view.setPlayer(player.getPlayer());
                    player.getPlayer().addListener(new Player.Listener() {
                        @Override public void onRenderedFirstFrame() { firstFrame.countDown(); }
                    });
                    player.start(PlaySpec.from("cloud-smoke", server.url(),
                            Map.of(OpenDrivePlaybackHints.COPY_LEASE, token), MediaMetadata.EMPTY), 15000);
                });
                assertTrue("No decoded first frame", firstFrame.await(15, TimeUnit.SECONDS));
                OpenDriveCopyUsage.retire("alipan", fid);
                assertTrue("Playing copy lost protection", OpenDriveCopyUsage.busy("alipan", fid));
                SystemClock.sleep(1200);
                scenario.onActivity(activity -> {
                    assertTrue(manager.get().getPlayer().getCurrentPosition() > 500);
                    assertTrue(manager.get().getPlayer().getVideoSize().width > 0);
                });
                assertNull(errors.get()); capture("decoded-frame");
                scenario.onActivity(activity -> manager.get().stop());
                assertFalse("Stopped player retained active pin", OpenDriveCopyUsage.busy("alipan", fid));
            } finally {
                scenario.onActivity(activity -> { if (manager.get() != null) manager.get().release(); });
            }
        } finally { OpenDriveCopyUsage.retire("alipan", fid); }
    }

    private static ActivityScenario<Activity> launch() {
        assumeTrue(BuildConfig.APPLICATION_ID.endsWith(".verification"));
        return ActivityScenario.launch(new Intent().setClassName(ApplicationProvider.getApplicationContext(),
                "com.wuming.android.tv.ui.activity.MemoryTestActivity"));
    }
    private static void awaitText(int text) {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        AssertionError last = null;
        do {
            try { onView(withId(R.id.diagnostic)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(matches(withText(text))); return; }
            catch (AssertionError pending) { last = pending; SystemClock.sleep(100); }
        } while (SystemClock.elapsedRealtime() < deadline);
        throw last;
    }
    private static void capture(String name) throws Exception {
        var bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        var directory = new java.io.File(ApplicationProvider.getApplicationContext().getExternalFilesDir(null), "cloud-smoke");
        directory.mkdirs();
        try (var output = new java.io.FileOutputStream(new java.io.File(directory, name + ".png"))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        } finally { bitmap.recycle(); }
    }
    private static final class Fixture extends NanoHTTPD implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final boolean blocked;
        final byte[] media;
        Fixture(boolean blocked) throws Exception {
            super("127.0.0.1", 0); this.blocked = blocked;
            try (var input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("cloud-smoke.mp4")) {
                media = input.readAllBytes();
            }
            start(SOCKET_READ_TIMEOUT, false);
        }
        String url() { return "http://127.0.0.1:" + getListeningPort() + "/sample.mp4"; }
        @Override public Response serve(IHTTPSession session) {
            entered.countDown();
            if (blocked) try { release.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return newFixedLengthResponse(Response.Status.OK, "video/mp4", new ByteArrayInputStream(media), media.length);
        }
        public void close() { release.countDown(); stop(); }
    }
}
