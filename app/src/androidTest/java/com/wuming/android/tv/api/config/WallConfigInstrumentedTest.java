package com.wuming.android.tv.api.config;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.wuming.android.tv.bean.Config;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class WallConfigInstrumentedTest {
    @Test public void settingsCanReadWallpaperBeforeBackgroundInitialization() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            WallConfig wall = WallConfig.get();
            Config original = wall.config;
            try {
                wall.config = null;
                assertNotNull(WallConfig.getDesc());
                assertNotNull(WallConfig.getUrl());
                // Main-thread display reads must not cache a transient default
                // and prevent the asynchronous load from retaining real config.
                assertNull(wall.config);
            } finally { wall.config = original; }
        });
    }
}
