package com.wuming.android.tv.cloud.opendrive;

import com.wuming.android.tv.cloud.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class OpenDriveCacheCleanerTest {
    @Test public void allCopyProvidersSkipActivePlayersAndDeleteAfterRelease() throws Exception {
        for (var provider : List.of(CloudStorageProvider.UC, CloudStorageProvider.QUARK, CloudStorageProvider.ALIPAN)) {
            var record = CloudCacheRecord.create(provider, "account", "folder", "lease-integration-copy", "sample", "owner", 10);
            String token = OpenDriveCopyUsage.stage(provider.getId(), "account", record.remoteFileId);
            try (var pin = OpenDriveCopyUsage.pin(java.util.Map.of(
                    com.wuming.tv.core.playback.OpenDrivePlaybackHints.COPY_LEASE, token))) {
                OpenDriveCopyUsage.retire(provider.getId(), record.remoteFileId);
                var result = OpenDriveCacheCleaner.collect(provider, List.of(record), item -> {
                    fail("Active playback must not reach remote deletion"); return true;
                });
                assertEquals(List.of(record.remoteFileId), result.skippedIds);
                assertEquals(0, result.deleted);
            }
            assertEquals(1, OpenDriveCacheCleaner.collect(provider, List.of(record), item -> true).deleted);
        }
    }

    @Test public void partialCleanupKeepsExactOutcomesAndContinuesAfterFailure() {
        var records = List.of(record("failed"), record("moved"), record("deleted"));
        var result = OpenDriveCacheCleaner.collect(CloudStorageProvider.ALIPAN, records, item -> {
            if (item.remoteFileId.equals("failed")) throw new IOException("secret response");
            return !item.remoteFileId.equals("moved");
        });
        assertTrue(result.supported);
        assertFalse(result.successful());
        assertEquals(List.of("failed"), result.failedIds);
        assertEquals(List.of("moved"), result.skippedIds);
        assertEquals(List.of("deleted"), result.deletedIds);
        assertEquals(1024L, result.freedBytes);
        assertEquals(CloudCacheRecord.ACTIVE, records.get(0).state);
        assertFalse(result.message.contains("secret"));
    }

    @Test public void wrongProviderAndInactiveRecordsNeverReachDeletion() {
        var wrongProvider = record("wrong"); wrongProvider.provider = "uc";
        var inactive = record("inactive"); inactive.state = CloudCacheRecord.DELETED;
        var result = OpenDriveCacheCleaner.collect(CloudStorageProvider.ALIPAN,
                List.of(wrongProvider, inactive), item -> { fail("Must not delete"); return true; });
        assertEquals(0, result.deleted);
        assertEquals(List.of("wrong", "inactive"), result.skippedIds);
    }

    @Test public void resultOwnsAnImmutableSnapshot() {
        var ids = new ArrayList<>(List.of("one"));
        var result = new CloudStorageManager.Result(true, 1, 1, "", ids, ids, ids);
        ids.clear();
        assertEquals(List.of("one"), result.deletedIds);
        assertEquals(List.of("one"), result.failedIds);
        assertThrows(UnsupportedOperationException.class, () -> result.skippedIds.clear());
    }

    private static CloudCacheRecord record(String id) {
        return CloudCacheRecord.create(CloudStorageProvider.ALIPAN, "account", "folder", id,
                "sample", "owner", 1024);
    }
}
