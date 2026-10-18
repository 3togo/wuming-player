package com.wuming.android.tv.bean;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class DevicePersistenceTest {
    @Test
    public void nullableDatabaseColumnsKeepNormalizedPublicAndPersistedValues() {
        Device device = new Device();
        device.setDatabaseUuid(null);
        device.setDatabaseName(null);
        device.setDatabaseIp(null);
        assertEquals("", device.getUuid());
        assertEquals("", device.getName());
        assertEquals("", device.getIp());
        assertEquals("", device.getDatabaseUuid());
        assertEquals("", device.getDatabaseName());
        assertEquals("", device.getDatabaseIp());

        device.setUuid("renderer-id");
        device.setName("Living room");
        device.setIp("192.0.2.1");
        assertEquals("renderer-id", device.getDatabaseUuid());
        assertEquals("Living room", device.getDatabaseName());
        assertEquals("192.0.2.1", device.getDatabaseIp());
    }

    @Test
    public void gsonWireNamesAndDeviceIdentityRemainCompatible() {
        Gson gson = new Gson();
        Device device = gson.fromJson(
                "{\"uuid\":\"renderer-id\",\"name\":null,\"ip\":\"192.0.2.1\"}", Device.class);
        assertEquals("renderer-id", device.getDatabaseUuid());
        assertEquals("", device.getDatabaseName());
        JsonObject json = gson.toJsonTree(device).getAsJsonObject();
        assertEquals("renderer-id", json.get("uuid").getAsString());
        assertEquals("192.0.2.1", json.get("ip").getAsString());
        assertFalse(json.has("databaseUuid"));
        assertFalse(json.has("databaseName"));
        assertFalse(json.has("databaseIp"));
        Device restored = new Device();
        restored.setDatabaseUuid(device.getDatabaseUuid());
        assertEquals(device, restored);
        assertEquals(device.hashCode(), restored.hashCode());
    }
}
