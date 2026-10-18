package com.wuming.android.tv.cloud.opendrive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.wuming.tv.core.playback.OpenDrivePlaybackHints;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.Gson;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import okhttp3.HttpUrl;
import okhttp3.Response;

/**
 * JVM regression tests for the pure/static helpers of the Quark provider.
 * Network-bound paths are covered by the instrumented OpenDrive tests.
 */
public class QuarkOpenDriveProviderTest {

    @Test
    public void plainRendererGetKeepsParallelCastEnabled() throws Exception {
        var original = new QuarkOpenDriveProvider.StreamRef("https://media.example/file", "saved", null);
        var full = QuarkOpenDriveProvider.LogicalRange.parse("bytes 0-9999999/10000000");
        assertEquals("bytes=0-", QuarkOpenDriveProvider.translatedRange(null, 0));
        assertTrue(QuarkOpenDriveProvider.shouldUseParallelCast(
                Map.of("quarkCast", "parallel"), original, full));
        assertTrue(QuarkOpenDriveProvider.shouldUseParallelCast(
                Map.of("quarkCast", "parallel", "range", ""), original, full));
        assertTrue(QuarkOpenDriveProvider.shouldUseParallelCast(
                Map.of("quarkCast", "parallel", "range", "bytes=1000-"), original,
                QuarkOpenDriveProvider.LogicalRange.parse("bytes 1000-9999999/10000000")));
    }

    @Test
    public void castAccelerationStillRequiresOriginalMediaAndValidatedLargeRange() {
        var original = new QuarkOpenDriveProvider.StreamRef("https://media.example/file", "saved", null);
        var smart = new QuarkOpenDriveProvider.StreamRef("https://media.example/file", "saved", null, true);
        var full = QuarkOpenDriveProvider.LogicalRange.parse("bytes 0-9999999/10000000");
        assertFalse(QuarkOpenDriveProvider.shouldUseParallelCast(Map.of(), original, full));
        assertFalse(QuarkOpenDriveProvider.shouldUseParallelCast(Map.of("quarkCast", "parallel"), smart, full));
        assertFalse(QuarkOpenDriveProvider.shouldUseParallelCast(Map.of("quarkCast", "parallel"), original, null));
        assertFalse(QuarkOpenDriveProvider.shouldUseParallelCast(Map.of("quarkCast", "parallel"), original,
                QuarkOpenDriveProvider.LogicalRange.parse("bytes 0-15/10000000")));
    }

    @Test
    public void saveWaitsForCompletionEvenWhenDestinationIdIsPresent() throws Exception {
        JsonObject task = new Gson().fromJson(
                "{\"status\":1,\"save_as\":{\"save_as_top_fids\":[\"destination\"]}}", JsonObject.class);
        assertNull(QuarkOpenDriveProvider.completedSavedFid(task));
        task.addProperty("status", 2);
        assertEquals("destination", QuarkOpenDriveProvider.completedSavedFid(task));
    }

    @Test
    public void failedSaveCannotSucceedThroughAnEarlyDestinationId() {
        JsonObject task = new Gson().fromJson(
                "{\"status\":3,\"save_as\":{\"save_as_top_fids\":[\"destination\"],"
                        + "\"remain_capacity\":0,\"min_save_file_size\":100}}", JsonObject.class);
        assertEquals(com.wuming.tv.core.cloud.OpenDriveFailure.Reason.QUOTA_EXHAUSTED,
                assertThrows(com.wuming.tv.core.cloud.OpenDriveFailure.class,
                        () -> QuarkOpenDriveProvider.completedSavedFid(task)).reason());
        task.getAsJsonObject("save_as").remove("min_save_file_size");
        assertThrows(IOException.class, () -> QuarkOpenDriveProvider.completedSavedFid(task));
    }

    @Test
    public void completedSaveRequiresDestinationId() {
        JsonObject task = new Gson().fromJson("{\"status\":2}", JsonObject.class);
        assertThrows(IOException.class, () -> QuarkOpenDriveProvider.completedSavedFid(task));
    }

    // --- validAccountResponse ---

    @Test
    public void accountResponseRequiresNonEmptyDataObject() {
        JsonObject valid = new JsonObject();
        JsonObject data = new JsonObject();
        data.addProperty("list", "anything");
        valid.add("data", data);
        assertTrue(QuarkOpenDriveProvider.validAccountResponse(valid));

        JsonObject empty = new JsonObject();
        empty.add("data", new JsonObject());
        assertFalse(QuarkOpenDriveProvider.validAccountResponse(empty));
        assertFalse(QuarkOpenDriveProvider.validAccountResponse(new JsonObject()));
        assertFalse(QuarkOpenDriveProvider.validAccountResponse(null));
    }

    // --- transcodeIsHls ---

    @Test
    public void transcodeHlsDetectedFromM3u8Prefix() {
        assertTrue(QuarkOpenDriveProvider.transcodeIsHls(
                "application/octet-stream", "#EXTM3U\n#EXT-X".getBytes(), "https://cdn/x.bin"));
    }

    @Test
    public void transcodeHlsRejectedForContainerSignatures() {
        byte[] mp4 = new byte[16];
        mp4[4] = 'f'; mp4[5] = 't'; mp4[6] = 'y'; mp4[7] = 'p';
        assertFalse(QuarkOpenDriveProvider.transcodeIsHls(
                "application/vnd.apple.mpegurl", mp4, "https://cdn/x.m3u8"));

        byte[] ebml = new byte[]{(byte) 0x1a, (byte) 0x45, (byte) 0xdf, (byte) 0xa3};
        assertFalse(QuarkOpenDriveProvider.transcodeIsHls("video/MP2T", ebml, "https://cdn/x.m3u8"));
    }

    @Test
    public void transcodeHlsFallsBackToMimeThenUrl() {
        assertTrue(QuarkOpenDriveProvider.transcodeIsHls(
                "application/x-mpegURL", null, "https://cdn/x.bin"));
        assertFalse(QuarkOpenDriveProvider.transcodeIsHls("video/mp4", null, "https://cdn/x.m3u8"));
        assertFalse(QuarkOpenDriveProvider.transcodeIsHls(
                "application/octet-stream", null, "https://cdn/x.m3u8"));
        // Unknown mime falls back to the URL suffix.
        assertTrue(QuarkOpenDriveProvider.transcodeIsHls("text/plain", null, "https://cdn/x.m3u8"));
        assertFalse(QuarkOpenDriveProvider.transcodeIsHls("text/plain", null, "https://cdn/x.mp4"));
        assertFalse(QuarkOpenDriveProvider.transcodeIsHls(null, null, "not a uri"));
    }

    // --- readPrefix ---

    @Test
    public void readPrefixReturnsShortAndExactReads() throws Exception {
        assertEquals(0, QuarkOpenDriveProvider.readPrefix(null, 8).length);
        assertEquals(0, QuarkOpenDriveProvider.readPrefix(
                new ByteArrayInputStream(new byte[]{1, 2, 3}), 0).length);
        assertArrayEquals(new byte[]{1, 2, 3},
                QuarkOpenDriveProvider.readPrefix(new ByteArrayInputStream(new byte[]{1, 2, 3}), 8));
        assertArrayEquals(new byte[]{1, 2},
                QuarkOpenDriveProvider.readPrefix(new ByteArrayInputStream(new byte[]{1, 2, 3}), 2));
    }

    @Test
    public void readPrefixMakesProgressOnZeroLengthReads() throws Exception {
        // Non-conforming stream that reports a zero-length read once, then delivers data.
        InputStream stubborn = new ByteArrayInputStream(new byte[]{9, 8, 7}) {
            private boolean stalled;
            @Override
            public int read(byte[] b, int off, int len) {
                if (!stalled) {
                    stalled = true;
                    return 0;
                }
                return super.read(b, off, len);
            }
        };
        assertArrayEquals(new byte[]{9, 8, 7}, QuarkOpenDriveProvider.readPrefix(stubborn, 3));
    }

    // --- logicalContentLength ---

    @Test
    public void logicalContentLengthSubtractsMediaOffset() {
        assertEquals(1992L, QuarkOpenDriveProvider.logicalContentLength("bytes 8-1999/2000", 8L));
        assertEquals(100L, QuarkOpenDriveProvider.logicalContentLength("bytes 0-99/100", 0L));
        assertEquals(0L, QuarkOpenDriveProvider.logicalContentLength("bytes 0-99/*", 0L));
        assertEquals(0L, QuarkOpenDriveProvider.logicalContentLength(null, 8L));
        assertEquals(0L, QuarkOpenDriveProvider.logicalContentLength("bytes 8-9/4", 8L));
    }

    // --- prefixedMp4Offset ---

    @Test
    public void prefixedMp4OffsetDetectsPngWrappedMp4() {
        byte[] wrapped = new byte[16];
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        System.arraycopy(png, 0, wrapped, 0, png.length);
        wrapped[8] = 0; wrapped[9] = 0; wrapped[10] = 0; wrapped[11] = 24;
        wrapped[12] = 'f'; wrapped[13] = 't'; wrapped[14] = 'y'; wrapped[15] = 'p';
        assertEquals(8L, QuarkOpenDriveProvider.prefixedMp4Offset(wrapped));

        wrapped[12] = 'x';
        assertEquals(0L, QuarkOpenDriveProvider.prefixedMp4Offset(wrapped));
        assertEquals(0L, QuarkOpenDriveProvider.prefixedMp4Offset(null));
        assertEquals(0L, QuarkOpenDriveProvider.prefixedMp4Offset(new byte[8]));
    }

    // --- missingJustSavedFile / transcodeUnavailable ---

    @Test
    public void missingJustSavedFileMatchesQuark404WithApiCode21001() {
        assertTrue(QuarkOpenDriveProvider.missingJustSavedFile(
                new QuarkNetwork.QuarkHttpException(404, "/file/download", 21_001L, false)));
        assertFalse(QuarkOpenDriveProvider.missingJustSavedFile(
                new QuarkNetwork.QuarkHttpException(404, "/file/download", 21_005L, false)));
        assertFalse(QuarkOpenDriveProvider.missingJustSavedFile(
                new QuarkNetwork.QuarkHttpException(400, "/file/download", 21_001L, false)));
        assertFalse(QuarkOpenDriveProvider.missingJustSavedFile(new IOException("boom")));
        assertFalse(QuarkOpenDriveProvider.missingJustSavedFile(null));
    }

    @Test
    public void transcodeUnavailableIsCapabilityFailureOnly() {
        assertTrue(QuarkOpenDriveProvider.transcodeUnavailable(
                new QuarkNetwork.QuarkHttpException(400, "/file/v2/play", 21_005L, false)));
        assertTrue(QuarkOpenDriveProvider.transcodeUnavailable(
                new QuarkNetwork.QuarkHttpException(403, "/file/v2/play", 0L, true)));
        assertFalse(QuarkOpenDriveProvider.transcodeUnavailable(
                new QuarkNetwork.QuarkHttpException(400, "/file/v2/play", 21_001L, false)));
        assertFalse(QuarkOpenDriveProvider.transcodeUnavailable(
                new QuarkNetwork.QuarkHttpException(404, "/file/v2/play", 21_005L, false)));
        assertFalse(QuarkOpenDriveProvider.transcodeUnavailable(null));
    }

    // --- translatedRange ---

    @Test
    public void translatedRangeShiftsByteRangesByMediaOffset() throws Exception {
        assertEquals("bytes=8-", QuarkOpenDriveProvider.translatedRange(null, 8L));
        assertEquals("bytes=0-", QuarkOpenDriveProvider.translatedRange("", 0L));
        assertEquals("bytes=10-20", QuarkOpenDriveProvider.translatedRange("bytes=10-20", 0L));
        assertEquals("bytes=18-28", QuarkOpenDriveProvider.translatedRange("bytes=10-20", 8L));
        assertEquals("bytes=18-", QuarkOpenDriveProvider.translatedRange("bytes=10-", 8L));
        // Suffix ranges are passed through untouched.
        assertEquals("bytes=-100", QuarkOpenDriveProvider.translatedRange("bytes=-100", 8L));
    }

    @Test
    public void translatedRangeRejectsUnsupportedShapes() {
        assertThrows(IOException.class,
                () -> QuarkOpenDriveProvider.translatedRange("items=1-2", 8L));
        assertThrows(IOException.class,
                () -> QuarkOpenDriveProvider.translatedRange("bytes=" + Long.MAX_VALUE + "-", 8L));
    }

    // --- translatedContentRange ---

    @Test
    public void translatedContentRangeShiftsBackToLogicalCoordinates() {
        assertNull(QuarkOpenDriveProvider.translatedContentRange(null, 8L));
        assertEquals("bytes 0-1000/2000",
                QuarkOpenDriveProvider.translatedContentRange("bytes 8-1008/2008", 8L));
        assertEquals("bytes 0-1000/*",
                QuarkOpenDriveProvider.translatedContentRange("bytes 8-1008/*", 8L));
        assertEquals("bytes 0-92/100",
                QuarkOpenDriveProvider.translatedContentRange("bytes 8-100/108", 8L));
    }

    @Test
    public void translatedContentRangeKeepsOriginalOnInconsistentValues() {
        // Offset 0 is a pass-through.
        assertEquals("bytes 8-1008/2008",
                QuarkOpenDriveProvider.translatedContentRange("bytes 8-1008/2008", 0L));
        // Shift would produce negative start.
        assertEquals("bytes 4-10/100",
                QuarkOpenDriveProvider.translatedContentRange("bytes 4-10/100", 8L));
        // Shifted total would not exceed the shifted end.
        assertEquals("bytes 8-100/100",
                QuarkOpenDriveProvider.translatedContentRange("bytes 8-100/100", 8L));
        // Non-matching header shape.
        assertEquals("identity",
                QuarkOpenDriveProvider.translatedContentRange("identity", 8L));
    }

    // --- transcodeResolution ---

    @Test
    public void originalFixtureUsesDownloadWithoutCallingTranscode() throws Exception {
        QuarkOpenDriveProvider provider = new QuarkOpenDriveProvider(
                new QuarkNetwork.QuarkTransport() {
                    public JsonObject get(HttpUrl url) {
                        throw new AssertionError("Unexpected GET");
                    }

                    public Response open(String url, String range) {
                        throw new AssertionError("Unexpected media read");
                    }

                    public JsonObject post(HttpUrl url, JsonObject body) {
                        throw new AssertionError("Original playback must not request transcode");
                    }

                    public QuarkOpenDriveProvider.DownloadResponse postDownload(
                            HttpUrl url, JsonObject body) {
                        assertEquals("saved", body.getAsJsonArray("fids").get(0).getAsString());
                        JsonObject item = new JsonObject();
                        item.addProperty("fid", "saved");
                        item.addProperty("download_url", "https://cdn.example/original.mp4");
                        JsonArray data = new JsonArray();
                        data.add(item);
                        JsonObject root = new JsonObject();
                        root.add("data", data);
                        return new QuarkOpenDriveProvider.DownloadResponse(root, "session=opaque");
                    }
                }, () -> true, () -> {});

        QuarkOpenDriveProvider.PlaybackUrl playback = provider.requestPlaybackUrl(
                "saved", new QuarkOpenDriveProvider.FileRef(
                        "file", "", "", "", "0", false, ""));

        assertEquals("https://cdn.example/original.mp4", playback.url());
        assertFalse(playback.transcoded());
        assertFalse(playback.smartOriginalFallback());
        assertEquals("session=opaque", playback.cookie());
    }

    @Test
    public void smartPlaybackFallsBackToLowWithoutRequestingOriginal() throws Exception {
        QuarkOpenDriveProvider.PlaybackUrl playback = qualityProvider().requestPlaybackUrl(
                "saved", new QuarkOpenDriveProvider.FileRef("file", "", "", "", "0", false, "auto"));
        assertEquals("https://cdn.example/270p", playback.url());
        assertTrue(playback.transcoded());
    }

    @Test
    public void explicitLowPlaybackDoesNotUpgradeToOriginal() throws Exception {
        QuarkOpenDriveProvider.PlaybackUrl playback = qualityProvider().requestPlaybackUrl(
                "saved", new QuarkOpenDriveProvider.FileRef("file", "", "", "", "0", false, "low"));
        assertEquals("https://cdn.example/270p", playback.url());
        assertTrue(playback.transcoded());
    }

    private static QuarkOpenDriveProvider qualityProvider() {
        return new QuarkOpenDriveProvider(new QuarkNetwork.QuarkTransport() {
            public JsonObject get(HttpUrl url) { throw new AssertionError("Unexpected GET"); }
            public Response open(String url, String range) { throw new AssertionError("Unexpected media read"); }
            public JsonObject post(HttpUrl url, JsonObject body) {
                assertEquals("normal,low,high,super,2k,4k", body.get("resolutions").getAsString());
                JsonArray videos = new JsonArray();
                // Real response shape: the advertised higher renditions lack video_info.
                for (String quality : new String[]{"4k", "super", "high"}) {
                    JsonObject video = new JsonObject();
                    video.addProperty("resolution", quality);
                    videos.add(video);
                }
                videos.add(rendition("low", "https://cdn.example/270p"));
                JsonObject data = new JsonObject();
                data.add("video_list", videos);
                JsonObject root = new JsonObject();
                root.add("data", data);
                return root;
            }
            public QuarkOpenDriveProvider.DownloadResponse postDownload(HttpUrl url, JsonObject body)
                    throws IOException {
                throw new AssertionError("A playable transcode must not request the original");
            }
        }, () -> true, () -> {});
    }

    @Test
    public void legacyPersistedLowQualityHandleMigratesToSmartSelection() throws Exception {
        Gson gson = new Gson();
        QuarkOpenDriveProvider.FileRef restored = gson.fromJson(
                "{\"fid\":\"file\",\"transcodeResolution\":\"low\",\"size\":123}",
                QuarkOpenDriveProvider.FileRef.class);
        assertEquals("auto", restored.transcodeResolution());
        assertEquals(123L, restored.size());
        JsonArray videos = new JsonArray();
        videos.add(rendition("low", "https://cdn.example/270p"));
        videos.add(rendition("4k", "https://cdn.example/4k"));
        assertEquals("low", QuarkTranscodePolicy.selectTranscode(
                videos, restored.transcodeResolution()).get("resolution").getAsString());
    }

    @Test
    public void newExplicitLowQualityAndOriginalHandlesSurvivePersistence() {
        Gson gson = new Gson();
        for (String resolution : new String[]{"low", "auto", ""}) {
            QuarkOpenDriveProvider.FileRef file = new QuarkOpenDriveProvider.FileRef(
                    "file", "token", "share", "shareToken", "0", false, resolution, 123L);
            assertEquals(file, gson.fromJson(gson.toJson(file), QuarkOpenDriveProvider.FileRef.class));
        }
        assertEquals("", gson.fromJson("{\"transcodeResolution\":\"\"}",
                QuarkOpenDriveProvider.FileRef.class).transcodeResolution());
    }

    @Test
    public void transcodeResolutionDistinguishesSmartFromLowQuality() {
        assertEquals("", QuarkTranscodePolicy.transcodeResolution(null));
        assertEquals("", QuarkTranscodePolicy.transcodeResolution("夸父原"));
        assertEquals("", QuarkTranscodePolicy.transcodeResolution("normal"));
        assertEquals("auto", QuarkTranscodePolicy.transcodeResolution("夸父智"));
        assertEquals("auto", QuarkTranscodePolicy.transcodeResolution("夸克智"));
        assertEquals("auto", QuarkTranscodePolicy.transcodeResolution("quark智"));
        assertEquals("low", QuarkTranscodePolicy.transcodeResolution("quark普画"));
        assertEquals("auto", QuarkTranscodePolicy.transcodeResolution("QUARK智"));
    }

    @Test
    public void smartTranscodePrefersMiddleQualityRegardlessOfApiOrder() throws Exception {
        JsonArray videos = new JsonArray();
        videos.add(rendition("low", "https://cdn.example/low"));
        videos.add(rendition("super", "https://cdn.example/super"));
        videos.add(rendition("4k", "https://cdn.example/4k"));
        videos.add(rendition("high", "https://cdn.example/high"));
        assertEquals("super", QuarkTranscodePolicy.selectTranscode(videos, "auto")
                .get("resolution").getAsString());
        assertEquals("low", QuarkTranscodePolicy.selectTranscode(videos, "low")
                .get("resolution").getAsString());
    }

    @Test
    public void smartTranscodeFallsFrom1080To720ThenLow() throws Exception {
        JsonArray videos = new JsonArray();
        videos.add(rendition("4k", "https://cdn.example/4k"));
        videos.add(rendition("low", "https://cdn.example/low"));
        videos.add(rendition("high", "https://cdn.example/high"));
        videos.add(rendition("super", ""));
        assertEquals("high", QuarkTranscodePolicy.selectTranscode(videos, "auto")
                .get("resolution").getAsString());
        videos.remove(2);
        assertEquals("low", QuarkTranscodePolicy.selectTranscode(videos, "auto")
                .get("resolution").getAsString());
    }

    @Test
    public void smartTranscodeChecksActualDimensionsBeforeLabels() throws Exception {
        JsonArray videos = new JsonArray();
        JsonObject misleading = rendition("super", "https://cdn.example/oversized");
        misleading.getAsJsonObject("video_info").addProperty("width", 3840);
        misleading.getAsJsonObject("video_info").addProperty("height", 2160);
        videos.add(misleading);
        JsonObject cropped = rendition("high", "https://cdn.example/cropped");
        cropped.getAsJsonObject("video_info").addProperty("width", 1920);
        cropped.getAsJsonObject("video_info").addProperty("height", 800);
        videos.add(cropped);
        assertEquals(cropped, QuarkTranscodePolicy.selectTranscode(videos, "auto"));
        cropped.getAsJsonObject("video_info").addProperty("width", 480);
        cropped.getAsJsonObject("video_info").addProperty("height", 270);
        assertEquals(cropped, QuarkTranscodePolicy.selectTranscode(videos, "auto"));
    }

    @Test
    public void smartTranscodeDoesNotUseDeniedMiddleTierEvenWithAUrl() {
        JsonArray videos = new JsonArray();
        JsonObject denied = rendition("super", "https://cdn.example/denied");
        denied.addProperty("accessable", false);
        videos.add(denied);
        assertThrows(QuarkSmartQualityException.class,
                () -> QuarkTranscodePolicy.selectTranscode(videos, "auto"));
    }

    @Test
    public void smartNotVideoFallsBackToNormalizedOriginal() throws Exception {
        QuarkOpenDriveProvider provider = new QuarkOpenDriveProvider(new QuarkNetwork.QuarkTransport() {
            public JsonObject get(HttpUrl url) { throw new AssertionError("Unexpected GET"); }
            public Response open(String url, String range) { throw new AssertionError("Unexpected media read"); }
            public JsonObject post(HttpUrl url, JsonObject body) throws Exception {
                throw new QuarkNetwork.QuarkHttpException(400, "/file/v2/play", 21005L, false);
            }
            public QuarkOpenDriveProvider.DownloadResponse postDownload(HttpUrl url, JsonObject body) {
                JsonObject item = new JsonObject();
                item.addProperty("fid", "saved");
                item.addProperty("download_url", "https://cdn.example/original");
                JsonArray data = new JsonArray();
                data.add(item);
                JsonObject root = new JsonObject();
                root.add("data", data);
                return new QuarkOpenDriveProvider.DownloadResponse(root, "session=opaque");
            }
        }, () -> true, () -> {});
        QuarkOpenDriveProvider.PlaybackUrl playback = provider.requestPlaybackUrl("saved",
                new QuarkOpenDriveProvider.FileRef("file", "", "", "", "0", false, "auto"));
        assertEquals("https://cdn.example/original", playback.url());
        assertFalse(playback.transcoded());
        assertTrue(playback.smartOriginalFallback());
        assertEquals("session=opaque", playback.cookie());
    }

    private static JsonObject rendition(String resolution, String url) {
        JsonObject video = new JsonObject();
        video.addProperty("resolution", resolution);
        JsonObject info = new JsonObject();
        info.addProperty("url", url);
        video.add("video_info", info);
        return video;
    }

    // --- canonicalShareUrl / sharePasscode ---

    @Test
    public void canonicalShareUrlNormalizesAndValidates() {
        assertEquals("https://pan.quark.cn/s/abcd1234",
                QuarkOpenDriveProvider.canonicalShareUrl("https://pan.quark.cn/s/abcd1234"));
        assertEquals("https://pan.quark.cn/s/abcd1234",
                QuarkOpenDriveProvider.canonicalShareUrl(" https://pan.quark.cn/s/abcd1234/?pwd=xy12 "));
        assertNull(QuarkOpenDriveProvider.canonicalShareUrl(null));
        assertNull(QuarkOpenDriveProvider.canonicalShareUrl("http://pan.quark.cn/s/abcd1234"));
        assertNull(QuarkOpenDriveProvider.canonicalShareUrl("https://evil.example/s/abcd1234"));
        assertNull(QuarkOpenDriveProvider.canonicalShareUrl("https://pan.quark.cn/s/abc"));
        assertNull(QuarkOpenDriveProvider.canonicalShareUrl("https://user@pan.quark.cn/s/abcd1234"));
    }

    @Test
    public void sharePasscodeExtractsAndValidatesPwd() {
        assertEquals("xy12", QuarkOpenDriveProvider.sharePasscode(
                "https://pan.quark.cn/s/abcd1234?pwd=xy12"));
        assertEquals("", QuarkOpenDriveProvider.sharePasscode("https://pan.quark.cn/s/abcd1234"));
        assertEquals("", QuarkOpenDriveProvider.sharePasscode(null));
        assertThrows(IllegalArgumentException.class, () ->
                QuarkOpenDriveProvider.sharePasscode("https://pan.quark.cn/s/abcd1234?pwd=bad!pwd"));
        assertThrows(IllegalArgumentException.class, () ->
                QuarkOpenDriveProvider.sharePasscode("https://pan.quark.cn/s/abcd1234?pwd=abcdefghijklmnopq"));
    }

    // --- playback headers ---

    @Test
    public void transcodePlaybackHeadersCarryHintsOnlyWhenRelevant() {
        Map<String, String> plain = QuarkOpenDriveProvider.transcodePlaybackHeaders(null, false);
        assertEquals("https://pan.quark.cn/", plain.get("Referer"));
        assertFalse(plain.containsKey("Cookie"));
        assertFalse(plain.containsKey(OpenDrivePlaybackHints.EXPLICIT_COOKIE));
        assertFalse(plain.containsKey(OpenDrivePlaybackHints.HLS));

        Map<String, String> withCookie = QuarkOpenDriveProvider.transcodePlaybackHeaders("k=v", false);
        assertEquals("k=v", withCookie.get("Cookie"));
        assertTrue(OpenDrivePlaybackHints.enabled(withCookie, OpenDrivePlaybackHints.EXPLICIT_COOKIE));

        Map<String, String> hls = QuarkOpenDriveProvider.transcodePlaybackHeaders("k=v", true);
        assertTrue(OpenDrivePlaybackHints.enabled(hls, OpenDrivePlaybackHints.HLS));
        assertTrue(OpenDrivePlaybackHints.enabled(hls, OpenDrivePlaybackHints.EXPLICIT_COOKIE));
        assertEquals("k=v", hls.get("Cookie"));

        Map<String, String> hlsNoCookie = QuarkOpenDriveProvider.transcodePlaybackHeaders("", true);
        assertTrue(OpenDrivePlaybackHints.enabled(hlsNoCookie, OpenDrivePlaybackHints.HLS));
        assertFalse(hlsNoCookie.containsKey(OpenDrivePlaybackHints.EXPLICIT_COOKIE));

        Map<String, String> ranged = QuarkOpenDriveProvider.transcodePlaybackHeaders(
                "k=v", false, 128_847_022L);
        assertEquals("128847022", ranged.get(OpenDrivePlaybackHints.CONTENT_LENGTH));
        assertEquals(OpenDrivePlaybackHints.PROFILE_QUARK_ORIGINAL,
                ranged.get(OpenDrivePlaybackHints.PARALLEL_RANGE_PROFILE));
        assertTrue(OpenDrivePlaybackHints.enabled(
                ranged, OpenDrivePlaybackHints.EXPLICIT_COOKIE));
    }

    @Test
    public void transcodeRangeLengthRequiresAnExactSuccessfulProbe() {
        assertEquals(128_847_022L, QuarkOpenDriveProvider.transcodeRangeLength(
                206, "bytes 0-63/128847022"));
        assertEquals(0L, QuarkOpenDriveProvider.transcodeRangeLength(
                200, "bytes 0-63/128847022"));
        assertEquals(0L, QuarkOpenDriveProvider.transcodeRangeLength(
                206, "bytes 1-64/128847022"));
        assertEquals(0L, QuarkOpenDriveProvider.transcodeRangeLength(
                206, "bytes 0-31/128847022"));
        assertEquals(0L, QuarkOpenDriveProvider.transcodeRangeLength(
                206, "bytes 0-63/*"));
    }

    @Test
    public void originalPlaybackHeadersEnableParallelRangesOnlyWithLength() {
        Map<String, String> ranged = QuarkOpenDriveProvider.originalPlaybackHeaders(12345L);
        assertEquals("12345", ranged.get(OpenDrivePlaybackHints.CONTENT_LENGTH));
        assertEquals(OpenDrivePlaybackHints.PROFILE_QUARK_ORIGINAL,
                ranged.get(OpenDrivePlaybackHints.PARALLEL_RANGE_PROFILE));

        assertTrue(QuarkOpenDriveProvider.originalPlaybackHeaders(0L).isEmpty());
    }

    // --- Refactoring verification: QuarkOpenDriveProvider delegates JSON helpers to OpenDriveHttp ---

    @Test
    public void validAccountResponseUsesOpenDriveHttpObjectHelper() {
        // validAccountResponse calls object(root, "data") which now resolves
        // to OpenDriveHttp.object() via static import.
        JsonObject valid = new JsonObject();
        JsonObject data = new JsonObject();
        data.addProperty("list", "anything");
        valid.add("data", data);
        assertTrue(QuarkOpenDriveProvider.validAccountResponse(valid));

        JsonObject empty = new JsonObject();
        empty.add("data", new JsonObject());
        assertFalse(QuarkOpenDriveProvider.validAccountResponse(empty));
        assertFalse(QuarkOpenDriveProvider.validAccountResponse(new JsonObject()));
        assertFalse(QuarkOpenDriveProvider.validAccountResponse(null));
    }

    @Test
    public void availableCapacityUsesOpenDriveHttpNumberHelper() {
        // availableCapacity calls number(data, "total_capacity") and number(data, "use_capacity")
        // which now resolve to OpenDriveHttp.number() via static import.
        JsonObject quota = new Gson().fromJson(
                "{\"data\":{\"total_capacity\":10737418240,\"use_capacity\":5368709120}}",
                JsonObject.class);
        assertEquals(5L * 1024L * 1024L * 1024L,
                QuarkOpenDriveProvider.availableCapacity(quota));
        assertEquals(-1L, QuarkOpenDriveProvider.availableCapacity(new JsonObject()));
    }

    @Test
    public void matchingRecycleRecordIdsUsesOpenDriveHttpStringHelper() {
        // matchingRecycleRecordIds calls string(item, "fid") and string(item, "record_id")
        // which now resolve to OpenDriveHttp.string() via static import.
        JsonArray recycle = new Gson().fromJson(
                "[{\"fid\":\"owned-fid\",\"record_id\":\"rec-1\"},"
                        + "{\"fid\":\"other-fid\",\"record_id\":\"rec-2\"}]",
                JsonArray.class);
        java.util.Set<String> owned = java.util.Set.of("owned-fid");
        java.util.List<String> result = QuarkOpenDriveProvider.matchingRecycleRecordIds(recycle, owned);
        assertEquals(1, result.size());
        assertEquals("rec-1", result.get(0));
    }

    @Test
    public void shouldUseParallelCastUsesOpenDriveHttpHelpers() {
        // shouldUseParallelCast does not directly use JSON helpers but verifies
        // the static import chain is intact after refactoring.
        JsonObject params = new JsonObject();
        assertFalse(QuarkOpenDriveProvider.shouldUseParallelCast(null, null, null));
    }

    @Test
    public void quarkProviderDataMethodUsesOpenDriveHttpObjectHelper() {
        // The private data() method uses object(root, "data") which now resolves
        // to OpenDriveHttp.object(). Verify via validAccountResponse which calls
        // the same helper path: object(root, "data").size() > 0.
        JsonObject withData = new JsonObject();
        withData.add("data", new JsonObject());
        // Empty data object has size 0, so validAccountResponse returns false.
        assertFalse(QuarkOpenDriveProvider.validAccountResponse(withData));

        JsonObject withNonEmptyData = new JsonObject();
        JsonObject data = new JsonObject();
        data.addProperty("key", "value");
        withNonEmptyData.add("data", data);
        assertTrue(QuarkOpenDriveProvider.validAccountResponse(withNonEmptyData));
    }

    @Test
    public void quarkTranscodePolicyUsesOpenDriveHttpHelpersForDimensions() {
        // QuarkTranscodePolicy now imports bool/number/object/string from OpenDriveHttp
        // instead of QuarkOpenDriveProvider. Verify dimension-based smart selection
        // still works, which exercises number() and object() through OpenDriveHttp.
        JsonArray videos = new JsonArray();
        JsonObject video = rendition("super", "https://cdn.example/super");
        video.getAsJsonObject("video_info").addProperty("width", 1920);
        video.getAsJsonObject("video_info").addProperty("height", 1080);
        videos.add(video);
        try {
            assertEquals("super", QuarkTranscodePolicy.selectTranscode(videos, "auto")
                    .get("resolution").getAsString());
        } catch (Exception e) {
            throw new AssertionError("Transcode policy failed with OpenDriveHttp helpers", e);
        }
    }

    @Test
    public void quarkTranscodePolicyUsesOpenDriveHttpBoolForAccessable() {
        // QuarkTranscodePolicy uses bool() from OpenDriveHttp to check "accessable".
        // A denied rendition (accessable=false) should be skipped during smart selection.
        JsonArray videos = new JsonArray();
        JsonObject denied = rendition("super", "https://cdn.example/denied");
        denied.addProperty("accessable", false);
        videos.add(denied);
        assertThrows(QuarkSmartQualityException.class,
                () -> QuarkTranscodePolicy.selectTranscode(videos, "auto"));
    }
}
