package com.wuming.android.tv.api.projection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wuming.android.tv.bean.CastVideo;
import com.wuming.android.tv.bean.Device;
import com.wuming.android.tv.dlna.DLNACastManager;
import com.wuming.android.tv.dlna.DlnaCastBackend;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class DlnaTransportIntegrationInstrumentedTest {

    @Test
    public void realBackendPlaysSeeksAndStopsLanRenderer() throws Exception {
        String base = InstrumentationRegistry.getArguments()
                .getString("fake_renderer_base", "");
        Assume.assumeTrue("LAN DLNA transport test is opt-in", !base.isEmpty());

        DlnaCastBackend backend = new DlnaCastBackend();
        CountDownLatch discovered = new CountDownLatch(1);
        AtomicReference<Device> renderer = new AtomicReference<>();
        CastBackend.Session session = null;
        try {
            backend.startDiscovery(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                    new CastBackend.DiscoveryListener() {
                        @Override
                        public void onDeviceAdded(Device device) {
                            if (!"FakeRenderer-Test".equals(device.getName())) return;
                            renderer.set(device);
                            discovered.countDown();
                        }

                        @Override
                        public void onDeviceRemoved(Device device) {
                        }
                    });
            assertTrue("Fake renderer was not discovered", discovered.await(15, TimeUnit.SECONDS));
            assertNotNull(renderer.get());

            CountDownLatch playing = new CountDownLatch(1);
            CountDownLatch failed = new CountDownLatch(1);
            session = backend.createSession(new CastVideo("DLNA integration",
                            base + "/test/stream.m3u8", 0L, Map.of()),
                    playing::countDown, null, failed::countDown);
            session.start(renderer.get());
            assertTrue("Play callback was not received",
                    playing.await(10, TimeUnit.SECONDS));
            assertEquals(1L, failed.getCount());
            assertEquals("PLAYING", awaitState(base, "transport", "PLAYING", 5).getString("transport"));
            assertTrue("Renderer did not fetch the HLS fixture",
                    awaitFetchVerdict(base, "HLS_PLAYING", 5));

            session.seek(42_000L);
            JSONObject sought = awaitPosition(base, 42.0, 5);
            assertTrue(sought.getDouble("position") >= 42.0);

            session.stop();
            assertEquals("STOPPED", awaitState(base, "transport", "STOPPED", 5)
                    .getString("transport"));
        } finally {
            if (session != null) session.release();
            backend.stopDiscovery();
            DLNACastManager.get().release(
                    InstrumentationRegistry.getInstrumentation().getTargetContext());
        }
    }

    private static JSONObject awaitState(String base, String key, String expected,
                                         int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        JSONObject state;
        do {
            state = state(base);
            if (expected.equals(state.optString(key))) return state;
            Thread.sleep(100L);
        } while (System.nanoTime() < deadline);
        return state;
    }

    private static JSONObject awaitPosition(String base, double expected,
                                            int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        JSONObject state;
        do {
            state = state(base);
            if (state.optDouble("position", -1.0) >= expected) return state;
            Thread.sleep(100L);
        } while (System.nanoTime() < deadline);
        return state;
    }

    private static JSONObject state(String base) throws Exception {
        return json(base + "/debug/state");
    }

    private static boolean awaitFetchVerdict(String base, String expected,
                                             int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        do {
            JSONArray reports = json(base + "/debug/report").getJSONArray("fetch_reports");
            for (int index = 0; index < reports.length(); index++) {
                if (expected.equals(reports.getJSONObject(index).optString("verdict"))) return true;
            }
            Thread.sleep(100L);
        } while (System.nanoTime() < deadline);
        return false;
    }

    private static JSONObject json(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(2_000);
        try (InputStream input = connection.getInputStream()) {
            return new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
    }
}
