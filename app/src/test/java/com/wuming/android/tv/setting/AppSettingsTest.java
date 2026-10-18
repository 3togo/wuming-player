package com.wuming.android.tv.setting;
import com.wuming.android.tv.setting.AppSettings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class AppSettingsTest {

    @Test
    public void missingLinkRouteUsesBuiltInOpenDrive() {
        assertEquals(AppSettings.LINK_ROUTE_BUILTIN, AppSettings.defaultLinkRoute());
    }

    @Test
    public void sessionResetPreservesCredentialsAndTlsMigrationState() {
        Map<String, Object> values = new HashMap<>(Map.of(
                "speed", 3f, "keyword", "old search", "link_route", 0,
                "provider_cookie", "keep", "legacy_tls", true,
                "tls_policy_initialized", true, "restart_mode", 4));
        AppSettings.clearSessionSettings(preferences(values));
        assertFalse(values.containsKey("speed"));
        assertFalse(values.containsKey("keyword"));
        assertFalse(values.containsKey("link_route"));
        assertEquals("keep", values.get("provider_cookie"));
        assertEquals(true, values.get("legacy_tls"));
        assertEquals(true, values.get("tls_policy_initialized"));
        assertEquals(4, values.get("restart_mode"));
    }

    @Test
    public void restartModesConsumeOnlyOneShotRequests() {
        for (int mode = 0; mode <= 4; mode++) {
            Map<String, Object> values = new HashMap<>();
            values.put("restart_mode", mode);
            assertEquals(mode, AppSettings.consumeRestartMode(preferences(values)));
            assertEquals(mode == 1 || mode == 2 ? 0 : mode, values.get("restart_mode"));
            assertEquals(mode >= 1, AppSettings.shouldClearSession(mode));
            assertEquals(mode == 2 || mode == 4, AppSettings.shouldClearAll(mode));
        }
        assertTrue(AppSettings.shouldClearSession(4));
    }

    private static SharedPreferences preferences(Map<String, Object> values) {
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                AppSettingsTest.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "remove": values.remove(args[0]); return proxy;
                        case "putInt": values.put((String) args[0], args[1]); return proxy;
                        case "commit": return true;
                        case "apply": return null;
                        default: throw new AssertionError(method.getName());
                    }
                });
        return (SharedPreferences) Proxy.newProxyInstance(AppSettingsTest.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "edit": return editor;
                        case "getInt": return values.getOrDefault(args[0], args[1]);
                        default: throw new AssertionError(method.getName());
                    }
                });
    }
}
