package com.wuming.android.tv.cloud.opendrive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OpenDriveShareRoutingTest {

    @Test
    public void recognizesEveryBuiltInShareDomain() {
        assertTrue(OpenDriveSpider.supportsShare("https://www.alipan.com/s/Abcdef12"));
        assertTrue(OpenDriveSpider.supportsShare("https://pan.quark.cn/s/Abcdef12"));
        assertTrue(OpenDriveSpider.supportsShare("https://drive.uc.cn/s/Abcdef12"));
        assertTrue(OpenDriveSpider.supportsShare("https://pan.baidu.com/s/1Abcdef12?pwd=1234"));
        assertTrue(OpenDriveSpider.supportsShare("https://www.123pan.com/s/Abcdef12?pwd=1234"));
        assertTrue(OpenDriveSpider.supportsShare("https://www.123592.com/s/Abcdef12"));
        assertTrue(OpenDriveSpider.supportsShare("https://www.123912.com/s/Abcdef12"));
        assertTrue(OpenDriveSpider.supportsShare("https://www.123865.com/s/Abcdef12"));
        assertTrue(OpenDriveSpider.supportsShare("https://cloud.189.cn/web/share?code=Abcdef12"));
    }

    @Test
    public void rejectsLookalikeAndCredentialBearingDomains() {
        assertFalse(OpenDriveSpider.supportsShare("https://pan.quark.cn.evil.example/s/Abcdef12"));
        assertFalse(OpenDriveSpider.supportsShare("https://user@drive.uc.cn/s/Abcdef12"));
        assertFalse(OpenDriveSpider.supportsShare("http://pan.baidu.com/s/1Abcdef12"));
        assertFalse(OpenDriveSpider.supportsShare("https://www.123pan.com.evil.example/s/Abcdef12"));
        assertFalse(OpenDriveSpider.supportsShare("https://cloud.189.cn.evil.example/web/share?code=Abcdef12"));
    }

    @Test
    public void buildsAnOpaqueTargetFromSupportedShareText() {
        OpenDriveShareTarget target = OpenDriveShareTarget.fromText(
                "Please play https://pan.quark.cn/s/Abcdef12").orElseThrow();

        assertTrue(target.getItemId().startsWith("opendrive-share:"));
    }

    @Test
    public void doesNotBuildTargetsFromUnsafeOrEmptyText() {
        assertTrue(OpenDriveShareTarget.fromText(null).isEmpty());
        assertTrue(OpenDriveShareTarget.fromText("  ").isEmpty());
        assertTrue(OpenDriveShareTarget.fromText(
                "https://pan.quark.cn.evil.example/s/Abcdef12").isEmpty());
        assertTrue(OpenDriveShareTarget.fromText(
                "http://pan.baidu.com/s/1Abcdef12").isEmpty());
    }
}
