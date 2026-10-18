package com.wuming.android.tv.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.bean.Site;

import org.junit.Test;

import java.util.List;

public class DownloadedCatVodTest {

    @Test
    public void prefersMyDriveGuardOverProviderSpecificSpider() {
        Site quark = external("quark", "Quark", "csp_Quark");
        Site mDrive = external("MDrive", "MDrive", "csp_MyDriveGuard");

        assertEquals(mDrive, DownloadedCatVod.findCloudSite(List.of(quark, mDrive)));
    }

    @Test
    public void excludesBuiltInAndSitesWithoutDownloadedJar() {
        Site builtIn = Site.builtinSpider(VodConfig.OPEN_DRIVE_KEY, "OpenDrive", "builtin_opendrive");
        Site unpackaged = Site.builtinSpider("MDrive", "MDrive", "csp_MyDriveGuard");

        assertNull(DownloadedCatVod.findCloudSite(List.of(builtIn, unpackaged)));
    }

    @Test
    public void acceptsDownloadedQuarkSpiderAsFallback() {
        Site quark = external("csp_Quark", "Quark Drive", "csp_Quark");

        assertEquals(quark, DownloadedCatVod.findCloudSite(List.of(quark)));
    }

    private static Site external(String key, String name, String api) {
        Site site = Site.builtinSpider(key, name, api);
        site.setJar("https://example.com/catvod.jar");
        return site;
    }
}
