package com.wuming.android.tv.api.loader;

import static org.junit.Assert.assertTrue;

import com.wuming.android.tv.cloud.opendrive.OpenDriveSpider;
import com.github.catvod.crawler.Spider;

import org.junit.Test;

public class BaseLoaderBuiltinTest {

    @Test
    public void builtinLibvioDoesNotDependOnAnExternalJar() {
        assertTrue(BaseLoader.createBuiltinSpider("libvio") instanceof com.wuming.android.tv.catalog.LibvioSpider);
    }

    @Test
    public void builtinOpenDriveDoesNotDependOnAnExternalJar() {
        Spider spider = BaseLoader.createBuiltinSpider("opendrive");

        assertTrue(spider instanceof OpenDriveSpider);
    }
}
