package com.wuming.android.tv.bean;

import static org.junit.Assert.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Test;

/** Legacy Java callers, serialized names, and snapshot isolation survive Kotlin conversion. */
public class KotlinPersistenceContractTest {
    private final Gson gson = new Gson();

    @Test public void drivePublicFieldsAndMethodsShareStorage() {
        Drive drive = new Drive();
        assertNull(drive.name);
        drive.name = "Disk";
        assertEquals("Disk", drive.getName());
        drive.setConfigJson("{}");
        assertEquals("{}", drive.configJson);
        drive.setName(null);
        assertEquals("", drive.getName());
        drive.type = 2;
        assertEquals(2, drive.getType());
        drive.setId(7);
        Drive copy = gson.fromJson(gson.toJson(drive), Drive.class);
        assertEquals(7, copy.getId());
        assertEquals(2, copy.type);
        assertEquals("{}", copy.configJson);
    }

    @Test public void trackRetainsNullableMetadataFluentApiAndSerializedSelection() {
        Track track = new Track(1, null, null);
        assertNull(track.getName());
        assertNull(track.getFormat());
        assertSame(track, track.key("movie"));
        assertSame(track, track.toggle());
        assertTrue(track.isSelected());
        JsonObject json = gson.toJsonTree(track).getAsJsonObject();
        assertTrue(json.get("selected").getAsBoolean());
        assertFalse(json.has("isSelected"));
        Track restored = gson.fromJson(json, Track.class);
        assertEquals("movie", restored.getKey());
        assertTrue(restored.isSelected());
        restored.toggle();
        assertFalse(restored.isSelected());
    }

    @Test public void viewingMetadataKeepsLegacyJsonAndCoreNormalization() {
        ViewingRecord record = gson.fromJson("{\"id\":\"session\",\"key\":null,\"typeName\":\"Drama\",\"actor\":null}", ViewingRecord.class);
        com.wuming.tv.core.library.recommend.ViewingRecord core = record;
        assertEquals("", core.getKey());
        assertEquals("Drama", core.getTypeName());
        assertEquals("", core.getActor());
        record.setKey("movie");
        record.setActor("Actor");
        record.setSiteKey(null);
        assertEquals("", record.getSiteKey());
        JsonObject json = gson.toJsonTree(record).getAsJsonObject();
        assertEquals("movie", json.get("key").getAsString());
        assertEquals("Actor", json.get("actor").getAsString());
        assertFalse(json.has("storedKey"));
        assertFalse(json.has("storedActor"));
        record.setKey(null);
        assertFalse(gson.toJsonTree(record.snapshot()).getAsJsonObject().has("key"));
    }

    @Test public void viewingSnapshotAndCompletionRemainIndependent() {
        ViewingRecord record = new ViewingRecord();
        assertNotNull(record.getId());
        record.setKey("movie");
        record.setWatchedDuration(1000);
        record.setLastWatchedAt(2000);
        record.setMediaDuration(100000);
        ViewingRecord snapshot = record.snapshot();
        record.setKey("other");
        record.update(1000, 3000, 4000, 100000);
        record.finish(4000, true);
        record.finish(5000, false);
        assertEquals(record.getId(), snapshot.getId());
        assertEquals("movie", snapshot.getKey());
        assertEquals(1000, snapshot.getWatchedDurationMillis());
        assertEquals(2000, snapshot.getLastWatchedAtMillis());
        assertFalse(snapshot.getCompleted());
        assertTrue(record.getCompleted());
        assertEquals(5000, record.getEndedAt());
        assertEquals(5000, record.getLastWatchedAtMillis());
    }
}
