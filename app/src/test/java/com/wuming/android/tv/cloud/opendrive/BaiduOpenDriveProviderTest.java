package com.wuming.android.tv.cloud.opendrive;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.io.IOException;

import com.wuming.tv.core.cloud.OpenDriveFailure;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

import okhttp3.Cookie;
import okhttp3.HttpUrl;

import org.junit.Test;

public class BaiduOpenDriveProviderTest {

    @Test
    public void collisionReuseRequiresMatchingHashSizeAndCacheLocation() {
        String hash = "0123456789abcdef0123456789abcdef";
        JsonArray files = new com.google.gson.Gson().fromJson("[{\"path\":\"/TVBoxOSC_OpenDrive/sample.mp4\","
                + "\"isdir\":0,\"size\":123,\"md5\":\"" + hash + "\"}]", JsonArray.class);
        assertEquals("/TVBoxOSC_OpenDrive/sample.mp4", BaiduOpenDriveProvider.matchingTransferPath(files, hash, 123));
        assertEquals("", BaiduOpenDriveProvider.matchingTransferPath(files, hash, 124));
        assertEquals("", BaiduOpenDriveProvider.matchingTransferPath(files, "f".repeat(32), 123));
        assertEquals("", BaiduOpenDriveProvider.matchingTransferPath(files, null, 123));
        files.get(0).getAsJsonObject().addProperty("path", "/personal/sample.mp4");
        assertEquals("", BaiduOpenDriveProvider.matchingTransferPath(files, hash, 123));
        files.get(0).getAsJsonObject().addProperty("path", "/TVBoxOSC_OpenDrive/../personal/sample.mp4");
        assertEquals("", BaiduOpenDriveProvider.matchingTransferPath(files, hash, 123));
    }

    @Test
    public void parsesHtmlEscapedQrConfirmationJsonp() throws Exception {
        JsonObject root = BaiduOpenDriveProvider.parseBaiduJsonp(
                "bd__cbs__opendrive({&quot;errInfo&quot;:{&quot;no&quot;:&quot;0&quot;}})");
        assertEquals("0", root.getAsJsonObject("errInfo").get("no").getAsString());
    }

    @Test
    public void accountVerificationRequiresSuccessfulPersonalListEnvelope() {
        JsonObject valid = new JsonObject();
        valid.addProperty("errno", 0);
        valid.add("list", new com.google.gson.JsonArray());
        assertTrue(BaiduOpenDriveProvider.validAccountResponse(valid));

        valid.addProperty("errno", -6);
        assertFalse(BaiduOpenDriveProvider.validAccountResponse(valid));
        assertFalse(BaiduOpenDriveProvider.validAccountResponse(new JsonObject()));
    }

    private final BaiduOpenDriveProvider provider = new BaiduOpenDriveProvider();

    @Test
    public void validatedBaiduSchemaDoesNotDependOnAggregatorLabel() {
        String payload = "{"
                + "\"flag\":\"嘟嘟盘\","
                + "\"fs_id\":\"123456789012345\","
                + "\"uk\":\"1234567890\","
                + "\"share_id\":\"12345678901\","
                + "\"surl\":\"1AbCdEfGhIjKlMnOpQrStUv\","
                + "\"seKey\":\"validated-share-capability==\""
                + "}";

        assertNotNull(provider.importExternal("嘟嘟无限2", payload));
    }

    @Test
    public void genericUnlabeledPayloadDoesNotRouteToBaidu() {
        assertNull(provider.importExternal("Generic", "{\"fs_id\":\"123\"}"));
    }

    @Test
    public void acceptsReferenceOriginalPayloadWithoutPreviewSurl() {
        String payload = "{"
                + "\"fid\":\"123456789012345\","
                + "\"uk\":\"1234567890\","
                + "\"shareid\":\"12345678901\","
                + "\"randsk\":\"encoded%2Fshare%2Bkey%3D%3D\","
                + "\"qtype\":\"original\""
                + "}";

        assertNotNull(provider.importExternal("BD原画", payload));
        assertTrue(BaiduOpenDriveProvider.isOriginalQuality("original"));
        assertFalse(BaiduOpenDriveProvider.isOriginalQuality("preview"));
    }

    @Test
    public void referenceAppSignatureHelpersPreservePlusAndDecodePercent() {
        assertEquals("encoded/share+key==", BaiduOpenDriveProvider.percentDecode(
                "encoded%2Fshare%2Bkey%3D%3D"));
        assertEquals("literal+plus", BaiduOpenDriveProvider.percentDecode("literal+plus"));
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d",
                BaiduOpenDriveProvider.sha1Hex("abc"));
    }

    @Test
    public void originalFallbackIsLimitedToObservedPreviewRejection() {
        assertTrue(BaiduOpenDriveProvider.previewRejected("{\"errno\":71}"));
        assertFalse(BaiduOpenDriveProvider.previewRejected("{\"errno\":0}"));
        assertFalse(BaiduOpenDriveProvider.previewRejected("#EXTM3U\n#EXT-X-VERSION:3"));
        assertFalse(BaiduOpenDriveProvider.previewRejected("not-json"));
    }

    @Test
    public void originalAndPreviewStreamingContractsRemainDistinct() {
        assertTrue(BaiduOpenDriveProvider.isOriginalQuality("original"));
        assertFalse(BaiduOpenDriveProvider.isOriginalQuality("preview"));
        assertTrue(BaiduOpenDriveProvider.shouldUseOriginalRanges("original", false));
        assertFalse(BaiduOpenDriveProvider.shouldUseOriginalRanges("preview", false));

        HttpUrl preview = HttpUrl.parse(BaiduOpenDriveProvider.shareStreamUrl(
                "123", "456", "opaque-sign", "789", "1011",
                "page-token", 1));
        assertNotNull(preview);
        assertEquals("M3U8_AUTO_1080", preview.queryParameter("type"));
        assertEquals("page-token", preview.queryParameter("jsToken"));
    }

    @Test
    public void boundedRangeProbeRequiresExactFirstByteAndKnownTotal() {
        assertEquals(5L * 1024L * 1024L * 1024L,
                BaiduOpenDriveProvider.contentRangeTotal("bytes 0-0/5368709120"));
        assertEquals(0L, BaiduOpenDriveProvider.contentRangeTotal("bytes 0-1/5368709120"));
        assertEquals(0L, BaiduOpenDriveProvider.contentRangeTotal("bytes 0-0/*"));
    }

    @Test
    public void quotaAndFallbackHelpersAre64BitAndCacheScoped() {
        JsonObject quota = com.google.gson.JsonParser.parseString(
                "{\"errno\":0,\"total\":10737418240,\"used\":5368709120}")
                .getAsJsonObject();
        JsonArray files = com.google.gson.JsonParser.parseString("["
                + "{\"path\":\"/TVBoxOSC_OpenDrive/episode.mp4\"},"
                + "{\"path\":\"/Personal/never-delete.mp4\"},"
                + "{\"path\":\"/TVBoxOSC_OpenDrive\"}]").getAsJsonArray();

        assertEquals(5L * 1024L * 1024L * 1024L,
                BaiduOpenDriveProvider.availableCapacity(quota));
        assertEquals(-1L, BaiduOpenDriveProvider.availableCapacity(new JsonObject()));
        assertEquals(List.of("/TVBoxOSC_OpenDrive/episode.mp4"),
                BaiduOpenDriveProvider.transferCachePaths(files));
        assertEquals("[\"/TVBoxOSC_OpenDrive/episode.mp4\"]",
                BaiduOpenDriveProvider.deleteFileList(
                        List.of("/TVBoxOSC_OpenDrive/episode.mp4")));
        assertTrue(BaiduOpenDriveProvider.shouldUseOriginalRanges("preview", true));
        assertTrue(BaiduOpenDriveProvider.shouldUseOriginalRanges("original", false));
        assertFalse(BaiduOpenDriveProvider.shouldUseOriginalRanges("preview", false));
    }

    @Test
    public void transferUsesCollisionRenameAndPostsFileAndDestination() {
        HttpUrl url = BaiduOpenDriveProvider.transferUrl("123", "456");
        assertEquals("newcopy", url.queryParameter("ondup"));
        assertEquals("123", url.queryParameter("from"));
        assertEquals("456", url.queryParameter("shareid"));
        assertNull(url.queryParameter("path"));
        assertNull(url.queryParameter("fsidlist"));
        assertEquals("/TVBoxOSC_OpenDrive",
                BaiduOpenDriveProvider.transferForm("123456789012345").get("path"));
        assertEquals("[123456789012345]",
                BaiduOpenDriveProvider.transferForm("123456789012345").get("fsidlist"));
        JsonObject renamed = com.google.gson.JsonParser.parseString(
                "{\"errno\":0,\"extra\":{\"list\":[{\"to\":\"/TVBoxOSC_OpenDrive/E01(1).mp4\"}]}}")
                .getAsJsonObject();
        assertEquals("/TVBoxOSC_OpenDrive/E01(1).mp4",
                BaiduOpenDriveProvider.transferPath(renamed));
    }

    @Test
    public void parsesTransferPathFromObjectAndEncodedObjectEnvelopes() {
        JsonObject direct = com.google.gson.JsonParser.parseString(
                "{\"errno\":0,\"extra\":{\"list\":[{\"to\":\"/TV/file.mp4\"}]}}")
                .getAsJsonObject();
        JsonObject encoded = new JsonObject();
        encoded.addProperty("extra", "{\"list\":[{\"to\":\"TV/large.mp4\"}]}");

        assertEquals("/TV/file.mp4", BaiduOpenDriveProvider.transferPath(direct));
        assertEquals("TV/large.mp4", BaiduOpenDriveProvider.transferPath(encoded));
        assertEquals("", BaiduOpenDriveProvider.transferPath(new JsonObject()));
    }

    @Test
    public void onlyRetriesBoundedLargeTransferTimeouts() {
        assertTrue(BaiduOpenDriveProvider.retryableTransferError(1504L, 1));
        assertTrue(BaiduOpenDriveProvider.retryableTransferError(1504L, 2));
        assertFalse(BaiduOpenDriveProvider.retryableTransferError(1504L, 3));
        assertFalse(BaiduOpenDriveProvider.retryableTransferError(0L, 1));
        assertFalse(BaiduOpenDriveProvider.retryableTransferError(-6L, 1));
        assertFalse(BaiduOpenDriveProvider.retryableTransferError(111L, 1));
    }

    @Test
    public void extractsCurrentEncodedSharePageToken() {
        String script = "function fn(a){window.jsToken = a};fn(\"token+value/42\")";
        String encoded = URLEncoder.encode(script, StandardCharsets.UTF_8).replace("+", "%20");
        String html = "<script>eval(decodeURIComponent('" + encoded + "'))</script>";

        assertEquals("token+value/42", BaiduOpenDriveProvider.extractJsToken(html));
    }

    @Test
    public void extractsDirectSharePageToken() {
        assertEquals("direct-token", BaiduOpenDriveProvider.extractJsToken(
                "<script>window.jsToken='direct-token'</script>"));
    }

    @Test
    public void shareStreamAddsCurrentOfficialPagePlaybackFields() {
        HttpUrl url = HttpUrl.parse(BaiduOpenDriveProvider.shareStreamUrl(
                "123", "456", "opaque-sign", "789", "1011"));

        assertNotNull(url);
        assertEquals("chunlei", url.queryParameter("channel"));
        assertEquals("123", url.queryParameter("uk"));
        assertEquals("456", url.queryParameter("fid"));
        assertEquals("opaque-sign", url.queryParameter("sign"));
        assertEquals("789", url.queryParameter("timestamp"));
        assertEquals("1011", url.queryParameter("shareid"));
        assertEquals("M3U8_AUTO_480", url.queryParameter("type"));
        assertEquals("0", url.queryParameter("vip"));
        assertEquals("undefined", url.queryParameter("jsToken"));
    }

    @Test
    public void usesOfficialJavascriptValueWhenSharePageOmitsToken() {
        assertEquals("undefined", BaiduOpenDriveProvider.playbackToken(""));
        assertEquals("page-token", BaiduOpenDriveProvider.playbackToken("page-token"));
    }

    @Test
    public void derivesVipLevelFromTemplateCard() {
        JsonObject data = new JsonObject();
        JsonObject card = new JsonObject();
        card.addProperty("is_vip", 1);
        data.add("card_info", card);
        assertEquals(1, BaiduOpenDriveProvider.vipValue(data));
        card.addProperty("is_svip", 1);
        assertEquals(2, BaiduOpenDriveProvider.vipValue(data));
    }

    @Test
    public void shareStreamDoesNotDoubleEncodeProviderSign() {
        HttpUrl url = HttpUrl.parse(BaiduOpenDriveProvider.shareStreamUrl(
                "123", "456", "opaque%2Bsign%2Fvalue", "789", "1011"));

        assertNotNull(url);
        assertTrue(url.encodedQuery().contains("sign=opaque%2Bsign%2Fvalue"));
        assertFalse(url.encodedQuery().contains("%252B"));
    }

    @Test
    public void currentShareCookieReplacesStaleShareCookie() {
        Cookie account = new Cookie.Builder().name("BDUSS").value("account-session")
                .domain("baidu.com").path("/").build();
        Cookie staleShare = new Cookie.Builder().name("BDCLND").value("stale-share")
                .domain("baidu.com").path("/").build();

        String header = BaiduOpenDriveProvider.mergeCookieHeader(
                List.of(account, staleShare), "current-share");

        assertTrue(header.contains("BDUSS=account-session"));
        assertTrue(header.contains("BDCLND=current-share"));
        assertFalse(header.contains("stale-share"));
        assertEquals(header.indexOf("BDCLND="), header.lastIndexOf("BDCLND="));
    }

    @Test
    public void accountCookieHeaderExcludesShareScopedCookie() {
        Cookie account = new Cookie.Builder().name("BDUSS").value("account-session")
                .domain("baidu.com").path("/").build();
        Cookie staleShare = new Cookie.Builder().name("BDCLND").value("stale-share")
                .domain("baidu.com").path("/").build();

        String header = BaiduOpenDriveProvider.mergeCookieHeader(
                List.of(account, staleShare), "");

        assertEquals("BDUSS=account-session", header);
    }

    @Test
    public void qrResponseCookiesBecomeOneAccountTokenLikeReferenceHandler() {
        assertEquals("BDUSS=account-session; BAIDUID=browser-session; STOKEN=passport-session",
                BaiduOpenDriveProvider.normalizeAccountCookieToken(
                        "BDUSS=account-session; BAIDUID=browser-session; "
                                + "BDCLND=old-share; STOKEN=passport-session"));
    }

    @Test
    public void tokenWithoutBaiduAccountCookieIsNotAccepted() {
        assertEquals("", BaiduOpenDriveProvider.normalizeAccountCookieToken(
                "BAIDUID=browser-session; STOKEN=passport-session"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shareCookieRejectsHeaderInjection() {
        BaiduOpenDriveProvider.mergeCookieHeader(List.of(), "valid; injected=value");
    }

    @Test
    public void externalPayloadCachesDirectAndDecodedJson() {
        OpenDriveExternalPayload direct = OpenDriveExternalPayload.create(
                "baidu", "{\"fid\":\"123\"}");
        assertSame(direct.json(false), direct.json(true));
        assertSame(direct.json(false), direct.json(false));

        String encoded = Base64.getEncoder().encodeToString(
                "{\"fid\":\"456\"}".getBytes(StandardCharsets.UTF_8));
        OpenDriveExternalPayload deferred = OpenDriveExternalPayload.create("baidu", encoded);
        assertSame(deferred.json(true), deferred.json(true));
    }

    @Test
    public void baiduQuotaExceptionIsAnOpenDriveFailureWithQuotaReason() {
        var quota = new BaiduOpenDriveProvider.BaiduQuotaException("insufficient space");
        assertTrue(quota instanceof OpenDriveFailure);
        assertEquals(OpenDriveFailure.Reason.QUOTA_EXHAUSTED, quota.reason());
        assertEquals("baidu", quota.provider());
        assertEquals(0, quota.httpStatus());
        assertEquals(0L, quota.apiCode());
    }

    @Test
    public void baiduQuotaExceptionWithCausePreservesCauseInOpenDriveFailure() {
        var cause = new IOException("root cause");
        var quota = new BaiduOpenDriveProvider.BaiduQuotaException("insufficient space", cause);
        assertSame(cause, quota.getCause());
        assertEquals(OpenDriveFailure.Reason.QUOTA_EXHAUSTED, quota.reason());
    }
}
