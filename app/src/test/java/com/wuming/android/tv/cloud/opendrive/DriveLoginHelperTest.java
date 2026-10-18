package com.wuming.android.tv.cloud.opendrive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.wuming.android.tv.bean.Episode;
import com.wuming.android.tv.bean.Flag;
import com.wuming.android.tv.bean.Site;

import org.junit.Test;

import okio.Buffer;
import okhttp3.ResponseBody;

public class DriveLoginHelperTest {

    // --- isBaiduLogin ---

    @Test
    public void baiduLoginDetectedFromUrlOrFlag() {
        assertTrue(DriveLoginHelper.isBaiduLogin(
                Episode.create("e1", "https://pan.baidu.com/s/xyz"), new Flag("quark")));
        assertTrue(DriveLoginHelper.isBaiduLogin(
                Episode.create("e1", "http://x/?a={\"share_id\":1,\"sekey\":2}"), new Flag("quark")));
        assertTrue(DriveLoginHelper.isBaiduLogin(
                Episode.create("e1", "http://other/"), new Flag("百度网盘")));
        assertTrue(DriveLoginHelper.isBaiduLogin(
                Episode.create("e1", "http://other/"), new Flag("嘟嘟")));
        assertFalse(DriveLoginHelper.isBaiduLogin(
                Episode.create("e1", "https://pan.quark.cn/s/x"), new Flag("quark")));
        assertFalse(DriveLoginHelper.isBaiduLogin(null, new Flag("百度")));
        assertFalse(DriveLoginHelper.isBaiduLogin(Episode.create("e1", "http://x/"), null));
    }

    // --- isBilibiliSite ---

    @Test
    public void bilibiliSiteDetectedFromAnyField() {
        Site byKey = site("bilibili_main", "", "", "", "");
        assertTrue(DriveLoginHelper.isBilibiliSite(byKey));
        assertTrue(DriveLoginHelper.isBilibiliSite(site("", "哔哩哔哩", "", "", "")));
        assertTrue(DriveLoginHelper.isBilibiliSite(site("", "", "csp_Bili", "", "")));
        assertTrue(DriveLoginHelper.isBilibiliSite(site("", "", "", "", "bili.jar")));
        assertFalse(DriveLoginHelper.isBilibiliSite(site("quark", "夸克", "csp_Quark", "", "")));
        assertFalse(DriveLoginHelper.isBilibiliSite(null));
    }

    // --- isBilibiliLoginError ---

    @Test
    public void bilibiliLoginErrorRequiresLoginAndBilibiliSignals() {
        assertTrue(DriveLoginHelper.isBilibiliLoginError("please login to bilibili", false));
        assertTrue(DriveLoginHelper.isBilibiliLoginError("需要登录", true));
        assertTrue(DriveLoginHelper.isBilibiliLoginError("哔哩哔哩 登入失败", false));
        assertFalse(DriveLoginHelper.isBilibiliLoginError("please login", false));
        assertFalse(DriveLoginHelper.isBilibiliLoginError("bilibili error", false));
        assertFalse(DriveLoginHelper.isBilibiliLoginError(null, false));
        assertFalse(DriveLoginHelper.isBilibiliLoginError("network timeout", false));
    }

    // Note: decodeQrBitmap depends on android.graphics.BitmapFactory and is covered
    // by instrumented tests; JVM unit tests only exercise the pure helpers.

    // --- readLimited ---

    @Test
    public void readLimitedCapsAtMaxBytes() throws Exception {
        assertNull(DriveLoginHelper.readLimited(null));
        assertNull(DriveLoginHelper.readLimited(
                ResponseBody.create(new Buffer(), null, (long) DriveLoginHelper.MAX_QR_IMAGE_BYTES + 1)));
        byte[] small = new byte[100];
        byte[] out = DriveLoginHelper.readLimited(
                ResponseBody.create(new Buffer().write(small), null, (long) small.length));
        assertEquals(100, out.length);
    }

    private static Site site(String key, String name, String api, String ext, String jar) {
        Site site = new Site();
        site.setKey(key);
        site.setName(name);
        site.setApi(api);
        site.setExt(ext);
        site.setJar(jar);
        return site;
    }
}
