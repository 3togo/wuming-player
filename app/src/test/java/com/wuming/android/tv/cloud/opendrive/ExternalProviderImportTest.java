package com.wuming.android.tv.cloud.opendrive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class ExternalProviderImportTest {

    @Test
    public void ucImportsDelimitedAndJsonPayloadsOnlyWithUcAttribution() {
        UcOpenDriveProvider provider = new UcOpenDriveProvider();
        String delimited = "file_123++file-token==++share_123++share-token==";
        String json = "{\"fid\":\"file_123\",\"share_fid_token\":\"file-token==\","
                + "\"sId\":\"share_123\",\"sToken\":\"share-token==\",\"flag\":\"UC盘\"}";

        assertOpaque(provider.importExternal("UC原画", delimited), "file_123");
        assertOpaque(provider.importExternal("线路", json), "share-token");
        assertOpaque(provider.importExternal("优汐智1", json.replace("UC盘", "优汐盘")), "file-token");
        assertNull(provider.importExternal("夸父原1", delimited));
    }

    @Test
    public void aliyunImportsEstablishedPairAndRefreshableJsonEnvelope() {
        AliPanOpenDriveProvider provider = new AliPanOpenDriveProvider();

        assertOpaque(provider.importExternal("阿里原画", "share_123+file_456"), "share_123");
        assertOpaque(provider.importExternal("线路", "{\"provider\":\"aliyun\","
                + "\"share_id\":\"share_123\",\"file_id\":\"file_456\"}"), "file_456");
        assertNull(provider.importExternal("UC原画", "share_123+file_456"));
    }

    @Test
    public void pan123ImportsFivePartPayloadAndRejectsIncompleteMetadata() {
        Pan123OpenDriveProvider provider = new Pan123OpenDriveProvider();

        assertOpaque(provider.importExternal("pan123原画",
                "shareKey1++123456++s3-key/value++2048++etag-value"), "shareKey1");
        assertNull(provider.importExternal("pan123原画", "shareKey1++123456++missing"));
        assertNull(provider.importExternal("阿里原画",
                "shareKey1++123456++s3-key/value++2048++etag-value"));
    }

    @Test
    public void baiduImportsBase64PreviewAndTransferableOriginalJson() {
        BaiduOpenDriveProvider provider = new BaiduOpenDriveProvider();
        String preview = base64("{\"uk\":\"123456\",\"shareid\":\"654321\","
                + "\"fid\":\"998877\",\"surl\":\"AbcDef12\",\"qtype\":\"preview\"}");
        String original = base64("{\"uk\":\"123456\",\"shareid\":\"654321\","
                + "\"fid\":\"998877\",\"randsk\":\"secret\",\"qtype\":\"original\"}");

        assertOpaque(provider.importExternal("BD预览", preview), "998877");
        assertOpaque(provider.importExternal("BD原画", original), "998877");
        assertNull(provider.importExternal("UC原画", preview));
    }

    @Test
    public void quarkNoLongerClaimsUcShapedJson() {
        QuarkOpenDriveProvider provider = new QuarkOpenDriveProvider();
        String payload = "{\"fid\":\"file_123\",\"share_fid_token\":\"file-token\","
                + "\"sId\":\"share_123\",\"sToken\":\"share-token\",\"flag\":\"UC盘\"}";

        assertNull(provider.importExternal("UC原画", payload));
    }

    @Test
    public void tianyiImportsEstablishedPairAndValidatesShareHosts() {
        TianyiOpenDriveProvider provider = new TianyiOpenDriveProvider();

        assertOpaque(provider.importExternal("天翼原画", "123456++654321"), "123456");
        assertNull(provider.importExternal("UC原画", "123456++654321"));
        assertNotNull(TianyiOpenDriveProvider.canonicalShareUrl(
                "https://cloud.189.cn/web/share?code=AbcDef12"));
        assertNull(TianyiOpenDriveProvider.canonicalShareUrl(
                "https://cloud.189.cn.evil.example/web/share?code=AbcDef12"));
    }

    @Test
    public void tianyiUsesRootFileIdWhenCurrentShareMetadataOmitsShareId() {
        assertEquals("123456", TianyiOpenDriveProvider.shareId(
                JsonParser.parseString("{\"fileId\":\"123456\"}").getAsJsonObject()));
        assertEquals("654321", TianyiOpenDriveProvider.shareId(
                JsonParser.parseString("{\"shareId\":\"654321\",\"fileId\":\"123456\"}")
                        .getAsJsonObject()));
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertOpaque(String handle, String secret) {
        assertNotNull(handle);
        assertFalse(handle.isBlank());
        assertFalse(handle.contains(secret));
    }
}
