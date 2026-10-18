package com.wuming.android.tv.cloud.opendrive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.wuming.android.tv.api.loader.BaseLoader;
import com.github.catvod.crawler.Spider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class OpenDriveInstrumentedTest {

    @After
    public void clearLoaders() {
        BaseLoader.get().clear();
    }

    @Test
    public void realLoaderCreatesBundledOpenDriveWithoutExternalJar() throws Exception {
        Spider spider = BaseLoader.get().getSpider(
                "opendrive-device-test",
                "builtin_opendrive",
                "",
                "https://invalid.example/ignored.jar");

        assertTrue(spider instanceof OpenDriveSpider);
        JsonObject home = JsonParser.parseString(spider.homeContent(true)).getAsJsonObject();
        assertEquals("opendrive-accounts",
                home.getAsJsonArray("class").get(0).getAsJsonObject()
                        .get("type_id").getAsString());
    }
}
