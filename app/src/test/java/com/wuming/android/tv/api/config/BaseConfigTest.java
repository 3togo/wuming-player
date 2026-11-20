package com.wuming.android.tv.api.config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.wuming.android.tv.bean.Config;

import org.junit.Test;

public class BaseConfigTest {

    @Test
    public void obsoletePreparedConfigCannotPublish() {
        FakeConfig owner = new FakeConfig();
        int stale = owner.nextTaskId();
        int current = owner.nextTaskId();
        Config oldSource = new Config().url("https://stale.example/config.json");
        Config newSource = new Config().url("https://current.example/config.json");
        var publications = new java.util.ArrayList<Config>();

        assertTrue(owner.commitPrepared(current,
                new BaseConfig.PreparedLoad(newSource, () -> publications.add(newSource)), false));
        assertFalse(owner.commitPrepared(stale,
                new BaseConfig.PreparedLoad(oldSource, () -> publications.add(oldSource)), false));
        assertSame(newSource, owner.config);
        org.junit.Assert.assertEquals(java.util.List.of(newSource), publications);
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
        protected PreparedLoad prepare(Config config, int request) {
            return new PreparedLoad(config, () -> {});
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
