package com.wuming.android.tv.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;

public class StoragePressureManagerTest {

    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void cleanupDeletesOldAppCacheButNeverTouchesLiveExoDirectory() throws Exception {
        File cache = temporaryFolder.newFolder("cache");
        File old = file(cache, "js/old.bin", 4096);
        File exo = file(cache, "exo/active.bin", 4096);
        old.setLastModified(1);
        old.getParentFile().setLastModified(1);
        exo.setLastModified(1);

        StoragePressureManager.cleanCache(cache, 2048);

        assertFalse(old.exists());
        assertTrue(exo.exists());
    }

    private static File file(File root, String path, int size) throws Exception {
        File file = new File(root, path);
        if (!file.getParentFile().mkdirs() && !file.getParentFile().isDirectory()) {
            throw new IllegalStateException("Could not create test directory");
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(new byte[size]);
        }
        return file;
    }
}
