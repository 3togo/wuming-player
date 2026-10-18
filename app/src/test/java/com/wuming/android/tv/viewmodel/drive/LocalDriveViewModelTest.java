package com.wuming.android.tv.viewmodel.drive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.wuming.android.tv.bean.DriveFolderFile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class LocalDriveViewModelTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void loadsDirectoryOffThreadAndCachesItsChildren() throws Exception {
        File video = folder.newFile("movie.mp4");
        File directory = folder.newFolder("shows");
        LocalDriveViewModel model = new LocalDriveViewModel();
        model.setCurrentDrive(new DriveFolderFile(
                null, folder.getRoot().getAbsolutePath(), 0, false, null, null));
        CountDownLatch loaded = new CountDownLatch(1);
        AtomicReference<List<DriveFolderFile>> result = new AtomicReference<>();

        String path = model.loadData(new AbstractDriveViewModel.LoadDataCallback() {
            @Override
            public void callback(List<DriveFolderFile> list, boolean alreadyHasChildren, int generation) {
                assertFalse(alreadyHasChildren);
                result.set(list);
                loaded.countDown();
            }

            @Override
            public void fail(String message, int generation) {
                loaded.countDown();
            }
        });

        assertEquals(folder.getRoot().getAbsolutePath(), path);
        assertTrue(loaded.await(3, TimeUnit.SECONDS));
        assertEquals(3, result.get().size());
        assertEquals(video.getName(), result.get().get(1).name);
        assertEquals(directory.getName(), result.get().get(2).name);
        model.close();
    }
}
