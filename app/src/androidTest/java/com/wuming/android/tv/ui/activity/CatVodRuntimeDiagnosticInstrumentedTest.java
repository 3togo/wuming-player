package com.wuming.android.tv.ui.activity;

import static org.junit.Assert.assertTrue;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.utils.DownloadedCatVod;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Records the exact downloaded CatVod implementation used by route-switch tests. */
@RunWith(AndroidJUnit4.class)
public class CatVodRuntimeDiagnosticInstrumentedTest {

    @Test
    public void downloadedCloudResolverIdentityIsObservable() throws Exception {
        VodConfig.get().ensureLoaded();
        Site cloud = DownloadedCatVod.findCloudSite();

        Assume.assumeNotNull("No downloaded CatVod cloud resolver is configured", cloud);
        assertTrue("Downloaded CatVod resolver has no jar", !cloud.getJar().isBlank());
        Log.i("CatVodRuntime", "config=" + VodConfig.get().getConfig().getUrl()
                + ",key=" + cloud.getKey()
                + ",name=" + cloud.getName()
                + ",api=" + cloud.getApi()
                + ",jar=" + cloud.getJar());
    }
}
