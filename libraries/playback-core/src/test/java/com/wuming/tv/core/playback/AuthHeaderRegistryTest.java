package com.wuming.tv.core.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class AuthHeaderRegistryTest {

    @After
    public void clearRegistry() {
        AuthHeaderRegistry.unregister("https://media.example/a.mp4");
        AuthHeaderRegistry.unregister("https://media.example/b.mp4");
    }

    private static Map<String, String> headers(String... pairs) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) headers.put(pairs[i], pairs[i + 1]);
        return headers;
    }

    @Test
    public void registeredHeadersAreFoundUntilUnregistration() {
        assertNull(AuthHeaderRegistry.lookup("https://media.example/a.mp4"));
        AuthHeaderRegistry.register("https://media.example/a.mp4", headers("Cookie", "sid=1"));
        assertEquals("sid=1", AuthHeaderRegistry.lookup("https://media.example/a.mp4").get("Cookie"));
        AuthHeaderRegistry.unregister("https://media.example/a.mp4");
        assertNull(AuthHeaderRegistry.lookup("https://media.example/a.mp4"));
    }

    @Test
    public void reregistrationReplacesTheWholeHeaderSet() {
        AuthHeaderRegistry.register("https://media.example/a.mp4", headers("Cookie", "first", "X", "1"));
        Map<String, String> before = AuthHeaderRegistry.lookup("https://media.example/a.mp4");
        Map<String, String> source = headers("Cookie", "second");
        AuthHeaderRegistry.register("https://media.example/a.mp4", source);
        Map<String, String> stored = AuthHeaderRegistry.lookup("https://media.example/a.mp4");
        assertEquals(1, stored.size());
        assertEquals("second", stored.get("Cookie"));
        assertFalse("A re-registration must not hand back the previous map", before == stored);
        source.put("Injected", "yes");
        assertEquals(1, AuthHeaderRegistry.lookup("https://media.example/a.mp4").size());
    }

    @Test
    public void laterSourceMapMutationsCannotChangeTheRegisteredSnapshot() {
        Map<String, String> source = headers("Cookie", "sid=1");
        AuthHeaderRegistry.register("https://media.example/a.mp4", source);
        source.put("Cookie", "sid=changed");
        source.put("Injected", "yes");
        Map<String, String> stored = AuthHeaderRegistry.lookup("https://media.example/a.mp4");
        assertEquals("sid=1", stored.get("Cookie"));
        assertNull(stored.get("Injected"));
    }

    @Test
    public void lookupResultCannotBeModified() {
        AuthHeaderRegistry.register("https://media.example/a.mp4", headers("Cookie", "sid=1"));
        try {
            AuthHeaderRegistry.lookup("https://media.example/a.mp4").put("Cookie", "sid=2");
            fail("Registered headers must be immutable");
        } catch (UnsupportedOperationException expected) {
            assertEquals("sid=1", AuthHeaderRegistry.lookup("https://media.example/a.mp4").get("Cookie"));
        }
    }

    @Test
    public void serializationRoundTripsOrderAndUnicode() {
        Map<String, String> source = headers(
                "Referer", "https://盘.example/媒体",
                "Cookie", "sid=1; 语言=zh",
                "User-Agent", "");
        Map<String, String> restored = AuthHeaderRegistry.deserialize(AuthHeaderRegistry.serialize(source));
        assertEquals(source, restored);
        assertEquals(source.keySet(), restored.keySet());
    }

    @Test
    public void emptyHeaderSetSurvivesSerialization() {
        Map<String, String> restored =
                AuthHeaderRegistry.deserialize(AuthHeaderRegistry.serialize(Map.of()));
        assertTrue(restored.isEmpty());
    }

    @Test
    public void deserializationOfMissingOrDamagedDataYieldsNoHeaders() {
        assertTrue(AuthHeaderRegistry.deserialize(null).isEmpty());
        assertTrue(AuthHeaderRegistry.deserialize(new byte[0]).isEmpty());
        // A declared count without any following entries cannot be filled.
        assertTrue(AuthHeaderRegistry.deserialize(new byte[]{0, 0, 0, 2, 0, 0}).isEmpty());
        // A truncated key length stops the read.
        byte[] truncated = AuthHeaderRegistry.serialize(headers("Cookie", "sid=1"));
        byte[] cut = new byte[truncated.length - 3];
        System.arraycopy(truncated, 0, cut, 0, cut.length);
        assertTrue(AuthHeaderRegistry.deserialize(cut).isEmpty());
    }

    @Test
    public void rejectsNegativeOverflowingAndImpossibleLengthsBeforeAllocation() {
        for (int count : new int[]{-1, 257, Integer.MAX_VALUE}) {
            assertTrue(AuthHeaderRegistry.deserialize(ints(count)).isEmpty());
        }
        for (int length : new int[]{-1, 65537, Integer.MAX_VALUE}) {
            assertTrue(AuthHeaderRegistry.deserialize(ints(1, length)).isEmpty());
            assertTrue(AuthHeaderRegistry.deserialize(ints(1, 0, length)).isEmpty());
        }
        assertTrue(AuthHeaderRegistry.deserialize(ints(1, 100)).isEmpty());
        assertTrue(AuthHeaderRegistry.deserialize(ints(1, 0, 100)).isEmpty());
        assertTrue(AuthHeaderRegistry.deserialize(new byte[256 * 1024 + 1]).isEmpty());
    }

    @Test
    public void rejectsTrailingDataAndDoesNotReturnPartialHeaders() {
        byte[] good = AuthHeaderRegistry.serialize(headers("Cookie", "fixture"));
        assertTrue(AuthHeaderRegistry.deserialize(java.util.Arrays.copyOf(good, good.length + 1)).isEmpty());
        byte[] partial = java.util.Arrays.copyOf(good, good.length + 8);
        java.nio.ByteBuffer.wrap(partial).putInt(2);
        java.nio.ByteBuffer.wrap(partial, good.length, 8).putInt(0).putInt(Integer.MAX_VALUE);
        assertTrue(AuthHeaderRegistry.deserialize(partial).isEmpty());
    }

    @Test
    public void acceptsMaximumSupportedFieldWithoutDroppingUnicodeOrEmptyValues() {
        Map<String, String> source = headers("Cookie", "x".repeat(65536), "X-Title", "無名", "Empty", "");
        assertEquals(source, AuthHeaderRegistry.deserialize(AuthHeaderRegistry.serialize(source)));
    }

    private static byte[] ints(int... values) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(values.length * 4);
        for (int value : values) buffer.putInt(value);
        return buffer.array();
    }

    @Test
    public void deserializedMapIsImmutableAndComplete() {
        Map<String, String> restored =
                AuthHeaderRegistry.deserialize(AuthHeaderRegistry.serialize(headers("Cookie", "sid=1")));
        assertNotNull(restored.get("Cookie"));
        try {
            restored.put("Cookie", "sid=2");
            fail("Deserialized headers must be immutable");
        } catch (UnsupportedOperationException expected) {
            assertEquals("sid=1", restored.get("Cookie"));
        }
    }

    @Test
    public void serializationKeepsMultibyteValuesDecodable() {
        byte[] data = AuthHeaderRegistry.serialize(headers("X-Title", "無名"));
        // 4 count + 4+7 key + 4+6 UTF-8 value bytes; a fixed-width assumption would truncate here.
        assertEquals(4 + 4 + 7 + 4 + 6, data.length);
        assertEquals("無名", AuthHeaderRegistry.deserialize(data).get("X-Title"));
    }
}
