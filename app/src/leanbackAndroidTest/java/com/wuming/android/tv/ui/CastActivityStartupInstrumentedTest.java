package com.wuming.android.tv.ui;

import static org.junit.Assert.*;

import android.content.Intent;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.wuming.android.tv.dlna.CastAction;
import com.wuming.android.tv.player.PlayerManager;
import com.wuming.android.tv.ui.activity.CastActivity;
import com.wuming.android.tv.ui.base.PlaybackActivity;
import com.wuming.tv.core.playback.renderer.RendererProbeSamples;
import fi.iki.elonen.NanoHTTPD;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class CastActivityStartupInstrumentedTest {
    @Test public void newIntentsBeforePlayerBindingDeferAndPlayOnlyTheLatestRequest() throws Exception {
        byte[] sample = RendererProbeSamples.INSTANCE.resource("avc.mp4");
        assertNotNull(sample);
        AtomicBoolean firstFetched = new AtomicBoolean();
        AtomicBoolean latestFetched = new AtomicBoolean();
        NanoHTTPD server = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession session) {
                if (session.getUri().equals("/first.mp4")) firstFetched.set(true);
                if (session.getUri().equals("/latest.mp4")) latestFetched.set(true);
                return newFixedLengthResponse(Response.Status.OK, "video/mp4", new ByteArrayInputStream(sample), sample.length);
            }
        };
        server.start(5000, true);
        String base = "http://127.0.0.1:" + server.getListeningPort();
        Field service = PlaybackActivity.class.getDeclaredField("mService");
        Field player = PlaybackActivity.class.getDeclaredField("mPlayer");
        Field action = CastActivity.class.getDeclaredField("mAction");
        service.setAccessible(true); player.setAccessible(true); action.setAccessible(true);
        Method newIntent = CastActivity.class.getDeclaredMethod("onNewIntent", Intent.class);
        Method connected = CastActivity.class.getDeclaredMethod("onServiceConnected");
        newIntent.setAccessible(true); connected.setAccessible(true);
        try (ActivityScenario<CastActivity> scenario = TestActivityLauncher.launch(CastActivity.class)) {
            AtomicReference<PlayerManager> manager = new AtomicReference<>();
            long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
            while (manager.get() == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity(activity -> {
                    try { manager.set((PlayerManager) player.get(activity)); }
                    catch (Exception error) { throw new AssertionError(error); }
                });
                if (manager.get() == null) Thread.sleep(100);
            }
            assertNotNull("Playback service did not bind", manager.get());
            scenario.onActivity(activity -> {
                try {
                    Object boundService = service.get(activity);
                    Object boundPlayer = player.get(activity);
                    try {
                        // Reproduce the pre-bind state deterministically on the UI thread.
                        // Android callbacks cannot reconnect the fields midway through this block.
                        service.set(activity, null); player.set(activity, null);
                        newIntent.invoke(activity, request(base + "/first.mp4"));
                        newIntent.invoke(activity, request(base + "/latest.mp4"));
                        assertNull("A request started before the player was ready", action.get(activity));
                        assertFalse(activity.isFinishing());
                    } finally {
                        service.set(activity, boundService); player.set(activity, boundPlayer);
                    }
                    connected.invoke(activity);
                    assertEquals(base + "/latest.mp4", ((CastAction) action.get(activity)).getCurrentURI());
                    assertEquals(base + "/latest.mp4", manager.get().getKey());
                } catch (Exception error) { throw new AssertionError(error); }
            });
            deadline = android.os.SystemClock.elapsedRealtime() + 15000;
            while (!latestFetched.get() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
            assertTrue("Deferred request never reached playback", latestFetched.get());
            assertFalse("Superseded request was played", firstFetched.get());
        } finally { server.stop(); }
    }

    private static Intent request(String url) {
        return new Intent(ApplicationProvider.getApplicationContext(), CastActivity.class)
            .putExtra(CastAction.KEY_EXTRA, new CastAction(url, "", Collections.emptyMap()));
    }
}
