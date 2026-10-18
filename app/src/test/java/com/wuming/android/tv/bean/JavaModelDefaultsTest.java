package com.wuming.android.tv.bean;

import static org.junit.Assert.*;

import androidx.media3.common.C;
import com.google.gson.Gson;
import org.junit.Test;

/** Java construction and raw Gson defaults must survive independently of Room's schema. */
public class JavaModelDefaultsTest {
    private final Gson gson = new Gson();

    @Test public void historyRetainsUnassignedKeyAndPlaybackSentinels() {
        History history = new History();
        assertEquals("", history.getKey());
        assertEquals("", history.getVodName());
        assertEquals(C.TIME_UNSET, history.getPosition());
        assertEquals(C.TIME_UNSET, history.getDuration());
        assertEquals(C.TIME_UNSET, history.getOpening());
        assertEquals(C.TIME_UNSET, history.getEnding());
        assertEquals(-1, history.getScale());
        assertEquals(1f, history.getSpeed(), 0f);
        assertFalse(gson.toJsonTree(history).getAsJsonObject().has("key"));
        History restored = gson.fromJson("{\"key\":null,\"vodName\":null}", History.class);
        assertEquals("", restored.getKey());
        assertEquals("", restored.getVodName());
        restored.setKey("site@@@movie");
        assertEquals("site@@@movie", gson.fromJson(gson.toJson(restored), History.class).getKey());
    }

    @Test public void deviceKeepsBoxedIdAndNormalizesOnlyAtItsGetters() {
        Device device = new Device();
        assertNull(device.getId());
        assertEquals("", device.getUuid());
        assertEquals("", device.getName());
        assertEquals("", device.getIp());
        assertFalse(gson.toJsonTree(device).getAsJsonObject().has("uuid"));
        device.setId(7);
        device.setUuid(null);
        Device copy = gson.fromJson(gson.toJson(device), Device.class);
        assertEquals(Integer.valueOf(7), copy.getId());
        assertEquals("", copy.getUuid());
    }

    @Test public void liveAndSiteKeepRawAbsentKeysWhileNormalizingGetters() {
        Live live = new Live();
        Site site = new Site();
        assertEquals("", live.getName());
        assertEquals("", site.getKey());
        assertFalse(gson.toJsonTree(live).getAsJsonObject().has("name"));
        assertFalse(gson.toJsonTree(site).getAsJsonObject().has("key"));
        assertEquals("", gson.fromJson("{\"name\":null}", Live.class).getName());
        assertEquals("", gson.fromJson("{\"key\":null}", Site.class).getKey());
    }
}
