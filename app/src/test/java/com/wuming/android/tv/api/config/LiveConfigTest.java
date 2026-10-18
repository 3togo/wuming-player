package com.wuming.android.tv.api.config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.wuming.android.tv.bean.Config;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class LiveConfigTest {

    private LiveConfig liveConfig;
    private Config originalConfig;

    @Before
    public void setUp() {
        liveConfig = LiveConfig.get();
        originalConfig = liveConfig.config;
    }

    @After
    public void tearDown() {
        liveConfig.config = originalConfig;
    }

    @Test
    public void hasLoadedUrlDoesNotResolveMissingDefaultConfig() {
        liveConfig.config = null;

        assertFalse(LiveConfig.hasLoadedUrl());
        assertNull("A read-only UI check must not trigger default config resolution",
                liveConfig.config);
    }

    @Test
    public void hasLoadedUrlRejectsNullAndEmptyUrls() {
        Config nullUrl = new Config();
        liveConfig.config = nullUrl;
        assertFalse(LiveConfig.hasLoadedUrl());
        assertSame(nullUrl, liveConfig.config);

        Config emptyUrl = new Config().url("");
        liveConfig.config = emptyUrl;
        assertFalse(LiveConfig.hasLoadedUrl());
        assertSame(emptyUrl, liveConfig.config);
    }

    @Test
    public void hasLoadedUrlAcceptsResolvedNonEmptyUrl() {
        Config loaded = new Config().url("https://example.test/live.m3u8");
        liveConfig.config = loaded;

        assertTrue(LiveConfig.hasLoadedUrl());
        assertSame(loaded, liveConfig.config);
    }
}
