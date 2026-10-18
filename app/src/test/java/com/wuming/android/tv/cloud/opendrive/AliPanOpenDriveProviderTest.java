package com.wuming.android.tv.cloud.opendrive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.wuming.tv.core.cloud.OpenDrivePlayback;
import com.wuming.tv.core.playback.OpenDrivePlaybackHints;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.IOException;

public class AliPanOpenDriveProviderTest {

    @Test
    public void canonicalShareUrlPreservesValidatedPasscode() {
        assertEquals("https://www.alipan.com/s/Abc_123/folder/Fid_456?pwd=A1b2",
                AliPanOpenDriveProvider.canonicalShareUrl(
                        "https://www.aliyundrive.com/s/Abc_123/folder/Fid_456?pwd=A1b2#ignored"));
        assertNull(AliPanOpenDriveProvider.canonicalShareUrl(
                "https://www.alipan.com/s/Abc_123?pwd=bad%0Avalue"));
    }

    @Test
    public void sharePasscodeRejectsUnexpectedCharacters() {
        assertThrows(IllegalArgumentException.class, () ->
                AliPanOpenDriveProvider.sharePasscode(
                        "https://www.alipan.com/s/Abc_123?pwd=bad-value"));
    }

    @Test
    public void downloadUrlUsesEndpointSpecificOfficialField() {
        assertEquals("https://download.example/personal",
                AliPanOpenDriveProvider.downloadUrl(JsonParser.parseString(
                        "{\"url\":\"https://download.example/personal\","
                                + "\"download_url\":\"https://download.example/legacy\"}")
                        .getAsJsonObject(), false));
        assertEquals("https://download.example/shared",
                AliPanOpenDriveProvider.downloadUrl(JsonParser.parseString(
                        "{\"url\":\"https://download.example/fallback\","
                                + "\"download_url\":\"https://download.example/shared\"}")
                        .getAsJsonObject(), true));
    }

    @Test
    public void downloadUrlRetainsCompatibilityFallbacks() {
        assertEquals("https://download.example/legacy",
                AliPanOpenDriveProvider.downloadUrl(JsonParser.parseString(
                        "{\"download_url\":\"https://download.example/legacy\"}")
                        .getAsJsonObject(), false));
        assertEquals("https://download.example/fallback",
                AliPanOpenDriveProvider.downloadUrl(JsonParser.parseString(
                        "{\"url\":\"https://download.example/fallback\"}")
                        .getAsJsonObject(), true));
    }

    @Test
    public void originalAndSmartLabelsRemainDistinct() {
        assertFalse(AliPanOpenDriveProvider.smartPlayback("阿里原画"));
        assertFalse(AliPanOpenDriveProvider.smartPlayback("AliPan original"));
        assertTrue(AliPanOpenDriveProvider.smartPlayback("阿里智1"));
        assertTrue(AliPanOpenDriveProvider.smartPlayback("AliPan智2"));
        assertTrue(AliPanOpenDriveProvider.smartPlayback("线路", JsonParser.parseString(
                "{\"flag\":\"阿里智\"}").getAsJsonObject()));
    }

    @Test
    public void smartRequestUsesPreviewContractForPersonalAndSharedFiles() {
        JsonObject personal = AliPanOpenDriveProvider.previewBody(
                "drive-1", "", "file-1");
        assertEquals("drive-1", personal.get("drive_id").getAsString());
        assertFalse(personal.has("share_id"));
        assertEquals("live_transcoding", personal.get("category").getAsString());

        JsonObject shared = AliPanOpenDriveProvider.previewBody(
                "", "share-1", "file-1");
        assertEquals("share-1", shared.get("share_id").getAsString());
        assertFalse(shared.has("drive_id"));
        assertEquals(900, shared.get("url_expire_sec").getAsInt());
    }

    @Test
    public void smartFixtureSelectsBestFinishedRenditionAtOrBelow1080p() {
        JsonObject response = JsonParser.parseString("{\"video_preview_play_info\":{"
                + "\"live_transcoding_task_list\":["
                + "{\"template_id\":\"264_480p\",\"status\":\"finished\","
                + "\"url\":\"https://cdn.example/480.m3u8\"},"
                + "{\"template_id\":\"264_1080p\",\"status\":\"finished\","
                + "\"url\":\"https://cdn.example/1080.m3u8\"},"
                + "{\"template_id\":\"265_4K\",\"status\":\"finished\","
                + "\"url\":\"https://cdn.example/4k.m3u8\"},"
                + "{\"template_id\":\"264_720p\",\"status\":\"running\","
                + "\"url\":\"https://cdn.example/running.m3u8\"}]}}")
                .getAsJsonObject();

        assertEquals("https://cdn.example/1080.m3u8",
                AliPanOpenDriveProvider.previewUrl(response));
        assertEquals("", AliPanOpenDriveProvider.previewUrl(JsonParser.parseString(
                "{\"video_preview_play_info\":{\"live_transcoding_task_list\":[]}}")
                .getAsJsonObject()));
    }

    @Test
    public void originalResolverUsesOnlySharedDownloadContractOffline() throws Exception {
        AliPanOpenDriveProvider provider = new AliPanOpenDriveProvider((url, body, headers) -> {
            assertTrue(url.endsWith("/v2/file/get_download_url"));
            assertEquals("share-1", body.get("share_id").getAsString());
            assertEquals("file-1", body.get("file_id").getAsString());
            assertEquals("share-token==", headers.get("x-share-token"));
            return JsonParser.parseString(
                    "{\"download_url\":\"https://cdn.example/original.mp4\"}")
                    .getAsJsonObject();
        });
        String handle = provider.importExternal("阿里原1", sharedPayload());

        OpenDrivePlayback playback = provider.resolve(handle);

        assertEquals("https://cdn.example/original.mp4", playback.url());
        assertFalse(OpenDrivePlaybackHints.enabled(
                playback.headers(), OpenDrivePlaybackHints.HLS));
    }

    @Test
    public void smartResolverUsesOnlyPreviewContractOffline() throws Exception {
        AliPanOpenDriveProvider provider = new AliPanOpenDriveProvider((url, body, headers) -> {
            assertTrue(url.endsWith("/v2/file/get_share_link_video_preview_play_info"));
            assertEquals("share-1", body.get("share_id").getAsString());
            assertEquals("live_transcoding", body.get("category").getAsString());
            assertTrue(body.get("get_preview_url").getAsBoolean());
            assertEquals("", body.get("template_id").getAsString());
            assertFalse(body.has("drive_id"));
            assertEquals("share-token==", headers.get("x-share-token"));
            return JsonParser.parseString("{\"video_preview_play_info\":{"
                    + "\"live_transcoding_task_list\":[{"
                    + "\"template_id\":\"264_720p\",\"status\":\"finished\","
                    + "\"url\":\"https://cdn.example/smart.m3u8\"}]}}")
                    .getAsJsonObject();
        });
        String handle = provider.importExternal("阿里智1", sharedPayload());

        OpenDrivePlayback playback = provider.resolve(handle);

        assertEquals("https://cdn.example/smart.m3u8", playback.url());
        assertTrue(OpenDrivePlaybackHints.enabled(
                playback.headers(), OpenDrivePlaybackHints.HLS));
    }

    @Test
    public void smartResolverNeverFallsBackToOriginalOffline() {
        AliPanOpenDriveProvider provider = new AliPanOpenDriveProvider((url, body, headers) -> {
            assertTrue(url.endsWith("/v2/file/get_share_link_video_preview_play_info"));
            return JsonParser.parseString(
                    "{\"video_preview_play_info\":{\"live_transcoding_task_list\":[]}}")
                    .getAsJsonObject();
        });
        String handle = provider.importExternal("阿里智1", sharedPayload());

        var error = assertThrows(com.wuming.tv.core.cloud.OpenDriveFailure.class, () -> provider.resolve(handle));
        assertEquals(com.wuming.tv.core.cloud.OpenDriveFailure.Reason.TRANSCODE_UNAVAILABLE, error.reason());
    }

    @Test
    public void sharedPreviewUsesMeasuredNamedTemplatesAndPreviewUrl() {
        JsonObject response = JsonParser.parseString("""
                {"video_preview_play_info":{"live_transcoding_task_list":[
                  {"template_id":"SD","status":"finished","preview_url":"https://cdn.example/sd"},
                  {"template_id":"HD","status":"finished","preview_url":"https://cdn.example/hd"},
                  {"template_id":"FHD","status":"finished","template_width":1920,
                   "template_height":1080,"preview_url":"https://cdn.example/fhd"},
                  {"template_id":"4K","status":"finished","preview_url":"https://cdn.example/4k"}
                ]}}
                """).getAsJsonObject();
        assertEquals("https://cdn.example/fhd", AliPanOpenDriveProvider.previewUrl(response));
    }

    @Test
    public void sharedPreviewRejectsPendingUnsafeAndOversizedRenditions() {
        JsonObject response = JsonParser.parseString("""
                {"video_preview_play_info":{"live_transcoding_task_list":[
                  {"template_id":"FHD","status":"finished","template_width":3840,
                   "template_height":2160,"preview_url":"https://cdn.example/oversized"},
                  {"template_id":"HD","status":"running","preview_url":"https://cdn.example/pending"},
                  {"template_id":"SD","status":"finished","preview_url":"http://cdn.example/unsafe"},
                  {"template_id":"LD","status":"finished","preview_url":"https://cdn.example/low"}
                ]}}
                """).getAsJsonObject();
        assertEquals("https://cdn.example/low", AliPanOpenDriveProvider.previewUrl(response));
    }

    @Test
    public void expiredSharedAccountRefreshesBeforeListingAndPersistsDeadline() throws Exception {
        var credential = new java.util.concurrent.atomic.AtomicReference<>(accountToken("old", "2000-01-01T00:00:00Z"));
        var calls = new java.util.ArrayList<String>();
        AliPanOpenDriveProvider provider = new AliPanOpenDriveProvider(null, (url, body, headers) -> {
            calls.add(url.substring(url.lastIndexOf('/') + 1));
            if (url.endsWith("get_share_token")) return json("{\"share_token\":\"share-token\"}");
            if (url.endsWith("get_share_by_anonymous")) return json("{\"file_infos\":[]}");
            if (url.endsWith("/token")) {
                assertEquals("refresh_token", body.get("grant_type").getAsString());
                return json(accountToken("fresh", ""));
            }
            assertEquals("Bearer " + "fresh".repeat(6), headers.get("Authorization"));
            assertEquals("share-token", headers.get("x-share-token"));
            return json("{\"items\":[]}");
        }, credential::get, credential::set);
        provider.listShare("https://www.alipan.com/s/abcdef123");
        assertEquals(java.util.List.of("get_share_token", "get_share_by_anonymous", "token", "list"), calls);
        assertTrue(json(credential.get()).has("expire_time"));
        // A new adapter/process must reuse the persisted deadline, not refresh again.
        AliPanOpenDriveProvider reopened = new AliPanOpenDriveProvider(null, (url, body, headers) -> {
            assertTrue(url.endsWith("get_download_url"));
            assertEquals("Bearer " + "fresh".repeat(6), headers.get("Authorization"));
            return json("{\"url\":\"https://cdn.example/original.mp4\"}");
        }, credential::get, credential::set);
        reopened.resolve(reopened.importExternal("阿里原1", sharedPayload()));
    }

    @Test
    public void sharedResolverRefreshesRejectedTokenOnceWithoutChangingRoute() throws Exception {
        var credential = new java.util.concurrent.atomic.AtomicReference<>(accountToken("old", "2099-01-01T00:00:00Z"));
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var refreshes = new java.util.concurrent.atomic.AtomicInteger();
        AliPanOpenDriveProvider provider = new AliPanOpenDriveProvider(null, (url, body, headers) -> {
            if (url.endsWith("/token")) {
                refreshes.incrementAndGet();
                return json(accountToken("fresh", "2099-01-01T00:00:00Z"));
            }
            assertTrue(url.endsWith("get_share_link_video_preview_play_info"));
            assertEquals("share-token==", headers.get("x-share-token"));
            if (requests.getAndIncrement() == 0) throw new OpenDriveHttp.StatusException(401);
            assertEquals("Bearer " + "fresh".repeat(6), headers.get("Authorization"));
            return json("{\"video_preview_play_info\":{\"live_transcoding_task_list\":["
                    + "{\"template_id\":\"HD\",\"status\":\"finished\",\"preview_url\":\"https://cdn.example/smart\"}]}}");
        }, credential::get, credential::set);
        assertEquals("https://cdn.example/smart", provider.resolve(
                provider.importExternal("阿里智1", sharedPayload())).url());
        assertEquals(2, requests.get());
        assertEquals(1, refreshes.get());
    }

    @Test
    public void authRetryIsBoundedAndNeverRetriesForbiddenOrGone() {
        for (int status : new int[]{401, 403, 410}) {
            var credential = new java.util.concurrent.atomic.AtomicReference<>(accountToken("old", "2099-01-01T00:00:00Z"));
            var requests = new java.util.concurrent.atomic.AtomicInteger();
            var refreshes = new java.util.concurrent.atomic.AtomicInteger();
            AliPanOpenDriveProvider provider = new AliPanOpenDriveProvider(null, (url, body, headers) -> {
                if (url.endsWith("/token")) {
                    refreshes.incrementAndGet();
                    return json(accountToken("fresh", "2099-01-01T00:00:00Z"));
                }
                requests.incrementAndGet();
                throw new OpenDriveHttp.StatusException(status);
            }, credential::get, credential::set);
            var error = assertThrows(OpenDriveHttp.StatusException.class,
                    () -> provider.resolve(provider.importExternal("阿里智1", sharedPayload())));
            assertEquals(status, error.code());
            assertEquals(status == 401 ? 2 : 1, requests.get());
            assertEquals(status == 401 ? 1 : 0, refreshes.get());
        }
    }

    @Test
    public void relativeOnlyStoredExpiryMustRefreshBeforeSharedPlayback() throws Exception {
        var credential = new java.util.concurrent.atomic.AtomicReference<>(accountToken("old", ""));
        var refreshed = new java.util.concurrent.atomic.AtomicBoolean();
        AliPanOpenDriveProvider provider = new AliPanOpenDriveProvider(null, (url, body, headers) -> {
            if (url.endsWith("/token")) {
                refreshed.set(true);
                return json(accountToken("fresh", "2099-01-01T00:00:00Z"));
            }
            assertTrue(refreshed.get());
            return json("{\"url\":\"https://cdn.example/original.mp4\"}");
        }, credential::get, credential::set);
        provider.resolve(provider.importExternal("阿里原1", sharedPayload()));
    }

    @Test
    public void forbiddenSharedOriginalSavesExactFileAndRemainsOriginal() throws Exception {
        var credential = new java.util.concurrent.atomic.AtomicReference<>(accountToken("fresh", "2099-01-01T00:00:00Z"));
        var calls = new java.util.ArrayList<String>();
        AliPanOpenDriveProvider provider = new AliPanOpenDriveProvider(null, (url, body, headers) -> {
            calls.add(url.substring(url.lastIndexOf('/') + 1));
            if (url.endsWith("get_download_url") && body.has("share_id"))
                throw new OpenDriveHttp.StatusException(403);
            if (url.endsWith("/list")) return json("{\"items\":[{\"file_id\":\"folder\",\"type\":\"folder\",\"name\":\"TVBoxCache\"}]}");
            if (url.endsWith("/batch")) {
                assertEquals("share-token==", headers.get("x-share-token"));
                return json("{\"responses\":[{\"status\":201,\"body\":{\"file_id\":\"saved-file\"}}]}");
            }
            assertTrue(url.endsWith("get_download_url"));
            assertFalse(headers.containsKey("x-share-token"));
            assertEquals("saved-file", body.get("file_id").getAsString());
            return json("{\"url\":\"https://cdn.example/copied-original.mp4\",\"download_url\":\"https://cdn.example/shared-priority.mp4\"}");
        }, credential::get, credential::set, new AliSharedOriginalCacheTest.Store());
        var result = provider.resolve(provider.importExternal("阿里原1", sharedPayload()));
        assertEquals("https://cdn.example/copied-original.mp4", result.url());
        assertEquals(OpenDrivePlaybackHints.FallbackReason.ALI_SHARED_ORIGINAL_FORBIDDEN,
                OpenDrivePlaybackHints.fallbackReason(result.headers()));
        assertFalse(OpenDrivePlaybackHints.enabled(result.headers(), OpenDrivePlaybackHints.HLS));
        assertEquals(java.util.List.of("get_download_url", "list", "batch", "get_download_url"), calls);
    }

    private static JsonObject json(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
    }

    private static String accountToken(String prefix, String expiry) {
        JsonObject value = new JsonObject();
        value.addProperty("access_token", prefix.repeat(6) + (prefix.equals("old") ? "12345" : ""));
        value.addProperty("refresh_token", "fixture-refresh-token-12345");
        value.addProperty("token_type", "Bearer");
        value.addProperty("default_drive_id", "fixture-drive");
        value.addProperty("expires_in", 7200);
        if (!expiry.isBlank()) value.addProperty("expire_time", expiry);
        return value.toString();
    }

    private static String sharedPayload() {
        return "{\"provider\":\"aliyun\",\"share_id\":\"share-1\","
                + "\"file_id\":\"file-1\",\"share_token\":\"share-token==\"}";
    }
}
