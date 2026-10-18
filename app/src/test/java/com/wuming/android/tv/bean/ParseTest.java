package com.wuming.android.tv.bean;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.List;
import java.util.Map;

public class ParseTest {

    @Test
    public void requestHeadersAndClickScriptsDoNotPolluteTheConfiguration() {
        Parse configuration = Parse.get(1, "https://example.test/parse");
        configuration.setName("parser");
        configuration.setClick("original");
        Parse first = configuration.copy();
        Parse second = configuration.copy();
        first.setHeader(Map.of("Cookie", "account-one"));
        first.setClick("first-request");
        second.setHeader(Map.of("Cookie", "account-two"));
        assertTrue(configuration.getHeader().isEmpty());
        assertEquals("original", configuration.getClick());
        assertEquals("account-one", first.getHeader().get("Cookie"));
        assertEquals("account-two", second.getHeader().get("Cookie"));
        assertEquals("parser", first.getName());
        assertEquals(1, first.getType());
    }

    @Test
    public void configuredHeadersAndFlagsAreCopiedWithoutLosingPrecedence() {
        Parse configuration = Parse.get(1, "https://example.test/parse");
        configuration.getExt().setHeader(Map.of("Referer", "configured"));
        configuration.getExt().setFlag(List.of("vod"));
        Parse copy = configuration.copy();
        copy.setHeader(Map.of("Referer", "request"));
        assertEquals("configured", copy.getHeader().get("Referer"));
        copy.getHeader().put("Cookie", "temporary");
        copy.getExt().getFlag().add("live");
        assertFalse(configuration.getHeader().containsKey("Cookie"));
        assertEquals(List.of("vod"), configuration.getExt().getFlag());
    }
}
