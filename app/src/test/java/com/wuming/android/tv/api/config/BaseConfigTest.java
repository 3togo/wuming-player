package com.wuming.android.tv.api.config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.wuming.android.tv.bean.Config;

import org.junit.Test;

public class BaseConfigTest {

    @Test
    public void staleLoadCannotInstallResolvedDefault() {
        FakeConfig config = new FakeConfig();
        int stale = config.nextTaskId();
        config.nextTaskId();
        Config resolved = new Config().url("https://stale.example/config.json");

        assertFalse(config.retainResolvedConfig(stale, null, resolved));
        assertNull(config.config);
    }

    @Test
    public void currentLoadRetainsResolvedDefaultOnlyOnce() {
        FakeConfig config = new FakeConfig();
        int current = config.nextTaskId();
        Config resolved = new Config().url("https://current.example/config.json");
        Config replacement = new Config().url("https://replacement.example/config.json");

        assertTrue(config.retainResolvedConfig(current, null, resolved));
        assertSame(resolved, config.config);
        assertFalse(config.retainResolvedConfig(current, null, replacement));
        assertSame(resolved, config.config);
    }

    @Test
    public void explicitCurrentRequestDoesNotReplaceNewerConfig() {
        FakeConfig config = new FakeConfig();
        int current = config.nextTaskId();
        Config requested = new Config().url("https://requested.example/config.json");
        Config newer = new Config().url("https://newer.example/config.json");
        config.config = newer;

        assertTrue(config.retainResolvedConfig(current, requested, requested));
        assertSame(newer, config.config);
    }

    private static final class FakeConfig extends BaseConfig {

        @Override
        protected String getTag() {
            return "FakeConfig";
        }

        @Override
        protected Config defaultConfig() {
            return new Config();
        }

        @Override
        protected void load(Config config) {
        }

        @Override
        protected boolean isLoaded() {
            return false;
        }

        @Override
        protected void setRules(java.util.List<com.wuming.android.tv.bean.Rule> rules) {
        }

        @Override
        protected void setAds(java.util.List<String> ads) {
        }
    }
}
