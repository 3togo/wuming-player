package com.wuming.android.tv.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import org.junit.Test;

public class ProviderQrLauncherTest {

    @Test
    public void classifiesOnlyHttpsProviderDomains() {
        assertEquals(ProviderQrLauncher.Target.ALIPAN, ProviderQrLauncher.targetForPayload(
                "https://passport.aliyundrive.com/qrcodeCheck.htm?lgToken=test"));
        assertEquals(ProviderQrLauncher.Target.QUARK, ProviderQrLauncher.targetForPayload(
                "https://pan.quark.cn/qr/login"));
        assertEquals(ProviderQrLauncher.Target.UC, ProviderQrLauncher.targetForPayload(
                "https://drive.uc.cn/qr/login"));
        assertEquals(ProviderQrLauncher.Target.UC, ProviderQrLauncher.targetForPayload(
                "https://broccoli.uc.cn/apps/login?broken=%3&token=test"));
        assertEquals(ProviderQrLauncher.Target.BAIDU, ProviderQrLauncher.targetForPayload(
                "https://wappass.baidu.com/wp/api/login/qr"));
        assertEquals(ProviderQrLauncher.Target.PAN123, ProviderQrLauncher.targetForPayload(
                "https://www.123pan.com/qr/login?token=test"));
        assertEquals(ProviderQrLauncher.Target.PAN123, ProviderQrLauncher.targetForPayload(
                "https://login.123pan.cn/qr?token=test"));
        assertEquals(ProviderQrLauncher.Target.TIANYI, ProviderQrLauncher.targetForPayload(
                "https://cloud.189.cn/web/login.html"));
        assertNull(ProviderQrLauncher.targetForPayload("http://passport.aliyundrive.com/qr"));
        assertNull(ProviderQrLauncher.targetForPayload("https://aliyundrive.com.evil.example/qr"));
        assertNull(ProviderQrLauncher.targetForPayload("https://123pan.com.evil.example/qr"));
        assertNull(ProviderQrLauncher.targetForPayload("https://189.cn.evil.example/qr"));
        assertNull(ProviderQrLauncher.targetForPayload("https://uc.cn@evil.example/qr"));
        assertNull(ProviderQrLauncher.targetForPayload("https://uc.cn\\@evil.example/qr"));
        assertNull(ProviderQrLauncher.targetForPayload("javascript:alert(1)"));
    }

    @Test
    public void decodesQrPixelsWithoutProviderAccess() throws Exception {
        String expected = "https://passport.aliyundrive.com/qrcodeCheck.htm?lgToken=test";
        BitMatrix matrix = new MultiFormatWriter().encode(expected, BarcodeFormat.QR_CODE, 320, 320);
        int[] pixels = new int[320 * 320];
        for (int y = 0; y < 320; y++) {
            for (int x = 0; x < 320; x++) pixels[y * 320 + x] = matrix.get(x, y) ? 0xff000000 : 0xffffffff;
        }
        assertEquals(expected, ProviderQrLauncher.decode(320, 320, pixels));
    }

    @Test
    public void providersWithUnsupportedQrLinksCanOpenInstalledApps() {
        assertTrue(ProviderQrLauncher.Target.BAIDU.openAppWhenUrlUnsupported);
        assertTrue(ProviderQrLauncher.Target.PAN123.openAppWhenUrlUnsupported);
        assertTrue(ProviderQrLauncher.Target.TIANYI.openAppWhenUrlUnsupported);
        assertTrue(ProviderQrLauncher.Target.ALIPAN.openAppWhenUrlUnsupported);
        assertFalse(ProviderQrLauncher.Target.QUARK.openAppWhenUrlUnsupported);
        assertFalse(ProviderQrLauncher.Target.UC.openAppWhenUrlUnsupported);
    }

    @Test
    public void builtInProviderIdsMapOnlyToAllowlistedOfficialApps() {
        assertEquals(ProviderQrLauncher.Target.ALIPAN,
                ProviderQrLauncher.targetForProvider("alipan"));
        assertEquals(ProviderQrLauncher.Target.QUARK,
                ProviderQrLauncher.targetForProvider("quark"));
        assertEquals(ProviderQrLauncher.Target.UC,
                ProviderQrLauncher.targetForProvider("uc"));
        assertEquals(ProviderQrLauncher.Target.BAIDU,
                ProviderQrLauncher.targetForProvider("baidu"));
        assertEquals(ProviderQrLauncher.Target.PAN123,
                ProviderQrLauncher.targetForProvider("123pan"));
        assertEquals(ProviderQrLauncher.Target.TIANYI,
                ProviderQrLauncher.targetForProvider("tianyi"));
        assertNull(ProviderQrLauncher.targetForProvider("115"));
        assertNull(ProviderQrLauncher.targetForProvider("webdav"));
        assertNull(ProviderQrLauncher.targetForProvider("quark.evil"));
    }
}
