package com.wuming.android.tv.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Guards the shared hex codec that backs every cache key, MAC address, signature and
 * content hash in the app. A regression here silently corrupts persisted caches.
 */
public class HexTest {

    @Test
    public void lowerRoundTripsAnyByte() {
        byte[] data = new byte[256];
        for (int i = 0; i < 256; i++) data[i] = (byte) i;
        String hex = Hex.encodeLower(data);
        assertEquals(512, hex.length());
        for (int i = 0; i < 256; i++) {
            String expected = String.format("%02x", i);
            assertEquals(expected, hex.substring(i * 2, i * 2 + 2));
        }
    }

    @Test
    public void upperMatchesUppercaseFormat() {
        byte[] data = {0x00, 0x0f, (byte) 0xff, 0x7a, (byte) 0xab};
        assertEquals("000fff7aab", Hex.encodeLower(data));
        assertEquals("000FFF7AAB", Hex.encodeUpper(data));
    }

    @Test
    public void emptyAndNullAreSafe() {
        assertEquals("", Hex.encodeLower(new byte[0]));
        assertEquals("", Hex.encodeLower(null));
        assertEquals("", Hex.encodeUpperColon(null));
    }

    @Test
    public void colonFormMatchesMacStyle() {
        byte[] mac = {0x1a, 0x2b, 0x3c, (byte) 0xde, 0x00, 0x0f};
        assertEquals("1A:2B:3C:DE:00:0F", Hex.encodeUpperColon(mac));
        // Single byte has no separator.
        assertEquals("FF", Hex.encodeUpperColon(new byte[]{(byte) 0xff}));
    }

    @Test
    public void matchesLegacyInlineImplementation() {
        byte[] digest = "hello-world".getBytes(StandardCharsets.UTF_8);
        StringBuilder legacy = new StringBuilder(digest.length * 2);
        for (byte b : digest) legacy.append(String.format(java.util.Locale.ROOT, "%02x", b));
        assertEquals(legacy.toString(), Hex.encodeLower(digest));
    }

    @Test
    public void knownVector() {
        // "abc" encodes to the 6-char lowercase hex "616263"
        byte[] abc = "abc".getBytes(StandardCharsets.UTF_8);
        assertEquals("616263", Hex.encodeLower(abc));
    }
}
