package com.wuming.android.tv.library;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProviderRecommendationBridgeTest {
    @Test public void scopedPreferenceReachesCallAndRestoresOnFailureAndEmptySeeds() {
        Map<String, String> values = new HashMap<>();
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "putString": values.put((String) args[0], (String) args[1]); return proxy;
                case "remove": values.remove((String) args[0]); return proxy;
                case "commit": return true;
                case "apply": return null;
                default: throw new AssertionError(method.getName());
            }
        });
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{SharedPreferences.class}, (proxy, method, args) -> {
            if (method.getName().equals("getString")) return values.getOrDefault((String) args[0], (String) args[1]);
            if (method.getName().equals("edit")) return editor;
            throw new AssertionError(method.getName());
        });
        values.put("historyvodname", "old");
        assertEquals("[\"Seed\"]", ProviderRecommendationBridge.withSeeds(preferences, List.of("Seed"), () -> values.get("historyvodname")));
        assertEquals("old", values.get("historyvodname"));
        assertThrows(IllegalArgumentException.class, () -> ProviderRecommendationBridge.withSeeds(preferences, List.of("Seed"), () -> { throw new IllegalArgumentException("rejected"); }));
        assertEquals("old", values.get("historyvodname"));
        values.clear();
        assertEquals("[]", ProviderRecommendationBridge.withSeeds(preferences, List.of(), () -> values.get("historyvodname")));
        assertFalse(values.containsKey("historyvodname"));
    }
}
