package com.wuming.android.tv.db;

import static org.junit.Assert.*;

import com.wuming.android.tv.bean.Config;
import com.wuming.android.tv.bean.Device;
import com.wuming.android.tv.bean.History;
import com.wuming.android.tv.bean.Keep;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DataCacheTest {
    @Test public void readersNeverObserveListWhileWriterIsStillMutatingIt() throws Exception {
        DataCache cache = new DataCache(ignored -> {});
        CountDownLatch updating = new CountDownLatch(1), release = new CountDownLatch(1);
        Device original = new Device() {
            @Override public String getUuid() {
                updating.countDown();
                try { if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("Timed out"); }
                catch (InterruptedException error) { throw new AssertionError(error); }
                return "one";
            }
        };
        cache.putDevice(original);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var write = worker.submit(() -> cache.putDevice(device("two")));
            assertTrue(updating.await(3, TimeUnit.SECONDS));
            List<Device> snapshot = cache.getDevices();
            assertEquals(1, snapshot.size());
            release.countDown();
            write.get(3, TimeUnit.SECONDS);
            assertEquals(1, snapshot.size());
            assertSame(original, snapshot.get(0));
            assertEquals(2, cache.getDevices().size());
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test public void concurrentIndependentWritesCannotLoseCacheEntries() throws Exception {
        DataCache cache = new DataCache(ignored -> {});
        var workers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> writes = new ArrayList<>();
        try {
            for (int thread = 0; thread < 8; thread++) {
                int index = thread;
                writes.add(workers.submit(() -> {
                    start.await();
                    for (int i = 0; i < 50; i++) {
                        String key = index + ":" + i;
                        cache.putDevice(device(key));
                        History history = new History();
                        history.setKey(key);
                        cache.putHistory(history);
                        Keep keep = new Keep();
                        keep.setKey(key);
                        cache.putKeep(keep);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var write : writes) write.get(10, TimeUnit.SECONDS);
            assertEquals(400, cache.getDevices().size());
            assertEquals(400, cache.getHistories().size());
            assertEquals(400, cache.getKeeps().size());
        } finally { start.countDown(); workers.shutdownNow(); }
    }

    @Test public void configUpdatesAndDeletionLeaveExistingSnapshotsStable() {
        DataCache cache = new DataCache(ignored -> {});
        Config one = config(1, "one");
        Config two = config(2, "two");
        cache.putConfig(one);
        List<Config> original = cache.getConfigsByType(0);
        cache.putConfig(two);
        assertEquals(List.of(one), original);
        List<Config> both = cache.getVodConfigUrls();
        Config replacement = config(1, "one");
        cache.putConfig(replacement);
        assertSame(replacement, cache.getConfigsByType(0).get(0));
        assertSame(one, both.get(0));
        cache.removeConfig("one", 0);
        cache.removeConfig("two");
        assertEquals(List.of(one, two), both);
        assertTrue(cache.getConfigsByType(0).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> both.add(one));
    }

    @Test public void removalsPreserveReadersAndFilterOnlyRequestedHistory() {
        DataCache cache = new DataCache(ignored -> {});
        History one = new History(); one.setKey("one"); one.setCid(1);
        History two = new History(); two.setKey("two"); two.setCid(2);
        cache.putHistory(one); cache.putHistory(two);
        List<History> snapshot = cache.getHistories();
        cache.clearHistoriesByCid(1);
        assertEquals(List.of(two), cache.getHistories());
        cache.removeHistory("two");
        assertEquals(List.of(one, two), snapshot);
        assertTrue(cache.getHistories().isEmpty());
    }

    private static Device device(String id) { Device device = new Device(); device.setUuid(id); return device; }
    private static Config config(int id, String url) {
        Config config = new Config(); config.setId(id); config.setType(0); config.setUrl(url); return config;
    }
}
