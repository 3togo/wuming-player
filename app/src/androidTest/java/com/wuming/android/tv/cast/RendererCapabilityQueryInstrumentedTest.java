package com.wuming.android.tv.cast;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.wuming.android.tv.bean.Device;
import com.wuming.android.tv.dlna.DLNACastManager;
import com.wuming.tv.core.playback.renderer.RendererIdentity;
import kotlin.Unit;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Explicit opt-in receiver name: queries declarations only; never starts TV playback. */
@RunWith(AndroidJUnit4.class)
public class RendererCapabilityQueryInstrumentedTest {
    @Test public void pairingInstructionsAreReadableAndRetryKeepsItsAction() throws Exception {
        RendererIdentity identity = new RendererIdentity(RendererIdentity.key("pairing-ui-only"),
            "TVBox receiver", "test", "test", "unknown", "test");
        java.lang.reflect.Method render = com.wuming.android.tv.ui.dialog.CastDialogV2.class.getDeclaredMethod(
            "renderRendererProfile", RendererIdentity.class, String.class);
        render.setAccessible(true);
        try (androidx.test.core.app.ActivityScenario<com.wuming.android.tv.ui.activity.HomeActivity> scenario =
                 androidx.test.core.app.ActivityScenario.launch(com.wuming.android.tv.ui.activity.HomeActivity.class)) {
            scenario.onActivity(activity -> {
                com.wuming.android.tv.ui.dialog.CastDialogV2 dialog = com.wuming.android.tv.ui.dialog.CastDialogV2.create();
                dialog.show(activity);
                activity.getSupportFragmentManager().executePendingTransactions();
                try { render.invoke(dialog, identity, "pairing_required"); }
                catch (Exception error) { throw new AssertionError(error); }
            });
            String instructions = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getString(com.wuming.android.tv.R.string.renderer_pairing_instructions);
            waitForVisibleText(instructions);
            android.graphics.Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            assertNotNull(screenshot);
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(
                    InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), "renderer-pairing.png"))) {
                assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
            } finally { screenshot.recycle(); }
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(
                com.wuming.android.tv.R.string.renderer_query_retry)).perform(androidx.test.espresso.action.ViewActions.click());
            // The synthetic profile is offline. If the header shifted the action incorrectly,
            // Retry would open the playback/override action instead of rendering this status.
            String offline = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getString(com.wuming.android.tv.R.string.renderer_query_offline);
            long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
            while (true) {
                try {
                    androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(
                        org.hamcrest.Matchers.containsString(offline))).check(androidx.test.espresso.assertion.ViewAssertions.matches(
                            androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
                    break;
                } catch (androidx.test.espresso.NoMatchingViewException error) {
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) throw error;
                    Thread.sleep(100);
                }
            }
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(instructions))
                .check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
        }
    }

    private static void waitForVisibleText(String text) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        while (true) {
            try {
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(text))
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                        androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
                return;
            } catch (androidx.test.espresso.NoMatchingViewException error) {
                if (android.os.SystemClock.elapsedRealtime() >= deadline) throw error;
                Thread.sleep(100);
            }
        }
    }

    @Test public void realDashRelayHasValidHeadAndAuthenticatedMediaRanges() throws Exception {
        String source = InstrumentationRegistry.getArguments().getString("dashSource");
        assumeTrue("Requires an explicitly selected source", source != null);
        com.wuming.android.tv.server.Server.get().start();
        String relay = com.wuming.android.tv.server.CastMediaAccess.relay(source, java.util.Collections.emptyMap());
        assertTrue(relay.contains("/cast-relay/"));
        java.net.HttpURLConnection get = (java.net.HttpURLConnection) new java.net.URL(relay).openConnection();
        get.setConnectTimeout(10000); get.setReadTimeout(15000);
        org.w3c.dom.Document document;
        try {
            assertEquals(200, get.getResponseCode());
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            try (java.io.InputStream input = get.getInputStream()) { document = factory.newDocumentBuilder().parse(input); }
        } finally { get.disconnect(); }
        java.net.HttpURLConnection head = (java.net.HttpURLConnection) new java.net.URL(relay).openConnection();
        head.setRequestMethod("HEAD"); head.setConnectTimeout(10000); head.setReadTimeout(15000);
        try {
            assertEquals(200, head.getResponseCode());
            assertTrue("HEAD must describe a nonempty rewritten MPD", head.getContentLengthLong() > 0);
        } finally { head.disconnect(); }
        org.w3c.dom.NodeList urls = document.getElementsByTagNameNS("*", "BaseURL");
        assertTrue("Expected audio and video media URLs", urls.getLength() >= 2);
        for (int i = 0; i < urls.getLength(); i++) {
            String url = urls.item(i).getTextContent().trim();
            assertTrue("Media URL bypasses relay", url.contains("/cast-relay/"));
            java.net.HttpURLConnection child = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            child.setConnectTimeout(10000); child.setReadTimeout(15000);
            child.setRequestProperty("Range", "bytes=0-1023");
            try {
                assertEquals("Representation " + i, 206, child.getResponseCode());
                assertTrue(child.getHeaderField("Content-Range").startsWith("bytes 0-1023/"));
                try (java.io.InputStream input = child.getInputStream()) { assertTrue(input.read() >= 0); }
            } finally { child.disconnect(); }
        }
    }

    @Test public void cachedWriterPreservesEvidenceSavedByAnotherWriter() throws Exception {
        RendererIdentity identity = new RendererIdentity(RendererIdentity.key("storage-regression-only"),
            "Storage regression", "test", "test", "unknown", "test");
        java.io.File directory = InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir();
        try {
            // Populate the application's cached repository before another writer updates the file.
            CountDownLatch primed = new CountDownLatch(1);
            RendererCapabilityProfiles.INSTANCE.export(identity, value -> { primed.countDown(); return Unit.INSTANCE; });
            assertTrue(primed.await(5, TimeUnit.SECONDS));
            try (java.io.RandomAccessFile lockFile = new java.io.RandomAccessFile(new java.io.File(directory, "renderer-capabilities.lock"), "rw");
                 java.nio.channels.FileLock lock = lockFile.getChannel().lock()) {
                android.util.AtomicFile file = new android.util.AtomicFile(new java.io.File(directory, "renderer-capabilities.json"));
                com.wuming.tv.core.playback.renderer.RendererProfiles external = new com.wuming.tv.core.playback.renderer.RendererProfiles(
                    new com.wuming.tv.core.playback.renderer.RendererProfileStore() {
                        public String read() {
                            try (java.io.InputStream input = file.openRead()) {
                                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                                byte[] buffer = new byte[4096]; int count;
                                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                                return output.toString("UTF-8");
                            } catch (java.io.FileNotFoundException e) { return null; }
                            catch (java.io.IOException e) { throw new RuntimeException(e); }
                        }
                        public void write(String json) {
                            java.io.FileOutputStream output = null;
                            try { output = file.startWrite(); output.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)); file.finishWrite(output); }
                            catch (java.io.IOException e) { if (output != null) file.failWrite(output); throw new RuntimeException(e); }
                        }
                    }, java.util.Collections.emptyList());
                external.record(identity, new com.wuming.tv.core.playback.renderer.RendererEvidence(
                    com.wuming.tv.core.playback.renderer.RendererCapability.MP4,
                    com.wuming.tv.core.playback.renderer.EvidenceResult.CONTROLLED_SUCCESS,
                    "external-writer", System.currentTimeMillis(), "test", "storage_regression", -1, -1));
            }
            CountDownLatch updated = new CountDownLatch(1);
            RendererCapabilityProfiles.INSTANCE.setOverride(identity,
                com.wuming.tv.core.playback.renderer.RendererCapability.HLS,
                com.wuming.tv.core.playback.renderer.CapabilityState.UNKNOWN,
                ok -> { updated.countDown(); return Unit.INSTANCE; });
            assertTrue(updated.await(5, TimeUnit.SECONDS));
            CountDownLatch read = new CountDownLatch(1);
            AtomicReference<String> report = new AtomicReference<>();
            RendererCapabilityProfiles.INSTANCE.export(identity, value -> { report.set(value); read.countDown(); return Unit.INSTANCE; });
            assertTrue(read.await(5, TimeUnit.SECONDS));
            assertNotNull(report.get());
            assertEquals("SUPPORTED", com.google.gson.JsonParser.parseString(report.get()).getAsJsonObject()
                .getAsJsonObject("capabilities").get("MP4").getAsString());
        } finally {
            CountDownLatch cleanup = new CountDownLatch(1);
            RendererCapabilityProfiles.INSTANCE.reset(identity, ok -> { cleanup.countDown(); return Unit.INSTANCE; });
            assertTrue(cleanup.await(5, TimeUnit.SECONDS));
        }
    }

    @Test public void selectedReceiverDeclarationsAreSaved() throws Exception {
        String name = InstrumentationRegistry.getArguments().getString("rendererName");
        assumeTrue("Requires explicitly selected receiver", name != null && !name.isEmpty());
        DLNACastManager manager = DLNACastManager.get();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> manager.init(
            InstrumentationRegistry.getInstrumentation().getTargetContext()));
        Device selected = null;
        long deadline = android.os.SystemClock.elapsedRealtime() + 20000;
        while (selected == null && android.os.SystemClock.elapsedRealtime() < deadline) {
            manager.search();
            for (Device device : manager.getRegistered()) if (name.equals(device.getName())) selected = device;
            if (selected == null) Thread.sleep(1000);
        }
        assertNotNull("Selected receiver not discovered", selected);
        RendererIdentity identity = RendererCapabilityProfiles.INSTANCE.identityFor(selected);
        assertNotNull(identity);
        CountDownLatch query = new CountDownLatch(1);
        AtomicReference<String> status = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
            RendererCapabilityProfiles.INSTANCE.inspect(identity, value -> { status.set(value); query.countDown(); return Unit.INSTANCE; }));
        assertTrue("Query did not complete", query.await(15, TimeUnit.SECONDS));
        String expected = InstrumentationRegistry.getArguments().getString("expectedQueryStatus", "queried");
        assertEquals(expected, status.get());
        CountDownLatch exported = new CountDownLatch(1);
        AtomicReference<String> report = new AtomicReference<>();
        RendererCapabilityProfiles.INSTANCE.export(identity, value -> { report.set(value); exported.countDown(); return Unit.INSTANCE; });
        assertTrue(exported.await(5, TimeUnit.SECONDS));
        assertNotNull(report.get());
        assertTrue("No advertised capabilities persisted", report.get().contains("\"advertised\": true"));
        if ("query_http_403".equals(expected) || "pairing_required".equals(expected)) {
            com.google.gson.JsonObject data = com.google.gson.JsonParser.parseString(report.get()).getAsJsonObject();
            // A denied format query must still retain independently read action descriptions.
            assertTrue(data.getAsJsonObject("assessments").getAsJsonObject("SEEK").get("advertised").getAsBoolean());
            assertTrue(data.getAsJsonObject("assessments").getAsJsonObject("STOP").get("advertised").getAsBoolean());
            assertEquals("UNKNOWN", data.getAsJsonObject("capabilities").get("HEVC").getAsString());
        }
        if ("queried".equals(expected)) {
            org.fourthline.cling.model.meta.RemoteDevice remote = manager.findDevice(selected);
            assertNotNull(remote.findService(new org.fourthline.cling.model.types.UDAServiceType("ConnectionManager")));
            assertNotNull(remote.findService(new org.fourthline.cling.model.types.UDAServiceType("RenderingControl")));
            com.google.gson.JsonObject data = com.google.gson.JsonParser.parseString(report.get()).getAsJsonObject();
            assertTrue(data.getAsJsonObject("assessments").getAsJsonObject("MP4").get("advertised").getAsBoolean());
            String advertised = InstrumentationRegistry.getArguments().getString("expectAdvertised", "");
            for (String capability : advertised.split(",")) if (!capability.isEmpty())
                assertTrue(capability + " declaration missing", data.getAsJsonObject("assessments")
                    .getAsJsonObject(capability).get("advertised").getAsBoolean());
            if ("true".equals(InstrumentationRegistry.getArguments().getString("expectLeanbackMarker")))
                assertEquals(com.wuming.tv.core.playback.renderer.RendererPairingHint.MODEL_DESCRIPTION,
                    remote.getDetails().getModelDetails().getModelDescription());
            String preserved = InstrumentationRegistry.getArguments().getString("expectSupported", "");
            for (String capability : preserved.split(",")) if (!capability.isEmpty())
                assertEquals(capability, "SUPPORTED", data.getAsJsonObject("capabilities").get(capability).getAsString());
        }
        String confirmedRun = InstrumentationRegistry.getArguments().getString("confirmPreviousSampleRun");
        if (confirmedRun != null) {
            CountDownLatch confirmed = new CountDownLatch(1);
            AtomicReference<Boolean> saved = new AtomicReference<>(false);
            RendererCapabilityProfiles.INSTANCE.confirmProbe(identity,
                com.wuming.tv.core.playback.renderer.RendererProbeSamples.INSTANCE.getSamples().get(0), confirmedRun,
                ok -> { saved.set(ok); confirmed.countDown(); return Unit.INSTANCE; });
            assertTrue(confirmed.await(5, TimeUnit.SECONDS));
            assertTrue("Confirmed picture/audio result not saved", saved.get());
        }
        if ("true".equals(InstrumentationRegistry.getArguments().getString("playSample"))) {
            Device receiver = selected;
            RendererPlaybackProbe[] probe = new RendererPlaybackProbe[1];
            try {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    probe[0] = new RendererPlaybackProbe(receiver,
                        com.wuming.tv.core.playback.renderer.RendererProbeSamples.INSTANCE.getSamples().get(0));
                    probe[0].start();
                });
                long until = android.os.SystemClock.elapsedRealtime() + 25000;
                while (!probe[0].getFetchedMedia() && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(250);
                assertTrue("TV did not fetch the MP4 sample", probe[0].getFetchedMedia());
                Thread.sleep(12000);
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { if (probe[0] != null) probe[0].close(); });
            }
        }
    }
}
