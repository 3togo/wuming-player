package com.github.catvod.compat;

import com.github.catvod.bean.Proxy;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Prefers;
import com.github.catvod.utils.UriUtil;
import com.github.catvod.utils.Util;
import java.lang.reflect.Modifier;
import org.junit.Test;
import static org.junit.Assert.*;

/** Java consumer contracts which an automatic Kotlin conversion can silently remove. */
public class JavaMigrationContractTest {
    // Separately compiled Java consumers may hide utility static methods.
    public static class DerivedUtil extends Util {
        public static String getIp() { return "fixture"; }
        public static String substring(String value) { return value; }
        public static byte[] decode(String value) { return new byte[0]; }
        public static String base64(byte[] value) { return "fixture"; }
    }

    @Test public void utilityConstructorsAndSubclassingRemainAvailable() {
        assertNotNull(new Util());
        assertEquals("fixture", DerivedUtil.getIp());
        assertNotNull(new Path());
        assertNotNull(new Prefers());
        assertNotNull(new UriUtil());
        assertFalse(Modifier.isFinal(Util.class.getModifiers()));
        assertFalse(Modifier.isFinal(Path.class.getModifiers()));
        assertFalse(Modifier.isFinal(Prefers.class.getModifiers()));
    }

    @Test public void javaStaticEntryPointsKeepTheirNames() throws Exception {
        assertTrue(Modifier.isStatic(Prefers.class.getMethod("getPrefers").getModifiers()));
        assertTrue(Modifier.isStatic(OkHttp.class.getMethod("isLegacyTlsEnabled").getModifiers()));
        assertTrue(Modifier.isStatic(OkHttp.class.getMethod("setLegacyTlsEnabled", boolean.class).getModifiers()));
        assertEquals(String.class, Util.class.getMethod("getIp").getReturnType());
    }

    @Test public void proxyDeserializationKeepsRawNullFieldsAndReadOnlyEmptyDefaults() throws Exception {
        // java.net.Proxy is runtime-only here; Android permits its reflection, whereas
        // the desktop test JDK encapsulates java.net. Keep this fixture on the JSON fields.
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder()
                .setExclusionStrategies(new com.google.gson.ExclusionStrategy() {
                    public boolean shouldSkipField(com.google.gson.FieldAttributes field) {
                        return field.getName().equals("proxies");
                    }
                    public boolean shouldSkipClass(Class<?> type) { return false; }
                }).create();
        Proxy proxy = gson.fromJson("{}", Proxy.class);
        for (String name : new String[]{"name", "hosts", "urls"}) {
            java.lang.reflect.Field field = Proxy.class.getDeclaredField(name);
            field.setAccessible(true);
            assertNull(field.get(proxy));
        }
        assertTrue(proxy.getHosts().isEmpty());
        assertTrue(proxy.getUrls().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> proxy.getHosts().add("example.com"));
        Proxy populated = gson.fromJson("{\"hosts\":[\"example.com\"],\"urls\":[\"http://localhost:8080\"]}", Proxy.class);
        assertEquals("example.com", populated.getHosts().get(0));
        assertEquals("http://localhost:8080", populated.getUrls().get(0));
    }

    @Test public void invalidMatchersRemainFalseIncludingNullJavaArguments() {
        assertFalse(Util.containOrMatch(null, "test"));
        assertFalse(Util.containOrMatch("test", null));
        assertFalse(Util.containOrMatch("test", "["));
        assertTrue(Util.containOrMatch("test", "es"));
    }
}
