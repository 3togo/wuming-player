package com.wuming.android.tv.bean;

import static org.junit.Assert.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Test;

public class DanmakuContractTest {
    private final Gson gson = new Gson();

    private static class DerivedFactories extends Danmaku {
        public static Danmaku from(String value) { return new DerivedFactories(); }
        public static Danmaku empty() { return new DerivedFactories(); }
        public static java.util.List<Danmaku> arrayFrom(String value) { return java.util.List.of(empty()); }
    }

    @Test public void javaSubclassesCanStillDeclareTheirOwnStaticFactories() {
        assertTrue(DerivedFactories.from("ignored") instanceof DerivedFactories);
        assertTrue(DerivedFactories.empty() instanceof DerivedFactories);
        assertTrue(DerivedFactories.arrayFrom("ignored").get(0) instanceof DerivedFactories);
        assertEquals(Danmaku.class, Danmaku.from("original").getClass());
    }

    @Test public void nullDefaultsAndNameFallbackRetainRawJsonState() {
        Danmaku item = Danmaku.empty();
        assertTrue(item.isEmpty());
        assertEquals("", item.getName());
        assertEquals("", item.getUrl());
        assertFalse(item.isSelected());
        JsonObject json = gson.toJsonTree(item).getAsJsonObject();
        assertFalse(json.has("name"));
        assertFalse(json.has("url"));
        assertFalse(json.get("selected").getAsBoolean());
        item.setUrl("https://example.test/overlay.xml");
        for (String name : new String[]{null, ""}) {
            item.setName(name);
            assertEquals(item.getUrl(), item.getName());
        }
        item.setName(" ");
        assertEquals(" ", item.getName());
        item.setUrl(null);
        assertTrue(item.isEmpty());
        assertEquals("", item.getUrl());
        assertTrue(Danmaku.from(null).isEmpty());
    }

    @Test public void serializationPreservesMutableFieldsAndNullReads() {
        Danmaku item = gson.fromJson("{\"name\":null,\"url\":\"https://example.test/a.xml\",\"selected\":true}", Danmaku.class);
        assertEquals(item.getUrl(), item.getName());
        assertTrue(item.isSelected());
        item.setName("Overlay");
        item.setSelected(false);
        JsonObject json = gson.toJsonTree(item).getAsJsonObject();
        assertEquals(3, json.size());
        assertEquals("Overlay", json.get("name").getAsString());
        assertEquals(item.getUrl(), json.get("url").getAsString());
        assertFalse(json.get("selected").getAsBoolean());
        Danmaku copy = gson.fromJson(json, Danmaku.class);
        assertEquals(item, copy);
        assertEquals("Overlay", copy.getName());
        assertEquals("", gson.fromJson("{\"url\":null}", Danmaku.class).getUrl());
    }

    @Test public void equalityUsesOnlyNormalizedUrlAndRemainsOverridable() {
        Danmaku first = Danmaku.from("https://example.test/a.xml");
        Danmaku second = Danmaku.from(first.getUrl());
        second.setName("Other name");
        second.setSelected(true);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(first.getUrl().hashCode(), first.hashCode());
        assertNotEquals(first, Danmaku.from("https://example.test/b.xml"));
        assertNotEquals(first, null);
        assertNotEquals(first, "https://example.test/a.xml");
        assertEquals(Danmaku.empty(), Danmaku.from(""));
        Danmaku derived = new Danmaku() {
            @Override public String getUrl() { return first.getUrl(); }
        };
        assertEquals(first, derived);
        assertEquals(first.getUrl(), derived.getName());
        assertFalse(derived.isEmpty());
        assertEquals(first.hashCode(), derived.hashCode());
    }

    @Test public void javaOverrideReturningNullKeepsLegacyEqualityFailure() {
        Danmaku derived = new Danmaku() {
            @Override public String getUrl() { return null; }
        };
        assertTrue(derived.equals(derived));
        assertEquals(0, derived.hashCode());
        assertThrows(NullPointerException.class, () -> derived.equals(Danmaku.empty()));
    }
}
