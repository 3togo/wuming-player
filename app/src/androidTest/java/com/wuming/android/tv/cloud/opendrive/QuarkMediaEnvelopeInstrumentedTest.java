package com.wuming.android.tv.cloud.opendrive;

import com.wuming.tv.core.cloud.*;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import okhttp3.HttpUrl;

/** Opt-in probe for a PNG envelope around media; never logs capabilities or URLs. */
@RunWith(AndroidJUnit4.class)
public class QuarkMediaEnvelopeInstrumentedTest {

    private static final String TAG = "QuarkEnvelopeProbe";
    @Test
    public void currentExternalEpisodeIsNormalizedToStandardMp4() throws Exception {
        boolean enabled = Boolean.parseBoolean(InstrumentationRegistry.getArguments()
                .getString("run_quark_envelope_probe", "false"));
        Assume.assumeTrue("Quark envelope probe is opt-in", enabled);
        OpenDriveDeviceFixtures.ExternalFixture fixture =
                OpenDriveDeviceFixtures.externalEpisode("quark");
        Assume.assumeNotNull("No Quark external episode in app history", fixture);

        OpenDriveProvider provider = new OpenDriveEngine().provider("quark");
        Assume.assumeTrue("Quark is not authenticated", provider.authenticated());
        OpenDrivePreparedExternal prepared = provider.prepareExternal("夸父原2", fixture.payload());
        assertNotNull("Current Quark payload is not importable", prepared);
        OpenDrivePlayback playback = null;
        try {
            playback = provider.resolve(prepared.materialize());
            HttpUrl proxy = HttpUrl.parse(playback.url());
            assertNotNull(proxy);
            assertTrue("Expected the reviewed Quark proxy", "127.0.0.1".equals(proxy.host()));

            byte[] signature = read(provider, proxy, 0L, 32);
            assertTrue("Quark proxy did not expose a standard MP4 ftyp box",
                    signature[4] == 'f' && signature[5] == 't'
                            && signature[6] == 'y' && signature[7] == 'p');
            Log.i(TAG, "normalized=mp4,ftypOffset=4");
        } finally {
            cleanup(provider, playback);
        }
    }

    private static byte[] read(OpenDriveProvider provider, HttpUrl proxy,
                               long start, int length) throws Exception {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        for (String name : proxy.queryParameterNames()) {
            params.put(name, proxy.queryParameter(name));
        }
        params.put("range", "bytes=" + start + '-' + (start + length - 1L));
        OpenDriveProxyResponse response = provider.proxy(params);
        assertNotNull(response);
        assertTrue("Quark range HTTP " + response.getStatus(), response.getStatus() == 206);
        String contentRange = response.getHeaders().get("Content-Range");
        assertNotNull("Quark 206 omitted Content-Range", contentRange);
        assertTrue("Quark served the wrong range: " + contentRange,
                contentRange.startsWith("bytes " + start + '-'));
        try (InputStream input = response.getStream()) {
            byte[] result = input.readNBytes(length);
            assertTrue("Short Quark range", result.length == length);
            return result;
        }
    }

    @SuppressWarnings("unchecked")
    private static void cleanup(OpenDriveProvider provider, OpenDrivePlayback playback) {
        if (playback == null) return;
        try {
            HttpUrl proxy = HttpUrl.parse(playback.url());
            String handle = proxy == null ? null : proxy.queryParameter("stream");
            Field streamsField = provider.getClass().getDeclaredField("streams");
            streamsField.setAccessible(true);
            Object stream = ((Map<String, Object>) streamsField.get(provider)).get(handle);
            if (stream == null) return;
            Field savedFid = stream.getClass().getDeclaredField("savedFid");
            savedFid.setAccessible(true);
            Method cleanup = provider.getClass().getDeclaredMethod("cleanup", String.class);
            cleanup.setAccessible(true);
            cleanup.invoke(provider, savedFid.get(stream));
        } catch (Exception ignored) {
        }
    }
}
