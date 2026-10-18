package com.wuming.android.tv.utils;

import com.wuming.android.tv.cloud.CloudStorageProvider;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProviderAppLauncherTest {

    @Test
    public void allReviewedManagementUrlsMapToOfficialProviderApps() {
        for (CloudStorageProvider provider : CloudStorageProvider.values()) {
            if (provider == CloudStorageProvider.DRIVE_115) continue;
            assertTrue(provider.getDisplayName(),
                    ProviderAppLauncher.supports(provider.getManagementUrl()));
        }
    }

    @Test
    public void recognizesSupportedProviderHttpsLinks() {
        assertTrue(ProviderAppLauncher.supports("https://www.alipan.com/s/example"));
        assertTrue(ProviderAppLauncher.supports("https://pan.quark.cn/s/example"));
        assertTrue(ProviderAppLauncher.supports("https://drive.uc.cn/s/example"));
        assertTrue(ProviderAppLauncher.supports("https://pan.baidu.com/s/example"));
        assertTrue(ProviderAppLauncher.supports("https://www.123pan.com/s/example"));
    }

    @Test
    public void rejectsUntrustedOrMalformedLinks() {
        assertFalse(ProviderAppLauncher.supports("http://pan.quark.cn/s/example"));
        assertFalse(ProviderAppLauncher.supports("https://quark.cn.example.com/s/example"));
        assertFalse(ProviderAppLauncher.supports("https://user@pan.baidu.com/s/example"));
        assertFalse(ProviderAppLauncher.supports("https://example.com/?next=https://pan.quark.cn"));
    }
}
