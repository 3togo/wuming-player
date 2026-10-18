package com.wuming.android.tv.bean;

import static org.junit.Assert.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Objects;
import org.junit.Test;

public class FilterValueContractTest {
    private final Gson gson = new Gson();

    @Test public void categoryDefaultsSurviveJsonRoundTripWithoutInventingSelections() {
        // Providers distinguish an omitted default from an intentional empty All value.
        // Normalizing either null case to "" caused category requests with type="".
        String[] inputs = {"{\"key\":\"type\"}", "{\"key\":\"type\",\"init\":null}"};
        for (String input : inputs) {
            Filter filter = gson.fromJson(input, Filter.class);
            assertNull(filter.getInit());
            assertFalse(gson.toJsonTree(filter).getAsJsonObject().has("init"));
            assertNull(gson.fromJson(gson.toJson(filter.copy()), Filter.class).getInit());
        }
        Filter all = gson.fromJson("{\"key\":\"type\",\"init\":\"\"}", Filter.class);
        assertEquals("", gson.toJsonTree(all).getAsJsonObject().get("init").getAsString());
        assertEquals("", gson.fromJson(gson.toJson(all.copy()), Filter.class).getInit());
    }

    @Test public void categoryDefaultsDistinguishMissingFromExplicitAll() {
        Filter missing = gson.fromJson("{\"key\":\"type\",\"value\":[{\"n\":\"全部\",\"v\":\"tv_hot\"}]}", Filter.class);
        Filter all = gson.fromJson("{\"key\":\"type\",\"init\":\"\",\"value\":[{\"n\":\"全部\",\"v\":\"\"}]}", Filter.class);
        Filter selected = gson.fromJson("{\"key\":\"type\",\"init\":\"tv_hot\"}", Filter.class);
        assertNull(missing.getInit());
        assertNull(missing.copy().getInit());
        assertEquals("", all.getInit());
        assertEquals("", all.copy().getInit());
        assertEquals("tv_hot", selected.getInit());
        assertEquals("", all.setActivated(all.getInit()));
        assertTrue(all.getValue().get(0).isActivated());
    }

    @Test public void defaultsPreserveRawNullsAndNormalizedReads() {
        Filter filter = new Filter();
        assertEquals("", filter.getKey());
        assertNull(filter.getInit());
        assertEquals("", filter.getName());
        assertTrue(filter.getValue().isEmpty());
        assertEquals("{}", gson.toJson(filter));
        // The existing BeanUtils empty list is immutable; preserve this legacy failure.
        assertThrows(UnsupportedOperationException.class, filter::check);
        Value value = Value.create((String) null);
        assertEquals("", value.getN());
        assertEquals("", value.getV());
        assertFalse(value.isActivated());
        assertEquals("{}", gson.toJson(value));
        assertEquals(Value.create(""), value);
        assertEquals(Objects.hash(""), value.hashCode());
    }

    @Test public void filteringAndCopyKeepIndependentMutableValues() {
        Filter filter = gson.fromJson("{\"key\":\"year\",\"name\":\"Year\",\"init\":\"2026\","
                + "\"value\":[null,{\"n\":\"This year\",\"v\":\"2026\"},{\"v\":\"2025\"}]}", Filter.class);
        assertSame(filter, filter.check());
        assertEquals(2, filter.getValue().size());
        assertEquals("2026", filter.setActivated("2026"));
        assertTrue(filter.getValue().get(0).isActivated());
        assertFalse(filter.getValue().get(1).isActivated());
        Filter copy = filter.copy();
        assertEquals(filter.getKey(), copy.getKey());
        assertEquals(filter.getName(), copy.getName());
        assertEquals(filter.getInit(), copy.getInit());
        assertNotSame(filter.getValue(), copy.getValue());
        assertNotSame(filter.getValue().get(0), copy.getValue().get(0));
        assertTrue(copy.getValue().get(0).isActivated());
        copy.getValue().get(0).setV("changed");
        copy.getValue().get(0).setActivated(false);
        assertEquals("2026", filter.getValue().get(0).getV());
        assertTrue(filter.getValue().get(0).isActivated());
        JsonObject json = gson.toJsonTree(filter).getAsJsonObject();
        assertEquals("year", json.get("key").getAsString());
        assertFalse(json.getAsJsonArray("value").get(0).getAsJsonObject().has("activated"));
    }

    @Test public void activationTogglesAndEqualityOnlyUsesNormalizedValue() {
        Value first = Value.create("a");
        Value same = Value.create("Different label", "a");
        first.setActivated(same);
        assertTrue(first.isActivated());
        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertTrue(first.isSameItem(same));
        assertTrue(first.isSameContent(same));
        first.setActivated(same);
        assertFalse(first.isActivated());
        first.setActivated(true);
        first.setActivated(Value.create("b"));
        assertFalse(first.isActivated());
        assertThrows(NullPointerException.class, () -> first.setActivated((Value) null));
        first.setV(null);
        assertEquals(Value.create(""), first);
    }

    @Test public void copiesAndFactoryOverloadsKeepJavaSubclassContracts() {
        Value value = Value.create("name", "id");
        value.setActivated(true);
        Value copy = value.copy();
        assertNotSame(value, copy);
        assertEquals(value.getN(), copy.getN());
        assertEquals(value, copy);
        assertTrue(copy.isActivated());
        Filter derived = new Filter() {
            @Override public String getKey() { return "overridden"; }
        };
        assertEquals("overridden", derived.getKey());
        assertNotNull(Filter.CREATOR);
        assertNotNull(Value.CREATOR);
        assertEquals(3, Filter.CREATOR.newArray(3).length);
        assertEquals(2, Value.CREATOR.newArray(2).length);
    }
}
