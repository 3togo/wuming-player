package com.wuming.android.tv.cloud.opendrive;

import com.wuming.tv.core.playback.OpenDrivePlaybackHints;
import com.wuming.android.tv.cloud.CloudCacheRecord;
import com.wuming.android.tv.cloud.CloudStorageProvider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Set;

import org.junit.Test;

public class UcOpenDriveProviderTest {

    @Test
    public void quotaCapacityUses64BitValuesAndRejectsIncompleteResponses() {
        JsonObject quota = com.google.gson.JsonParser.parseString("{\"data\":{"
                + "\"total_capacity\":10737418240,\"use_capacity\":5368709120}}")
                .getAsJsonObject();

        assertEquals(5L * 1024L * 1024L * 1024L,
                UcOpenDriveProvider.availableCapacity(quota));
        assertEquals(-1L, UcOpenDriveProvider.availableCapacity(new JsonObject()));
    }

    @Test
    public void recycleRecoveryIncludesPreviouslyDeletedPersistentRecords() {
        CloudCacheRecord previous = CloudCacheRecord.create(
                CloudStorageProvider.UC, "account", "folder", "old-recycle-fid",
                "cache", "builtin_opendrive", 1L);
        previous.state = CloudCacheRecord.DELETED;
        Set<String> owned = UcOpenDriveProvider.ownedRecycleFids(
                Set.of("new-recycle-fid"), List.of(previous));
        JsonArray recycle = JsonParser.parseString("["
                + "{\"fid\":\"old-recycle-fid\",\"record_id\":\"old-record\"},"
                + "{\"fid\":\"new-recycle-fid\",\"record_id\":\"new-record\"},"
                + "{\"fid\":\"personal\",\"record_id\":\"personal-record\"}]")
                .getAsJsonArray();

        assertEquals(Set.of("old-recycle-fid", "new-recycle-fid"), owned);
        assertEquals(List.of("old-record", "new-record"),
                UcOpenDriveProvider.matchingRecycleRecordIds(recycle, owned));
    }

    @Test
    public void callbackDenialIsPreservedAsActionable403() throws Exception {
        OpenDriveProxyResponse response = UcOpenDriveProvider.upstreamFailure(
                403, "RequestDeniedByCallback");
        String expected = "UC rejected this download authorization. Re-login to UC or use another source.";

        assertEquals(403, response.getStatus());
        assertEquals(expected, response.getHeaders().get("X-OpenDrive-Error"));
        assertEquals(expected, new String(response.getStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    public void signedDownloadRequestMatchesUzVideoPlayHeaderContract() {
        UcOpenDriveProvider provider = newProvider();
        java.lang.reflect.Method method = null;
        try {
            method = UcOpenDriveProvider.class.getDeclaredMethod(
                    "downloadHeaders", String.class);
            method.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("downloadHeaders is missing on UcOpenDriveProvider", e);
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> headers = (Map<String, String>) method.invoke(
                    provider, "__puus=request-time-cookie");
            assertEquals("*/*", headers.get("Accept"));
            assertEquals("https://drive.uc.cn/", headers.get("Referer"));
            assertTrue(headers.get("User-Agent").contains("uc-cloud-drive/2.5.20"));
            assertEquals("__puus=request-time-cookie", headers.get("Cookie"));
            assertFalse(headers.containsKey("Origin"));
            assertFalse(headers.containsKey("Content-Type"));
        } catch (Exception e) {
            throw new AssertionError("downloadHeaders cannot be invoked", e);
        }
    }

    private static UcOpenDriveProvider newProvider() {
        try {
            java.lang.reflect.Constructor<UcOpenDriveProvider> ctor =
                    UcOpenDriveProvider.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            throw new AssertionError("UcOpenDriveProvider cannot be instantiated", e);
        }
    }

    @Test
    public void qrChallengeUsesUcFirstPartyAuthorization() {
        String value = UcOpenDriveProvider.qrContent("Abcd_efgh-12345678");
        assertTrue(value.startsWith("https://su.uc.cn/"));
        assertTrue(value.contains("token=Abcd_efgh-12345678"));
        assertTrue(value.contains("client_id=381"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void qrChallengeRejectsInjectedToken() {
        UcOpenDriveProvider.qrContent("safe&client_id=attacker");
    }

    @Test
    public void accountVerificationRequiresProviderDataEnvelope() {
        assertTrue(UcOpenDriveProvider.validAccountResponse(JsonParser.parseString(
                "{\"data\":{\"list\":[]}}").getAsJsonObject()));
        assertFalse(UcOpenDriveProvider.validAccountResponse(JsonParser.parseString(
                "{\"status\":401}").getAsJsonObject()));
    }

    @Test
    public void taskQueryPreservesReferenceParameterOrder() {
        LinkedHashMap<String, String> task = new LinkedHashMap<>();
        task.put("task_id", "task-123");
        task.put("retry_index", "0");

        String query = UcOpenDriveProvider.apiUrl("task", task).encodedQuery();

        assertEquals("pr=UCBrowser&fr=pc&task_id=task-123&retry_index=0",
                query);
    }

    @Test
    public void sharepageEndpointsUseUcTransferQuery() {
        for (String endpoint : List.of("share/sharepage/token", "share/sharepage/detail")) {
            String query = UcOpenDriveProvider.apiUrl(endpoint,
                    java.util.Collections.emptyMap()).encodedQuery();

            assertEquals("entry=ft&fr=pc&pr=UCBrowser", query);
            assertFalse(query.contains("uc_param_str"));
        }
    }

    @Test
    public void downloadEndpointMatchesLinkSwiftUcQueryShape() {
        okhttp3.HttpUrl url = UcOpenDriveProvider.apiUrl("file/download",
                java.util.Collections.emptyMap());

        assertEquals("entry=ft&fr=pc&pr=UCBrowser", url.encodedQuery());
        assertEquals("ft", url.queryParameter("entry"));
        assertEquals("UCBrowser", url.queryParameter("pr"));
        assertEquals("pc", url.queryParameter("fr"));
        assertFalse(url.queryParameterNames().contains("sys"));
        assertFalse(url.queryParameterNames().contains("ve"));
        assertFalse(url.queryParameterNames().contains("ut"));
        assertFalse(url.queryParameterNames().contains("uc_param_str"));
    }

    @Test
    public void shareSaveUsesUcTransferQuery() {
        okhttp3.HttpUrl url = UcOpenDriveProvider.apiUrl("share/sharepage/save",
                java.util.Collections.emptyMap());

        assertEquals("entry=update_share&pr=UCBrowser&fr=pc", url.encodedQuery());
        assertEquals("update_share", url.queryParameter("entry"));
        assertFalse(url.queryParameterNames().contains("uc_param_str"));
    }

    @Test
    public void shareTokenIsExplicitlyAuthorizedForTransfer() {
        com.google.gson.JsonObject body = UcOpenDriveProvider.shareTokenBody("share-1", "A1b2");

        assertEquals("share-1", body.get("pwd_id").getAsString());
        assertEquals("A1b2", body.get("passcode").getAsString());
        assertTrue(body.get("share_for_transfer").getAsBoolean());
    }

    @Test
    public void transferApiHeadersCarryUcBrowserOrigin() throws Exception {
        UcOpenDriveProvider provider = newProvider();
        java.lang.reflect.Method build = UcOpenDriveProvider.class.getDeclaredMethod(
                "headers", String.class, String.class);
        build.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) build.invoke(
                provider, "", "file/download");

        assertEquals("https://drive.uc.cn", headers.get("Origin"));
        assertEquals("application/json, text/plain, */*", headers.get("Accept"));
    }

    @Test
    public void shareDownloadBodyMatchesLinkSwiftContract() {
        JsonObject body = UcOpenDriveProvider.shareDownloadBody(
                "shared-fid", "fid-token", "share-id", "share-token");

        assertEquals("shared-fid", body.getAsJsonArray("fids").get(0).getAsString());
        assertEquals("fid-token",
                body.getAsJsonArray("fids_token").get(0).getAsString());
        assertEquals("share-id", body.get("pwd_id").getAsString());
        assertEquals("share-token", body.get("stoken").getAsString());
    }

    @Test
    public void sharePasscodeIsForwardedFromPwdQuery() {
        assertEquals("A1b2", UcOpenDriveProvider.sharePasscode(
                "https://drive.uc.cn/s/share123?pwd=A1b2"));
        assertEquals("", UcOpenDriveProvider.sharePasscode(
                "https://drive.uc.cn/s/share123"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void sharePasscodeRejectsUnexpectedCharacters() {
        UcOpenDriveProvider.sharePasscode("https://drive.uc.cn/s/share123?pwd=bad%0Avalue");
    }

    @Test
    public void nonSharepageNonDownloadEndpointKeepsOriginalQuery() {
        LinkedHashMap<String, String> sort = new LinkedHashMap<>();
        sort.put("pdir_fid", "0");

        String query = UcOpenDriveProvider.apiUrl("file/sort", sort).encodedQuery();

        assertTrue(query.startsWith("pr=UCBrowser&fr=pc&sys=darwin&ve=1.8.6"));
        assertTrue(query.contains("&ut=Nk27FcCv6q1eo6rXz8QHR/nIG6qLA3jh7KdL+agFgcOvww=="));
        assertTrue(query.contains("pdir_fid=0"));
        assertFalse(query.contains("uc_param_str="));
        assertFalse(query.contains("__dt="));
        assertFalse(query.contains("__t="));
    }

    @Test
    public void qrLoginCarriesTokenAndAccountCookiesAsOneExplicitHeader() {
        UcOpenDriveProvider.UcCookies cookies = new UcOpenDriveProvider.UcCookies();

        cookies.merge(List.of(
                "_UP_A4A_11_=pre-login; Path=/; HttpOnly",
                "_UP_D_=device; Domain=.uc.cn; Path=/"));
        cookies.merge(List.of(
                "__pus=session-value; Path=/; Secure; HttpOnly",
                "__puus=refresh-value; Path=/; Secure"));

        assertEquals("_UP_A4A_11_=pre-login; _UP_D_=device; __pus=session-value; __puus=refresh-value",
                cookies.header());
        assertTrue(cookies.has("__pus"));
    }

    @Test
    public void normalApiResponseRefreshesOnlyReferencePuuCookie() {
        UcOpenDriveProvider.UcCookies cookies = UcOpenDriveProvider.UcCookies.from(
                "_UP_D_=device;__pus=session-value;__puus=old-refresh");

        assertTrue(cookies.mergeNamed(List.of(
                "tracking=must-not-be-imported; Path=/",
                "__puus=new-refresh; Path=/; Secure"), "__puus"));

        assertEquals("_UP_D_=device; __pus=session-value; __puus=new-refresh",
                cookies.header());
        assertFalse(cookies.header().contains("tracking="));
    }

    @Test
    public void puusRefreshRequestPreservesEveryOtherCookie() {
        UcOpenDriveProvider.UcCookies cookies = UcOpenDriveProvider.UcCookies.from(
                "_UP_D_=device;__pus=session;__puus=expired;tracking=safe");

        String stripped = cookies.headerWithout("__puus");

        assertEquals("_UP_D_=device; __pus=session; tracking=safe", stripped);
        assertEquals("_UP_D_=device; __pus=session; __puus=expired; tracking=safe",
                cookies.header());
    }

    @Test
    public void cookieAccumulatorRejectsHeaderInjection() {
        UcOpenDriveProvider.UcCookies cookies = new UcOpenDriveProvider.UcCookies();

        cookies.mergePairs(List.of("__pus=good\r\nInjected: bad", "bad name=value"));

        assertFalse(cookies.has("__pus"));
        assertEquals("", cookies.header());
    }

    @Test
    public void downloadHeadersMatchUzVideoUcPlayHeaders() throws Exception {
        UcOpenDriveProvider provider = newProvider();
        java.lang.reflect.Method build = UcOpenDriveProvider.class.getDeclaredMethod(
                "downloadHeaders", String.class);
        build.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) build.invoke(
                provider, "_UP_D_=device; _UP_A4A_11_=a; __puus=f; __pugs=share; csrftoken=secret");
        String ua = headers.get("User-Agent");
        assertTrue("expected UC cloud-drive UA",
                ua != null && ua.contains("uc-cloud-drive"));
        assertTrue("expected LinkSwift UC play UA",
                ua != null && ua.contains("Windows NT 10.0"));
        assertTrue("expected anonymous share callback cookie",
                headers.get("Cookie").contains("__pugs=share"));
        assertTrue(headers.get("Cookie").contains("_UP_A4A_11_"));
        assertFalse(headers.get("Cookie").contains("csrftoken=secret"));
        assertFalse(headers.get("Cookie").contains("_UP_D_"));
    }

    @Test
    public void contentRangeTotalRejectsUnknownOrMalformedValues() {
        assertEquals(2_706_137_292L,
                UcOpenDriveProvider.contentRangeTotal("bytes 0-1023/2706137292"));
        assertEquals(0L, UcOpenDriveProvider.contentRangeTotal("bytes */*"));
        assertEquals(0L, UcOpenDriveProvider.contentRangeTotal(null));
    }

    @Test
    public void contentRangeTotalPreservesFilesAboveFourGiB() {
        assertEquals(6L * 1024L * 1024L * 1024L,
                UcOpenDriveProvider.contentRangeTotal("bytes 0-1023/6442450944"));
    }

    @Test
    public void savedFileTranscodeAvoidsRestrictedShareDownload() {
        UcOpenDriveProvider.PlaybackCandidate candidate =
                UcOpenDriveProvider.selectPlaybackCandidate(
                        "https://download.uc.cn/original.mp4",
                        "https://transcode.uc.cn/playlist.m3u8");

        assertEquals("https://transcode.uc.cn/playlist.m3u8", candidate.url());
        assertTrue(candidate.transcoded());
    }

    @Test
    public void originalAndSmartLabelsRemainDistinct() {
        assertFalse(UcOpenDriveProvider.smartPlayback("UC原画"));
        assertFalse(UcOpenDriveProvider.smartPlayback("优汐原1"));
        assertTrue(UcOpenDriveProvider.smartPlayback("UC智2"));
        assertTrue(UcOpenDriveProvider.smartPlayback("优汐智1"));
        assertTrue(UcOpenDriveProvider.smartPlayback("线路", JsonParser.parseString(
                "{\"flag\":\"UC智\"}").getAsJsonObject()));

        assertTrue(UcOpenDriveProvider.directSharePlayback(false, false));
        assertFalse(UcOpenDriveProvider.directSharePlayback(false, true));
        assertFalse(UcOpenDriveProvider.directSharePlayback(true, false));
    }

    @Test
    public void legacyMergedHandleRestoresAsSmartButNewOriginalStaysOriginal() {
        Gson gson = new Gson();
        UcOpenDriveProvider.FileRef legacy = gson.fromJson(
                "{\"fid\":\"legacy\",\"size\":123}", UcOpenDriveProvider.FileRef.class);
        assertTrue(legacy.smart());
        assertEquals(1, legacy.playbackModeVersion());

        UcOpenDriveProvider.FileRef original = new UcOpenDriveProvider.FileRef(
                "file", "token", "share", "share-token", "", false, false,
                false, 123L, 1);
        UcOpenDriveProvider.FileRef restored = gson.fromJson(
                gson.toJson(original), UcOpenDriveProvider.FileRef.class);
        assertFalse(restored.smart());
        assertEquals(1, restored.playbackModeVersion());
    }

    @Test
    public void smartFixtureChoosesLargestPlayableTranscode() {
        JsonObject response = JsonParser.parseString("{\"data\":{\"video_list\":["
                + "{\"video_info\":{\"width\":480,"
                + "\"url\":\"https://cdn.example/480.m3u8\"}},"
                + "{\"video_info\":{\"width\":1080,"
                + "\"url\":\"https://cdn.example/1080.m3u8\"}},"
                + "{\"video_info\":{\"width\":720,\"url\":\"http://unsafe\"}}]}}")
                .getAsJsonObject();

        String smartUrl = UcOpenDriveProvider.pickTranscodedUrl(response);
        UcOpenDriveProvider.PlaybackCandidate smart =
                UcOpenDriveProvider.selectPlaybackCandidate(
                        "https://cdn.example/original.mp4", smartUrl);
        assertEquals("https://cdn.example/1080.m3u8", smart.url());
        assertTrue(smart.transcoded());

        UcOpenDriveProvider.PlaybackCandidate original =
                UcOpenDriveProvider.selectPlaybackCandidate(
                        "https://cdn.example/original.mp4", "");
        assertEquals("https://cdn.example/original.mp4", original.url());
        assertFalse(original.transcoded());
    }

    @Test
    public void rawDownloadHeadersKeepTheRotatedAuthorizationCookie() throws Exception {
        UcOpenDriveProvider provider = newProvider();
        java.lang.reflect.Method build = UcOpenDriveProvider.class.getDeclaredMethod(
                "downloadHeaders", String.class);
        build.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) build.invoke(
                provider, "__pus=session; __puus=download-response");

        assertEquals("__pus=session;__puus=download-response", headers.get("Cookie"));
        assertFalse(headers.containsKey("Content-Type"));
    }

    @Test
    public void pdsPlaybackHeadersMatchObservedCatVodTransport() throws Exception {
        UcOpenDriveProvider provider = newProvider();
        java.lang.reflect.Method build = UcOpenDriveProvider.class.getDeclaredMethod(
                "downloadHeaders", String.class, String.class);
        build.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) build.invoke(provider,
                "__pus=session; __puus=download-response",
                "https://dl-uf-zb.pds.uc.cn/signed-media");

        assertEquals("https://dl-uf-zb.pds.uc.cn/", headers.get("Referer"));
        assertTrue(headers.get("User-Agent").contains("Chrome/148.0.0.0"));
        assertTrue(headers.get("User-Agent").contains("Linux aarch64"));
    }

    @Test
    public void pdsPlaybackCarriesExpectedSizeForBoundedTailRange() throws Exception {
        UcOpenDriveProvider provider = newProvider();
        java.lang.reflect.Method build = UcOpenDriveProvider.class.getDeclaredMethod(
                "downloadHeaders", String.class, String.class, long.class);
        build.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) build.invoke(provider,
                "__pus=session", "https://dl-uf-zb.pds.uc.cn/signed-media",
                2_710_694_996L);

        assertEquals("2710694996", headers.get("X-OpenDrive-Content-Length"));
        assertEquals(OpenDrivePlaybackHints.PROFILE_UC_PDS,
                headers.get("X-OpenDrive-Parallel-Range"));
    }

    @Test
    public void pdsPlaybackCarriesExpectedSizeAboveFourGiB() throws Exception {
        UcOpenDriveProvider provider = newProvider();
        java.lang.reflect.Method build = UcOpenDriveProvider.class.getDeclaredMethod(
                "downloadHeaders", String.class, String.class, long.class);
        build.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) build.invoke(provider,
                "__pus=session", "https://dl-uf-zb.pds.uc.cn/signed-media",
                5L * 1024L * 1024L * 1024L);

        assertEquals("5368709120", headers.get(OpenDrivePlaybackHints.CONTENT_LENGTH));
        assertEquals(OpenDrivePlaybackHints.PROFILE_UC_PDS,
                headers.get(OpenDrivePlaybackHints.PARALLEL_RANGE_PROFILE));
    }

    @Test
    public void playerCookieJarReceivesOnlyUcPlaybackCookies() {
        UcOpenDriveProvider.UcCookies cookies = UcOpenDriveProvider.UcCookies.from(
                "_UP_D_=device; __pus=session; __puus=download-response; tracking=ignored");

        List<okhttp3.Cookie> playerCookies = cookies.playCookies();

        assertEquals(List.of("__pus", "__puus"), playerCookies.stream()
                .map(okhttp3.Cookie::name).toList());
        assertTrue(playerCookies.stream().allMatch(cookie -> "uc.cn".equals(cookie.domain())));
    }

    @Test
    public void transcodingIsUsedOnlyWhenRawDownloadIsUnavailable() {
        UcOpenDriveProvider.PlaybackCandidate candidate =
                UcOpenDriveProvider.selectPlaybackCandidate(
                        "", "https://transcode.uc.cn/playlist.m3u8");

        assertEquals("https://transcode.uc.cn/playlist.m3u8", candidate.url());
        assertTrue(candidate.transcoded());
        assertNull(UcOpenDriveProvider.selectPlaybackCandidate("http://unsafe", ""));
    }

    @Test
    public void cacheCleanupIncludesEveryEntryInTheDedicatedUcFolder() {
        JsonObject first = JsonParser.parseString(
                "{\"fid\":\"saved-video\",\"dir\":false}").getAsJsonObject();
        JsonObject interrupted = JsonParser.parseString(
                "{\"fid\":\"interrupted-copy\",\"dir\":false}").getAsJsonObject();
        JsonObject missingId = JsonParser.parseString(
                "{\"file_name\":\"invalid\"}").getAsJsonObject();

        assertEquals(Set.of("saved-video", "interrupted-copy"),
                UcOpenDriveProvider.cacheFileIds(
                        List.of(first, interrupted, missingId)));
    }

    @Test
    public void cacheCleanupFindsEveryDuplicateAppOwnedFolder() {
        JsonObject first = JsonParser.parseString(
                "{\"fid\":\"cache-one\",\"dir\":true,\"file_name\":\"TVBoxCache\"}")
                .getAsJsonObject();
        JsonObject second = JsonParser.parseString(
                "{\"fid\":\"cache-two\",\"dir\":true,\"file_name\":\"TVBoxCache\"}")
                .getAsJsonObject();
        JsonObject personal = JsonParser.parseString(
                "{\"fid\":\"personal\",\"dir\":true,\"file_name\":\"Videos\"}")
                .getAsJsonObject();
        JsonObject sameNameFile = JsonParser.parseString(
                "{\"fid\":\"not-folder\",\"dir\":false,\"file_name\":\"TVBoxCache\"}")
                .getAsJsonObject();

        assertEquals(Set.of("cache-one", "cache-two"),
                UcOpenDriveProvider.cacheFolderIds(
                        List.of(first, second, personal, sameNameFile)));
    }

    @Test
    public void onlyUcPlay404ActivatesBoundedSavedFileReset() {
        IOException play404 = new IOException(
                "UC file/v2/play request failed (HTTP 404)",
                new OpenDriveHttp.StatusException(404));
        IOException download404 = new IOException(
                "UC file/download request failed (HTTP 404)",
                new OpenDriveHttp.StatusException(404));
        IOException play500 = new IOException(
                "UC file/v2/play request failed (HTTP 500)",
                new OpenDriveHttp.StatusException(500));

        assertTrue(UcOpenDriveProvider.playEndpointNotFound(play404));
        assertFalse(UcOpenDriveProvider.playEndpointNotFound(download404));
        assertFalse(UcOpenDriveProvider.playEndpointNotFound(play500));
    }

    @Test
    public void originalDownloadIsImmediateFallbackExceptForFirstPlay404() {
        IOException play404 = new IOException(
                "UC file/v2/play request failed (HTTP 404)",
                new OpenDriveHttp.StatusException(404));
        IOException play500 = new IOException(
                "UC file/v2/play request failed (HTTP 500)",
                new OpenDriveHttp.StatusException(500));
        String raw = "https://download.uc.cn/original.mp4";

        assertFalse(UcOpenDriveProvider.useRawAfterPlayFailure(play404, false, raw));
        assertTrue(UcOpenDriveProvider.useRawAfterPlayFailure(play404, true, raw));
        assertTrue(UcOpenDriveProvider.useRawAfterPlayFailure(play500, false, raw));
        assertFalse(UcOpenDriveProvider.useRawAfterPlayFailure(
                play500, true, "http://download.uc.cn/original.mp4"));
    }

    // --- Refactoring verification: UcOpenDriveProvider delegates array() to OpenDriveHttp ---

    @Test
    public void matchingRecycleRecordIdsUsesOpenDriveHttpArrayHelper() {
        // matchingRecycleRecordIds uses array() which now resolves to OpenDriveHttp.array()
        // via the refactoring. Verify it still parses recycle entries correctly.
        JsonArray recycle = JsonParser.parseString(
                "[{\"fid\":\"match-fid\",\"record_id\":\"match-rec\"}]").getAsJsonArray();
        java.util.Set<String> owned = java.util.Set.of("match-fid");
        java.util.List<String> result = UcOpenDriveProvider.matchingRecycleRecordIds(recycle, owned);
        assertEquals(1, result.size());
        assertEquals("match-rec", result.get(0));
    }

}
