package com.wuming.android.tv.bean;

import static org.junit.Assert.assertEquals;

import android.os.Parcel;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.core.os.BundleCompat;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

@RunWith(AndroidJUnit4.class)
public class StateRestorationInstrumentedTest {

    @Test
    public void valueParcelKeepsLegacyFieldOrderAndActivationByte() {
        Value original = Value.create("label", "id");
        original.setActivated(true);
        Parcel parcel = Parcel.obtain();
        try {
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            assertEquals("label", parcel.readString());
            assertEquals("id", parcel.readString());
            assertEquals(1, parcel.readByte());
            assertEquals(0, parcel.dataAvail());
            parcel.setDataPosition(0);
            Value restored = Value.CREATOR.createFromParcel(parcel);
            assertEquals(original, restored);
            assertEquals("label", restored.getN());
            assertEquals(true, restored.isActivated());
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void filterParcelKeepsNullFieldsAndIndependentValueList() {
        Filter original = new com.google.gson.Gson().fromJson(
                "{\"key\":\"year\",\"value\":[{\"n\":\"2026\",\"v\":\"2026\"}]}", Filter.class);
        original.setActivated("2026");
        Parcel parcel = Parcel.obtain();
        try {
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            assertEquals("year", parcel.readString());
            assertEquals(null, parcel.readString());
            assertEquals(null, parcel.readString());
            parcel.setDataPosition(0);
            Filter restored = Filter.CREATOR.createFromParcel(parcel);
            assertEquals("year", restored.getKey());
            assertEquals("", restored.getName());
            assertEquals(null, restored.getInit());
            assertEquals(1, restored.getValue().size());
            assertEquals(true, restored.getValue().get(0).isActivated());
            restored.getValue().get(0).setV("changed");
            assertEquals("2026", original.getValue().get(0).getV());
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void filterParcelPreservesExplicitEmptyAllAndSelectedDefaults() {
        // Empty is a provider selection, unlike an absent default; Parcel must preserve both.
        for (String initial : new String[] {"", "tv_hot"}) {
            Filter original = new com.google.gson.Gson().fromJson(
                    "{\"key\":\"type\",\"init\":\"" + initial
                            + "\",\"value\":[{\"n\":\"Selection\",\"v\":\"" + initial + "\"}]}", Filter.class);
            original.setActivated(initial);
            Parcel parcel = Parcel.obtain();
            try {
                original.writeToParcel(parcel, 0);
                parcel.setDataPosition(0);
                Filter restored = Filter.CREATOR.createFromParcel(parcel);
                assertEquals(initial, restored.getInit());
                assertEquals(initial, restored.getValue().get(0).getV());
                assertEquals(true, restored.getValue().get(0).isActivated());
                restored.getValue().get(0).setV("changed");
                assertEquals(initial, original.getValue().get(0).getV());
            } finally {
                parcel.recycle();
            }
        }
    }

    @Test
    public void vodScreenResultSurvivesFrameworkParcelRoundTrip() {
        Class type = new Class();
        type.setTypeId("hot-tv");
        type.setTypeName("Hot TV");
        Vod vod = new Vod();
        vod.setId("vod-1");
        vod.setName("Fixture Movie");
        vod.setPic("https://example.test/poster.jpg");
        Result original = Result.list(List.of(vod));
        original.setTypes(List.of(type));
        original.setMsg("state-message");
        original.setHeader(Map.of("Referer", "https://example.test/"));
        original.setSubs(List.of(Sub.create("English", "https://example.test/sub.vtt", "en", "text/vtt")));
        original.setKey("site-key");
        original.setMediaUri("opendrive://local/asset/movie-1");
        original.setParse(1);

        Parcel parcel = Parcel.obtain();
        try {
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            Result restored = Result.CREATOR.createFromParcel(parcel);

            assertEquals("hot-tv", restored.getTypes().get(0).getTypeId());
            assertEquals("vod-1", restored.getList().get(0).getId());
            assertEquals("Fixture Movie", restored.getList().get(0).getName());
            assertEquals("state-message", restored.getMsg());
            assertEquals("https://example.test/", restored.getHeader().get("Referer"));
            assertEquals("English", restored.getSubs().get(0).getName());
            assertEquals("site-key", restored.getKey());
            assertEquals("opendrive://local/asset/movie-1", restored.getMediaUri());
            assertEquals(1, restored.getParse().intValue());
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void nestedFolderArgumentsSurviveProcessStyleBundleMarshalling() {
        Bundle original = new Bundle();
        original.putString("key", "fixture-site");
        original.putString("typeId", "ju-ju");
        original.putBoolean("folder", true);
        original.putBoolean("nested", true);
        original.putParcelable("style", Style.list());
        HashMap<String, String> extend = new HashMap<>();
        extend.put("area", "china");
        extend.put("year", "2026");
        original.putSerializable("extend", extend);

        Parcel parcel = Parcel.obtain();
        try {
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            Bundle restored = Bundle.CREATOR.createFromParcel(parcel);
            restored.setClassLoader(Style.class.getClassLoader());

            assertEquals("fixture-site", restored.getString("key"));
            assertEquals("ju-ju", restored.getString("typeId"));
            assertEquals(true, restored.getBoolean("folder"));
            assertEquals(true, restored.getBoolean("nested"));
            assertEquals(Style.list(), BundleCompat.getParcelable(restored, "style", Style.class));
            @SuppressWarnings("unchecked")
            HashMap<String, String> restoredExtend = (HashMap<String, String>) BundleCompat.getSerializable(
                    restored, "extend", HashMap.class);
            assertEquals("china", restoredExtend.get("area"));
            assertEquals("2026", restoredExtend.get("year"));
        } finally {
            parcel.recycle();
        }
    }
}
