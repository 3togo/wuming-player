package com.wuming.android.tv.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.wuming.android.tv.bean.Site;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;

public class CloudStorageManagerTest {

    @Test
    public void downloadedProvidersCannotBecomeTrustedDeletionAdapters() throws Exception {
        CloudStorageManager.Result result = TrustedCloudRegistry.get(CloudStorageProvider.QUARK)
                .clear(Collections.emptyList());
        assertFalse(result.supported); // No test Android cookie jar means no authenticated account.
        assertEquals(0, result.deleted);
        assertTrue(result.message.contains("Sign in"));
    }

    @Test
    public void noCopyProvidersHaveConcreteNoOpCleanupAdapters() throws Exception {
        for (CloudStorageProvider provider : Arrays.asList(CloudStorageProvider.BAIDU, CloudStorageProvider.PAN123, CloudStorageProvider.TIANYI)) {
            CloudStorageManager.Result result = TrustedCloudRegistry.get(provider)
                    .clear(Collections.emptyList());
            assertTrue(result.supported);
            assertEquals(0, result.deleted);
        }
    }

    @Test
    public void trustedResultCarriesExactDeletedFileIds() {
        CloudStorageManager.Result result = new CloudStorageManager.Result(
                true, 2, 4096, "", Arrays.asList("file-1", "file-2"));

        assertTrue(result.supported);
        assertEquals(2, result.deleted);
        assertEquals(4096, result.freedBytes);
        assertEquals(Arrays.asList("file-1", "file-2"), result.deletedIds);
    }

    @Test
    public void cleanupRejectsConflictingAndUntrackedOutcomes() {
        var record = CloudCacheRecord.create(CloudStorageProvider.ALIPAN, "account", "folder",
                "file", "video", "owner", 1024);
        var records = java.util.List.of(record);
        var conflicting = new CloudStorageManager.Result(true, 1, 1024, "",
                java.util.List.of("file"), java.util.List.of("file"), java.util.List.of());
        assertFalse(CloudStorageManager.validate(CloudStorageProvider.ALIPAN, records, conflicting).supported);
        var untracked = new CloudStorageManager.Result(true, 0, 0, "",
                java.util.List.of(), java.util.List.of(), java.util.List.of("unknown"));
        assertFalse(CloudStorageManager.validate(CloudStorageProvider.ALIPAN, records, untracked).supported);
    }

    @Test
    public void cacheRecordContainsOpaqueOwnershipBoundary() {
        CloudCacheRecord record = CloudCacheRecord.create(
                CloudStorageProvider.QUARK, "account-hash", "folder-id",
                "file-id", "video.mp4", "site", 1024);

        assertEquals("quark", record.provider);
        assertEquals("account-hash", record.accountHash);
        assertEquals("folder-id", record.remoteFolderId);
        assertEquals("file-id", record.remoteFileId);
        assertEquals(1024, record.sizeBytes);
    }

    @Test
    public void providerMatchingDoesNotTreatEveryUcSubstringAsUcDrive() throws Exception {
        Site unrelated = new Site();
        unrelated.setKey("documentary");
        unrelated.setName("Documentary");
        setSpiderType(unrelated);

        Site uc = new Site();
        uc.setKey("ucpan");
        uc.setName("UC云盘");
        setSpiderType(uc);

        assertFalse(CloudStorageProvider.UC.matches(unrelated));
        assertTrue(CloudStorageProvider.UC.matches(uc));
    }

    @Test
    public void externalLaunchTargetsOfficialCloudApps() {
        for (CloudStorageProvider provider : CloudStorageProvider.values()) {
            assertFalse(provider.getAndroidPackage() == null);
        }
        assertTrue(CloudStorageProvider.QUARK.getAndroidLaunchUrl().contains("/clouddrive/main"));
        assertTrue(CloudStorageProvider.UC.getAndroidLaunchUrl().contains("/clouddrive/main"));
        assertEquals("com.UCMobile.main.UCMobile",
                CloudStorageProvider.UC.getAndroidLaunchActivity());
        assertEquals("com.mfcloudcalculate.networkdisk",
                CloudStorageProvider.PAN123.getAndroidPackage());
    }

    @Test
    public void matches123NetdiskWithoutMatchingUnrelatedNumbers() throws Exception {
        Site pan123 = new Site();
        pan123.setKey("pan123");
        pan123.setName("123云盘");
        setSpiderType(pan123);

        Site unrelated = new Site();
        unrelated.setKey("provider123");
        unrelated.setName("Episode 123");
        setSpiderType(unrelated);

        assertTrue(CloudStorageProvider.PAN123.matches(pan123));
        assertFalse(CloudStorageProvider.PAN123.matches(unrelated));
    }

    private static void setSpiderType(Site site) throws Exception {
        Field field = Site.class.getDeclaredField("type");
        field.setAccessible(true);
        field.set(site, 3);
    }
}
