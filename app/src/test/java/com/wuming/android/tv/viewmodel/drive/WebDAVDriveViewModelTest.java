package com.wuming.android.tv.viewmodel.drive;

import static org.junit.Assert.*;

import com.wuming.android.tv.bean.DriveFolderFile;
import com.google.gson.JsonObject;
import com.thegrizzlylabs.sardineandroid.DavResource;
import com.thegrizzlylabs.sardineandroid.Sardine;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class WebDAVDriveViewModelTest {
    @Test public void missingModificationDateLoadsWithUnknownTimestamp() throws Exception {
        WebDAVDriveViewModel model = model(new Resource("movie.mp4"));
        AtomicReference<List<DriveFolderFile>> files = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        try {
            model.loadData(callback(files, failure, completed));
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals("movie.mp4", files.get().get(1).name);
            assertEquals(Long.valueOf(0L), files.get().get(1).lastModifiedDate);
            assertEquals("MP4", files.get().get(1).fileType);
        } finally { model.close(); }
    }

    @Test public void malformedMetadataReachesFailureCallbackInsteadOfAbortingSilently() throws Exception {
        WebDAVDriveViewModel model = model(new Resource(null));
        AtomicReference<List<DriveFolderFile>> files = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        try {
            model.loadData(callback(files, failure, completed));
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertNull(files.get());
            assertNotNull(failure.get());
        } finally { model.close(); }
    }

    @Test public void foldersAndTrailingDotsHaveNoFileExtension() throws Exception {
        assertNull(WebDAVDriveViewModel.entry(null, "folder.ext", true, null).fileType);
        assertNull(WebDAVDriveViewModel.entry(null, "movie.", false, null).fileType);
        assertEquals(Long.valueOf(123L), WebDAVDriveViewModel.entry(null, "movie", false, new Date(123L)).lastModifiedDate);
    }

    private static AbstractDriveViewModel.LoadDataCallback callback(AtomicReference<List<DriveFolderFile>> files,
            AtomicReference<String> failure, CountDownLatch completed) {
        return new AbstractDriveViewModel.LoadDataCallback() {
            @Override public void callback(List<DriveFolderFile> list, boolean cached, int generation) {
                files.set(list);
                completed.countDown();
            }
            @Override public void fail(String message, int generation) {
                failure.set(message);
                completed.countDown();
            }
        };
    }

    private static WebDAVDriveViewModel model(DavResource resource) {
        Sardine client = (Sardine) Proxy.newProxyInstance(Sardine.class.getClassLoader(), new Class<?>[]{Sardine.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("list")) return List.of(resource);
                    throw new AssertionError("Unexpected call: " + method.getName());
                });
        WebDAVDriveViewModel model = new WebDAVDriveViewModel() {
            @Override protected Sardine getWebDAV(JsonObject config) { return client; }
        };
        DriveFolderFile drive = new DriveFolderFile(null, "fixture", 0, false, null, null);
        JsonObject config = new JsonObject();
        config.addProperty("url", "https://fixture.test");
        drive.setConfig(config);
        model.setCurrentDrive(drive);
        return model;
    }

    private static class Resource extends DavResource {
        final String name;
        Resource(String name) throws Exception {
            super("/movie.mp4", null, null, null, 0L, null, null, List.of(), null, List.of(), Map.of());
            this.name = name;
        }
        @Override public String getName() { return name; }
        @Override public Date getModified() { return null; }
        @Override public boolean isDirectory() { return false; }
    }
}
