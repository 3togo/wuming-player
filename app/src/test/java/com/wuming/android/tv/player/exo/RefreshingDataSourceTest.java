package com.wuming.android.tv.player.exo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import com.wuming.android.tv.media.OpenDriveUriResolver;
import com.wuming.tv.core.media.PlaybackTarget;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class RefreshingDataSourceTest {

    @Test
    public void onlyLinkLifecycleStatusesTriggerProviderResolution() {
        assertTrue(RefreshingDataSource.isRefreshableStatus(401));
        assertTrue(RefreshingDataSource.isRefreshableStatus(403));
        assertTrue(RefreshingDataSource.isRefreshableStatus(404));
        assertTrue(RefreshingDataSource.isRefreshableStatus(410));
        assertTrue(RefreshingDataSource.isRefreshableStatus(416));
        assertFalse(RefreshingDataSource.isRefreshableStatus(429));
        assertFalse(RefreshingDataSource.isRefreshableStatus(500));
        assertFalse(RefreshingDataSource.isRefreshableStatus(503));
    }

    @Test
    public void parallelFailuresShareOneRefreshInsteadOfRefreshingEveryRange() throws Exception {
        PlaybackTarget initial = target("initial", Long.MAX_VALUE);
        PlaybackTarget refreshed = target("refreshed", Long.MAX_VALUE);
        AtomicInteger refreshes = new AtomicInteger();
        RefreshingDataSource.TargetState state = new RefreshingDataSource.TargetState(
                initial, "opendrive://local/test", uri -> {
                    refreshes.incrementAndGet();
                    return refreshed;
                });
        RefreshingDataSource.TargetSnapshot original = state.target(0, null);
        assertSame(initial, original.playback());
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<RefreshingDataSource.TargetSnapshot>> requests = new ArrayList<>();
            for (int i = 0; i < 12; i++) requests.add(pool.submit(() -> state.target(1, original)));
            for (Future<RefreshingDataSource.TargetSnapshot> request : requests) {
                assertSame(refreshed, request.get(5, TimeUnit.SECONDS).playback());
            }
            assertEquals(1, refreshes.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void concurrentRecoveryDoesNotSkipPastAUsableFailoverSource() throws Exception {
        PlaybackTarget initial = target("initial", Long.MAX_VALUE);
        PlaybackTarget refreshed = target("refreshed", Long.MAX_VALUE);
        PlaybackTarget backup = target("backup", Long.MAX_VALUE);
        AtomicInteger failovers = new AtomicInteger();
        OpenDriveUriResolver resolver = new OpenDriveUriResolver() {
            @Override public PlaybackTarget resolve(String uri) { return refreshed; }
            @Override public PlaybackTarget failover(String uri) { failovers.incrementAndGet(); return backup; }
        };
        RefreshingDataSource.TargetState state = new RefreshingDataSource.TargetState(
                initial, "opendrive://local/test", resolver);
        RefreshingDataSource.TargetSnapshot original = state.target(0, null);
        RefreshingDataSource.TargetSnapshot replacement = state.target(1, original);
        assertSame(refreshed, replacement.playback());
        assertSame(backup, state.target(2, replacement).playback());
        assertSame(backup, state.target(2, replacement).playback());
        assertSame(backup, state.target(1, original).playback());
        assertEquals(1, failovers.get());
    }

    @Test
    public void failedProactiveRefreshKeepsAStillValidUrlButNotAnExpiredOne() throws Exception {
        PlaybackTarget valid = target("valid", 20_000L);
        OpenDriveUriResolver unavailable = uri -> { throw new IllegalStateException("offline"); };
        RefreshingDataSource.TargetState state = new RefreshingDataSource.TargetState(
                valid, "opendrive://local/test", unavailable, () -> 10_000L);
        RefreshingDataSource.TargetSnapshot original = state.target(0, null);
        assertSame(valid, original.playback());
        assertThrows(IOException.class, () -> state.target(1, original));
        RefreshingDataSource.TargetState expired = new RefreshingDataSource.TargetState(
                valid, "opendrive://local/test", unavailable, () -> 21_000L);
        assertThrows(IOException.class, () -> expired.target(0, null));
    }

    @Test
    public void missingRefreshResultDoesNotCorruptTheSharedTarget() throws Exception {
        PlaybackTarget initial = target("initial", 5_000L);
        PlaybackTarget refreshed = target("refreshed", Long.MAX_VALUE);
        AtomicInteger calls = new AtomicInteger();
        RefreshingDataSource.TargetState state = new RefreshingDataSource.TargetState(
                initial, "opendrive://local/test", uri -> calls.incrementAndGet() == 1 ? null : refreshed,
                () -> 10_000L);
        assertThrows(IOException.class, () -> state.target(0, null));
        assertSame(refreshed, state.target(0, null).playback());
    }

    @Test
    public void refreshIsSharedEvenWhenTheResolverReturnsTheSameObject() throws Exception {
        PlaybackTarget initial = target("initial", Long.MAX_VALUE);
        AtomicInteger refreshes = new AtomicInteger();
        RefreshingDataSource.TargetState state = new RefreshingDataSource.TargetState(
                initial, "opendrive://local/test", uri -> {
                    refreshes.incrementAndGet();
                    return initial;
                });
        RefreshingDataSource.TargetSnapshot original = state.target(0, null);
        RefreshingDataSource.TargetSnapshot replacement = state.target(1, original);
        assertSame(initial, replacement.playback());
        assertSame(replacement, state.target(1, original));
        assertEquals(1, refreshes.get());
    }

    @Test
    public void recentlyRefreshedShortLivedUrlIsReusedWhileStillValid() throws Exception {
        PlaybackTarget initial = target("initial", Long.MAX_VALUE);
        PlaybackTarget refreshed = target("short-lived", 25_000L);
        AtomicInteger refreshes = new AtomicInteger();
        RefreshingDataSource.TargetState state = new RefreshingDataSource.TargetState(
                initial, "opendrive://local/test", uri -> {
                    refreshes.incrementAndGet();
                    return refreshed;
                }, () -> 10_000L);
        RefreshingDataSource.TargetSnapshot original = state.target(0, null);
        assertSame(refreshed, state.target(1, original).playback());
        assertSame(refreshed, state.target(1, original).playback());
        assertEquals(1, refreshes.get());
    }

    private static PlaybackTarget target(String name, long expiry) {
        return new PlaybackTarget("https://example.test/" + name, Map.of(), expiry);
    }
}
