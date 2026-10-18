package com.wuming.android.tv.cloud.opendrive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

public class OpenDriveExternalEpisodeCacheTest {

    private static final String EXPIRED = "Deferred cloud episode expired; reopen the detail page";

    @Test
    public void handleMaterializesTheDeferredEpisodeOnce() {
        AtomicInteger calls = new AtomicInteger();
        String handle = OpenDriveExternalEpisodeCache.put(provider("quark"),
                OpenDrivePreparedExternal.of(() -> {
                    calls.incrementAndGet();
                    return "item-1";
                }), OpenDriveExternalEpisodeCache.retainedBytes("Quark", "payload"));

        assertEquals("item-1", OpenDriveExternalEpisodeCache.materialize("quark", handle).itemId());
        assertEquals("item-1", OpenDriveExternalEpisodeCache.materialize("quark", handle).itemId());
        assertEquals(1, calls.get());
    }

    @Test
    public void unknownHandleOrForeignProviderReportsTheExpiredContract() {
        String handle = OpenDriveExternalEpisodeCache.put(provider("uc"),
                OpenDrivePreparedExternal.of(() -> "item-2"), 0L);

        IllegalArgumentException foreign = assertThrows(IllegalArgumentException.class,
                () -> OpenDriveExternalEpisodeCache.materialize("quark", handle));
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> OpenDriveExternalEpisodeCache.materialize("uc", "missing-handle"));

        assertEquals(EXPIRED, foreign.getMessage());
        assertEquals(EXPIRED, missing.getMessage());
    }

    @Test
    public void retainedBytesMeasuresUtf8TextOfDeferredPayloads() {
        assertEquals(0L, OpenDriveExternalEpisodeCache.retainedBytes(null, null));
        assertEquals(1L, OpenDriveExternalEpisodeCache.retainedBytes("a", ""));
        assertEquals(4L, OpenDriveExternalEpisodeCache.retainedBytes("a", "中"));
        assertTrue(OpenDriveExternalEpisodeCache.retainedBytes("flag", "x".repeat(1024)) > 1024L);
    }

    private static OpenDriveProvider provider(String id) {
        return (OpenDriveProvider) Proxy.newProxyInstance(OpenDriveProvider.class.getClassLoader(),
                new Class<?>[]{OpenDriveProvider.class},
                (instance, method, args) -> "id".equals(method.getName()) ? id : null);
    }
}
