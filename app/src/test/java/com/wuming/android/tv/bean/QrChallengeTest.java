package com.wuming.android.tv.bean;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class QrChallengeTest {

    @Test
    public void nullInputHasNoImageAndInstancesKeepIdentityEquality() {
        QrChallenge challenge = QrChallenge.from(null);
        assertFalse(challenge.hasImage());
        assertEquals("", challenge.getImageBase64());
        assertEquals("", challenge.getImageUrl());
        assertFalse(challenge.equals(QrChallenge.from(null)));
    }

    @Test
    public void stripsLineBreaksAndPrefersDataImageOverRawQrData() {
        QrChallenge challenge = QrChallenge.from(
                "data:image/png;base64,YW\r\nJjZA== {\"qr_data\":\"ZGVm\"}");
        assertEquals("YWJjZA==", challenge.getImageBase64());
        assertEquals("YWJjZA==", QrChallenge.from("{\"qr_data\":\"YW\r\nJjZA==\"}").getImageBase64());
    }

    @Test
    public void base64LimitAppliesAfterRemovingLineBreaks() {
        String limit = "A".repeat(8 * 1024 * 1024);
        assertEquals(limit, QrChallenge.from("data:image/png;base64," + limit + "\r\n").getImageBase64());
        QrChallenge oversized = QrChallenge.from("data:image/png;base64," + limit + "A"
                + " {\"qr_data\":\"YWJjZA==\",\"qr_url\":\"https://example.com/qr\"}");
        assertEquals("", oversized.getImageBase64());
        assertEquals("https://example.com/qr", oversized.getImageUrl());
    }

    @Test
    public void urlNormalizationRetainsJavaTrimAndImagePriority() {
        assertEquals("", QrChallenge.from("{\"qr_url\":\"\u2003https://example.com/qr\u2003\"}").getImageUrl());
        assertEquals("https://example.com/image", QrChallenge.from(
                "<img src=' https://example.com/image '> {\"qr_url\":\"https://example.com/other\"}").getImageUrl());
        assertEquals("https://example.com/fallback", QrChallenge.from(
                "<img src='file:/tmp/image'> {\"qr_url\":\"https://example.com/fallback\"}").getImageUrl());
    }

    @Test
    public void extractsProviderSuppliedDataImage() {
        QrChallenge challenge = QrChallenge.from("need verify: <body><img src=\"data:image/jpeg;base64,YWJjZA==\"/></body>");

        assertTrue(challenge.hasImage());
        assertEquals("YWJjZA==", challenge.getImageBase64());
    }

    @Test
    public void extractsRemoteImageAndDecodesHtmlAmpersands() {
        QrChallenge challenge = QrChallenge.from("需要验证<img src='https://example.com/qr?id=1&amp;t=2'>");

        assertTrue(challenge.hasImage());
        assertEquals("https://example.com/qr?id=1&t=2", challenge.getImageUrl());
    }

    @Test
    public void ordinaryLoginMessageDoesNotInventQrCode() {
        QrChallenge challenge = QrChallenge.from("未登录，请重试");

        assertFalse(challenge.hasImage());
    }

    @Test
    public void baiduSchemeLessImgUrlUsesSecureEndpoint() {
        String response = "json-tangram_guid_1({\"imgurl\":\"passport.baidu.com\\/v2\\/api\\/qrcode?sign=abc&lp=pc\",\"errno\":0})";

        QrChallenge challenge = QrChallenge.from(response);

        assertTrue(challenge.hasImage());
        assertEquals("https://passport.baidu.com/v2/api/qrcode?sign=abc&lp=pc", challenge.getImageUrl());
    }

    @Test
    public void protocolRelativeQrUrlUsesHttps() {
        QrChallenge challenge = QrChallenge.from("{\"qr_url\":\"//passport.baidu.com/v2/api/qrcode?sign=abc\"}");

        assertEquals("https://passport.baidu.com/v2/api/qrcode?sign=abc", challenge.getImageUrl());
    }

    @Test
    public void schemeLessUnknownImageHostIsRejected() {
        QrChallenge challenge = QrChallenge.from("{\"imgurl\":\"attacker.example/track.png\"}");

        assertFalse(challenge.hasImage());
    }

    @Test
    public void extractsRawProviderQrDataResponse() {
        QrChallenge challenge = QrChallenge.from("{\"query_token\":\"session\",\"qr_data\":\"YWJjZA==\"}");

        assertTrue(challenge.hasImage());
        assertEquals("YWJjZA==", challenge.getImageBase64());
    }
}
