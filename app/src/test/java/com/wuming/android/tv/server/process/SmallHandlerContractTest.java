package com.wuming.android.tv.server.process;

import static org.junit.Assert.*;

import android.content.SharedPreferences;
import com.wuming.android.tv.server.Server;
import com.github.catvod.utils.Prefers;
import fi.iki.elonen.NanoHTTPD;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class SmallHandlerContractTest {
    @Test public void cacheKeepsKeyConstructionMissingValuesAndActions() throws Exception {
        Field instance = Prefers.class.getDeclaredField("instance");
        instance.setAccessible(true);
        Object previous = instance.get(null);
        Map<String, String> values = new HashMap<>();
        instance.set(null, preferences(values));
        try {
            Cache cache = new Cache();
            assertEquals("OK", body(cache.doResponse(session(Map.of("do", "set", "value", "v")), "/cache", Map.of())));
            assertEquals("v", values.get("cache_null"));
            assertEquals("v", body(cache.doResponse(session(Map.of("do", "get")), "/cache", Map.of())));
            cache.doResponse(session(Map.of("do", "set", "key", "k", "rule", "r", "value", "x")), "/cache", Map.of());
            assertEquals("x", values.get("cache_r_k"));
            cache.doResponse(session(Map.of("do", "set", "key", "k", "rule", "r")), "/cache", Map.of());
            assertEquals("x", values.get("cache_r_k")); // Missing value remains a no-op.
            cache.doResponse(session(Map.of("do", "del", "key", "k", "rule", "r")), "/cache", Map.of());
            assertFalse(values.containsKey("cache_r_k"));
            assertEquals("OK", body(cache.doResponse(session(Map.of()), "/cache", Map.of())));
        } finally {
            instance.set(null, previous);
        }
    }

    @Test public void routesKeepPrefixMatchingAndMediaWithoutServiceIsEmpty() throws Exception {
        var session = session(Map.of());
        assertTrue(new Cache().isRequest(session, "/cache-extra"));
        assertTrue(new Parse().isRequest(session, "/parse-extra"));
        assertTrue(new Media().isRequest(session, "/media-extra"));
        assertFalse(new Media().isRequest(session, "/other"));
        var previous = Server.get().getService();
        try {
            Server.get().setService(null);
            var response = new Media().doResponse(session, "/media", Map.of());
            assertEquals(200, response.getStatus().getRequestStatus());
            assertEquals("{}", body(response));
        } finally {
            Server.get().setService(previous);
        }
    }

    @Test public void parseMapsRequestExceptionsIncludingNullMessages() throws Exception {
        for (String message : new String[]{"request failed", null}) {
            NanoHTTPD.IHTTPSession session = (NanoHTTPD.IHTTPSession) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{NanoHTTPD.IHTTPSession.class},
                    (proxy, method, args) -> { throw new IllegalStateException(message); });
            var response = new Parse().doResponse(session, "/parse", Map.of());
            assertEquals(500, response.getStatus().getRequestStatus());
            assertEquals(message == null ? "Unknown error" : message, body(response));
        }
    }

    private static String body(NanoHTTPD.Response response) throws Exception {
        try (response) {
            return new String(response.getData().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static NanoHTTPD.IHTTPSession session(Map<String, String> params) {
        return (NanoHTTPD.IHTTPSession) Proxy.newProxyInstance(SmallHandlerContractTest.class.getClassLoader(),
                new Class<?>[]{NanoHTTPD.IHTTPSession.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getParms")) return params;
                    throw new AssertionError(method.getName());
                });
    }

    private static SharedPreferences preferences(Map<String, String> values) {
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                SmallHandlerContractTest.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "putString" -> { values.put((String) args[0], (String) args[1]); yield proxy; }
                    case "remove" -> { values.remove(args[0]); yield proxy; }
                    case "apply" -> null;
                    default -> throw new AssertionError(method.getName());
                });
        return (SharedPreferences) Proxy.newProxyInstance(SmallHandlerContractTest.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "edit" -> editor;
                    case "getString" -> values.getOrDefault(args[0], (String) args[1]);
                    default -> throw new AssertionError(method.getName());
                });
    }
}
