package com.wuming.android.tv;
import com.wuming.android.tv.setting.AppSettings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RestartModeInstrumentedTest {

    private static final String PROVIDER_CREDENTIAL = "quark_ck";
    private static final String APP_SETTING = "theme";
    private SharedPreferences preferences;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        preferences = context.getSharedPreferences("restart_mode_test", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @After
    public void tearDown() {
        preferences.edit().clear().commit();
    }

    @Test
    public void sessionResetPreservesProviderCredentials() {
        preferences.edit()
                .putString(PROVIDER_CREDENTIAL, "credential-fixture")
                .putInt(APP_SETTING, 2)
                .commit();

        AppSettings.clearSessionSettings(preferences);

        assertEquals("credential-fixture", preferences.getString(PROVIDER_CREDENTIAL, ""));
        assertEquals(0, preferences.getInt(APP_SETTING, 0));
    }

    @Test
    public void oneShotForgetAllIsConsumedWithoutLosingItsAction() {
        preferences.edit().putInt("restart_mode", 2).commit();

        int mode = AppSettings.consumeRestartMode(preferences);

        assertEquals(2, mode);
        assertEquals(0, preferences.getInt("restart_mode", 0));
        assertTrue(AppSettings.shouldClearSession(mode));
        assertTrue(AppSettings.shouldClearAll(mode));
    }

    @Test
    public void saveModeClearsNothing() {
        int mode = AppSettings.consumeRestartMode(preferences);

        assertFalse(AppSettings.shouldClearSession(mode));
        assertFalse(AppSettings.shouldClearAll(mode));
    }
}
