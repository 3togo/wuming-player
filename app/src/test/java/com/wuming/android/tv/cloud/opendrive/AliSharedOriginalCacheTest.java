package com.wuming.android.tv.cloud.opendrive;

import static org.junit.Assert.*;
import com.wuming.android.tv.cloud.CloudCacheRecord;
import com.wuming.android.tv.cloud.CloudStorageProvider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class AliSharedOriginalCacheTest {
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }

    @Test
    public void activePlayerProtectsTrackedOriginalUntilReleased() throws Exception {
        Store store = new Store();
        CloudCacheRecord record = record("drive-protected", "active-copy", 0);
        store.record(record);
        var trashed = new java.util.concurrent.atomic.AtomicInteger();
        AliSharedOriginalCache cache = new AliSharedOriginalCache((path, body, token) -> {
            if (path.endsWith("/list")) return json("{\"items\":[{\"file_id\":\"active-copy\"}]}");
            trashed.incrementAndGet(); return new JsonObject();
        }, store, () -> 1_000_000L);
        String lease = OpenDriveCopyUsage.stage("alipan", record.accountHash, record.remoteFileId);
        try (var usage = OpenDriveCopyUsage.pin(java.util.Map.of(
                com.wuming.tv.core.playback.OpenDrivePlaybackHints.COPY_LEASE, lease))) {
            OpenDriveCopyUsage.retire("alipan", record.remoteFileId);
            assertFalse(cache.deleteTracked("drive-protected", record));
            assertEquals(0, trashed.get());
            assertEquals(1, store.active().size());
        }
        assertTrue(cache.deleteTracked("drive-protected", record));
        assertEquals(1, trashed.get());
    }

    @Test
    public void concurrentProviderInstancesReuseOneSavedSource() throws Exception {
        Store store = new Store();
        var copies = new java.util.concurrent.atomic.AtomicInteger();
        AliSharedOriginalCache.Requester requester = (path, body, token) -> {
            if (path.endsWith("/list")) return "root".equals(body.get("parent_file_id").getAsString())
                    ? json("{\"items\":[{\"file_id\":\"folder\",\"type\":\"folder\",\"name\":\"TVBoxCache\"}]}")
                    : json("{\"items\":[{\"file_id\":\"concurrent-copy\"}]}");
            if (path.endsWith("/batch")) {
                copies.incrementAndGet();
                return json("{\"responses\":[{\"status\":201,\"body\":{\"file_id\":\"concurrent-copy\"}}]}");
            }
            return json("{\"url\":\"https://cdn.example/original\"}");
        };
        var first = new AliSharedOriginalCache(requester, store, () -> 1_000_000L);
        var second = new AliSharedOriginalCache(requester, store, () -> 1_000_000L);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> first.resolve("parallel-drive", "share", "source", "token", 17));
            var b = pool.submit(() -> second.resolve("parallel-drive", "share", "source", "token", 17));
            a.get(3, java.util.concurrent.TimeUnit.SECONDS); b.get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1, copies.get()); assertEquals(1, store.active().size());
        } finally {
            pool.shutdownNow(); OpenDriveCopyUsage.retire("alipan", "concurrent-copy");
        }
    }

    @Test
    public void copiesExactSourceThenDownloadsOriginalAndReusesTrackedCopy() throws Exception {
        Store store = new Store();
        List<String> calls = new ArrayList<>();
        AliSharedOriginalCache cache = new AliSharedOriginalCache((path, body, token) -> {
            calls.add(path);
            if (path.endsWith("/list")) return "root".equals(body.get("parent_file_id").getAsString())
                    ? json("{\"items\":[{\"file_id\":\"cache-folder\",\"type\":\"folder\",\"name\":\"TVBoxCache\"}]}")
                    : json("{\"items\":[{\"file_id\":\"new-copy\"}]}");
            if (path.endsWith("/batch")) {
                assertEquals("share-token", token);
                JsonObject copy = body.getAsJsonArray("requests").get(0).getAsJsonObject().getAsJsonObject("body");
                assertEquals("share-id", copy.get("share_id").getAsString());
                assertEquals("source-id", copy.get("file_id").getAsString());
                assertEquals("cache-folder", copy.get("to_parent_file_id").getAsString());
                assertTrue(copy.get("auto_rename").getAsBoolean());
                assertFalse(copy.get("overwrite").getAsBoolean());
                return json("{\"responses\":[{\"status\":201,\"body\":{\"file_id\":\"new-copy\"}}]}");
            }
            assertTrue(path.endsWith("get_download_url"));
            assertEquals("", token);
            assertEquals("drive-id", body.get("drive_id").getAsString());
            assertEquals("new-copy", body.get("file_id").getAsString());
            assertFalse(body.has("share_id"));
            return json("{\"url\":\"https://cdn.example/original.mp4\"}");
        }, store, () -> 1_000_000L);
        assertTrue(cache.resolve("drive-id", "share-id", "source-id", "share-token", 17).has("url"));
        cache.resolve("drive-id", "share-id", "source-id", "share-token", 17);
        assertEquals(List.of("adrive/v3/file/list", "adrive/v4/batch", "v2/file/get_download_url", "adrive/v3/file/list", "v2/file/get_download_url"), calls);
        assertEquals(1, store.active().size());
        assertEquals(17, store.active().get(0).sizeBytes);
    }

    @Test
    public void deletedCopyWithStaleLocalRowIsReplacedBeforeDownload() throws Exception {
        Store store = new Store();
        var stale = record("drive-id", "deleted-copy", 999_999);
        stale.sourceSiteKey = AliSharedOriginalCache.OWNER + AliSharedOriginalCache.hash("share:source");
        store.record(stale);
        AliSharedOriginalCache cache = new AliSharedOriginalCache((path, body, token) -> {
            if (path.endsWith("/list")) return "root".equals(body.get("parent_file_id").getAsString())
                    ? json("{\"items\":[{\"type\":\"folder\",\"name\":\"TVBoxCache\",\"file_id\":\"folder\"}]}")
                    : json("{\"items\":[]}");
            if (path.endsWith("/batch")) return json("{\"responses\":[{\"status\":201,\"body\":{\"file_id\":\"replacement\"}}]}");
            assertEquals("v2/file/get_download_url", path);
            assertEquals("replacement", body.get("file_id").getAsString());
            return json("{\"url\":\"https://cdn.example/original\"}");
        }, store, () -> 1_000_000L);
        cache.resolve("drive-id", "share", "source", "token", 17);
        assertEquals(CloudCacheRecord.DELETED, stale.state);
        assertEquals(1, store.active().size());
        assertEquals("replacement", store.active().get(0).remoteFileId);
    }

    @Test
    public void cleanupRejectsWrongAccountAndUntrackedFilesAndPreservesMovedCopies() throws Exception {
        Store store = new Store();
        CloudCacheRecord record = record("drive-id", "saved", 0);
        store.record(record);
        List<String> calls = new ArrayList<>();
        AliSharedOriginalCache cache = new AliSharedOriginalCache((path, body, token) -> {
            calls.add(path);
            assertTrue(path.endsWith("/list"));
            return json("{\"items\":[{\"file_id\":\"user-file\"}]}");
        }, store, () -> 1_000_000L);
        assertThrows(IOException.class, () -> cache.deleteTracked("other-drive", record));
        assertThrows(IOException.class, () -> cache.deleteTracked("drive-id", record("drive-id", "unknown", 0)));
        assertTrue(calls.isEmpty());
        org.junit.Assert.assertFalse(cache.deleteTracked("drive-id", record));
        assertEquals(List.of("adrive/v3/file/list"), calls); // Moved/absent copy is never trashed elsewhere.
        assertTrue(store.active().isEmpty());
    }

    @Test
    public void cleanupOnlyTrashesTheVerifiedTrackedFile() throws Exception {
        Store store = new Store(); CloudCacheRecord record = record("drive-id", "saved", 0); store.record(record);
        List<String> deleted = new ArrayList<>();
        AliSharedOriginalCache cache = new AliSharedOriginalCache((path, body, token) -> {
            assertEquals("", token);
            if (path.endsWith("/list")) return json("{\"items\":[{\"file_id\":\"saved\"},{\"file_id\":\"user-file\"}]}");
            assertEquals("v2/recyclebin/trash", path);
            deleted.add(body.get("file_id").getAsString()); return new JsonObject();
        }, store, () -> 1_000_000L);
        assertTrue(cache.deleteTracked("drive-id", record));
        assertEquals(List.of("saved"), deleted);
    }

    @Test
    public void activeLeasesAreNotDeletedToMakeRoom() throws Exception {
        Store store = new Store();
        for (int i = 0; i < 10; i++) store.record(record("drive-id", "saved-" + i, 999_999));
        AliSharedOriginalCache cache = new AliSharedOriginalCache((path, body, token) -> {
            throw new AssertionError("Active copies must not be deleted or copied over");
        }, store, () -> 1_000_000L);
        assertThrows(IOException.class, () -> cache.resolve("drive-id", "different-share", "new-source", "token", 17));
        assertEquals(10, store.active().size());
    }

    @Test
    public void failedCopyIsNotRecordedOrReplacedWithPreview() throws Exception {
        Store store = new Store();
        AliSharedOriginalCache cache = new AliSharedOriginalCache((path, body, token) -> {
            if (path.endsWith("/list")) return json("{\"items\":[{\"type\":\"folder\",\"name\":\"TVBoxCache\",\"file_id\":\"folder\"}]}");
            assertEquals("adrive/v4/batch", path);
            return json("{\"responses\":[{\"status\":403,\"body\":{\"file_id\":\"not-a-success\"}}]}");
        }, store, () -> 1_000_000L);
        assertThrows(IOException.class, () -> cache.resolve("drive-id", "share", "source", "token", 17));
        assertTrue(store.active().isEmpty()); assertTrue(cache.createdIds().isEmpty());
    }

    private static CloudCacheRecord record(String drive, String file, long time) throws Exception {
        CloudCacheRecord value = CloudCacheRecord.create(CloudStorageProvider.ALIPAN,
                AliSharedOriginalCache.hash(drive), "folder", file, "Shared original",
                AliSharedOriginalCache.OWNER + "different-source-" + file, 17);
        value.createdAt = value.updatedAt = time;
        return value;
    }

    static final class Store implements AliSharedOriginalCache.Store {
        final List<CloudCacheRecord> values = new ArrayList<>();
        public List<CloudCacheRecord> active() { return values.stream().filter(x -> x.state == CloudCacheRecord.ACTIVE).toList(); }
        public void record(CloudCacheRecord value) { values.removeIf(x -> x.remoteFileId.equals(value.remoteFileId)); values.add(value); }
        public void deleted(CloudCacheRecord value) { value.state = CloudCacheRecord.DELETED; }
    }
}
