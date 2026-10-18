package com.wuming.android.tv.cloud.opendrive;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class AccountCredentialBackupPathTest {
    @Test public void exportFilenameUsesTimestampToSeconds() {
        String name = AccountCredentialBackup.suggestFilename();
        org.junit.Assert.assertTrue(name.matches("無名-credentials-[0-9]{8}-[0-9]{6}\\.tvbox-credentials\\.json"));
    }

    @Test public void relativePathsUseSdcard() {
        assertEquals("/sdcard/backup.json", AccountCredentialBackup.resolve("backup.json").getPath());
        assertEquals("/sdcard/Download/backup.json", AccountCredentialBackup.resolve("Download/backup.json").getPath());
    }

    @Test public void storagePrefixesAreNotDuplicated() {
        assertEquals("/sdcard/backup.json", AccountCredentialBackup.resolve("sdcard/backup.json").getPath());
        assertEquals("/sdcard/backup.json", AccountCredentialBackup.resolve("/sdcard/backup.json").getPath());
        assertEquals("/storage/emulated/0/backup.json", AccountCredentialBackup.resolve(" /storage/emulated/0/backup.json ").getPath());
    }

    @Test(expected = IllegalArgumentException.class) public void emptyPathIsRejected() {
        AccountCredentialBackup.resolve(" ");
    }

    @Test(expected = IllegalArgumentException.class) public void documentUriIsNotALocalPath() {
        AccountCredentialBackup.resolve("content://documents/backup.json");
    }
}
