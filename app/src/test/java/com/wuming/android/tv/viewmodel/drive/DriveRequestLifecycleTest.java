package com.wuming.android.tv.viewmodel.drive;

import static org.junit.Assert.*;

import com.wuming.android.tv.bean.DriveFolderFile;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class DriveRequestLifecycleTest {
    @Test public void everyProviderInvalidatesEarlierResultsEvenWhenNextFolderIsCached() {
        for (AbstractDriveViewModel model : List.of(new LocalDriveViewModel(), new AlistDriveViewModel(), new WebDAVDriveViewModel())) {
            DriveFolderFile drive = node("/root");
            drive.setConfig(new JsonObject());
            model.setCurrentDrive(drive);
            List<Integer> generations = new ArrayList<>();
            AbstractDriveViewModel.LoadDataCallback callback = new AbstractDriveViewModel.LoadDataCallback() {
                @Override public void callback(List<DriveFolderFile> list, boolean cached, int generation) {
                    assertTrue(cached);
                    generations.add(generation);
                }
                @Override public void fail(String message, int generation) { org.junit.Assert.fail(message); }
            };
            model.setCurrentDriveNote(node("/one"));
            model.loadData(callback);
            model.setCurrentDriveNote(node("/two"));
            model.loadData(callback);
            assertFalse(model.isCurrentLoad(generations.get(0)));
            assertTrue(model.isCurrentLoad(generations.get(1)));
            model.close();
            assertFalse(model.isCurrentLoad(generations.get(1)));
            assertNull(model.loadData(callback));
            assertEquals(2, generations.size());
        }
    }

    @Test public void navigationCancelsInFlightWorkAndCloseRejectsFurtherDispatch() throws Exception {
        TestModel model = new TestModel();
        CountDownLatch started = new CountDownLatch(1), canceled = new CountDownLatch(1), release = new CountDownLatch(1);
        int old = model.beginLoad();
        try {
            model.submitLoad(old, () -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException expected) { canceled.countDown(); }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            model.beginLoad();
            assertTrue(canceled.await(2, TimeUnit.SECONDS));
            assertFalse(model.isCurrentLoad(old));
            model.close();
            AtomicInteger calls = new AtomicInteger();
            model.submitLoad(old, calls::incrementAndGet);
            assertEquals(0, calls.get());
            assertEquals(-1, model.beginLoad());
        } finally { release.countDown(); model.close(); }
    }

    private static DriveFolderFile node(String name) {
        DriveFolderFile node = new DriveFolderFile(null, name, 0, false, null, null);
        node.setChildren(new ArrayList<>());
        return node;
    }

    private static class TestModel extends AbstractDriveViewModel {
        @Override public String loadData(LoadDataCallback callback) { return null; }
    }
}
