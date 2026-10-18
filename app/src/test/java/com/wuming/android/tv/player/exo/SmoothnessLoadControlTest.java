package com.wuming.android.tv.player.exo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SmoothnessLoadControlTest {

    @Test
    public void byteBudgetStopsLoadingButAllowsBufferedPlaybackToStart() {
        SmoothnessLoadControl control = new SmoothnessLoadControl(5,
                () -> new PlaybackBufferBudget.Memory(16L << 20, 0, -1, 0, false, false), () -> 1000L);
        androidx.media3.exoplayer.analytics.PlayerId player = androidx.media3.exoplayer.analytics.PlayerId.UNSET;
        control.onPrepared(player);
        androidx.media3.exoplayer.LoadControl.Parameters buffered = parameters(player, 1_000_000L);
        assertTrue(control.shouldContinueLoading(buffered));
        assertFalse(control.shouldStartPlayback(buffered));
        androidx.media3.exoplayer.upstream.Allocator allocator = control.getAllocator(player);
        java.util.List<androidx.media3.exoplayer.upstream.Allocation> blocks = new java.util.ArrayList<>();
        try {
            while (allocator.getTotalBytesAllocated() < PlaybackBufferBudget.MIN_BYTES) {
                blocks.add(allocator.allocate());
            }
            assertFalse(control.shouldContinueLoading(buffered));
            assertTrue(control.shouldStartPlayback(buffered));
        } finally {
            for (var block : blocks) allocator.release(block);
            control.onReleased(player);
        }
    }

    @Test
    public void preloadAllocationDoesNotExhaustTheActivePlayersBudget() {
        SmoothnessLoadControl control = new SmoothnessLoadControl(5,
                () -> new PlaybackBufferBudget.Memory(16L << 20, 0, -1, 0, false, false), () -> 1000L);
        var player = androidx.media3.exoplayer.analytics.PlayerId.UNSET;
        var preload = androidx.media3.exoplayer.analytics.PlayerId.PRELOAD;
        control.onPrepared(player);
        control.onPrepared(preload);
        var preloadAllocator = control.getAllocator(preload);
        java.util.List<androidx.media3.exoplayer.upstream.Allocation> blocks = new java.util.ArrayList<>();
        try {
            while (preloadAllocator.getTotalBytesAllocated() < PlaybackBufferBudget.MIN_BYTES) {
                blocks.add(preloadAllocator.allocate());
            }
            var buffered = parameters(player, 1_000_000L);
            assertTrue(control.shouldContinueLoading(buffered));
            assertFalse(control.shouldStartPlayback(buffered));
        } finally {
            for (var block : blocks) preloadAllocator.release(block);
            control.onReleased(preload);
            control.onReleased(player);
        }
    }

    @Test
    public void preloadLifecyclePreservesHealthWhilePlaybackLifecycleClearsIt() throws Exception {
        PlaybackHealthMonitor health = new PlaybackHealthMonitor();
        SmoothnessLoadControl control = new SmoothnessLoadControl(5,
                () -> new PlaybackBufferBudget.Memory(64L << 20, 0, -1, 0, false, false), () -> 1000L, health);
        androidx.media3.exoplayer.analytics.PlayerId player = androidx.media3.exoplayer.analytics.PlayerId.UNSET;
        androidx.media3.exoplayer.analytics.PlayerId preload = androidx.media3.exoplayer.analytics.PlayerId.PRELOAD;
        try {
            health.record(1000L, 1500, 7L);
            control.onPrepared(preload);
            java.lang.reflect.Field adaptiveActive = SmoothnessLoadControl.class.getDeclaredField("adaptiveActive");
            adaptiveActive.setAccessible(true);
            adaptiveActive.setBoolean(control, true);
            control.shouldContinueLoading(parameters(preload, 1_000_000L));
            assertTrue(adaptiveActive.getBoolean(control));
            assertEquals(1500, health.snapshot(1000L).marginPermille());
            control.onStopped(preload);
            control.onReleased(preload);
            assertEquals(1500, health.snapshot(1000L).marginPermille());
            control.onPrepared(player);
            assertEquals(0, health.snapshot(1000L).marginPermille());
            health.record(1000L, 1500, 7L);
            control.onStopped(player);
            assertEquals(0, health.snapshot(1000L).marginPermille());
            health.record(1000L, 1500, 7L);
            control.onReleased(player);
            assertEquals(0, health.snapshot(1000L).marginPermille());
        } finally { health.clear(); }
    }

    private static androidx.media3.exoplayer.LoadControl.Parameters parameters(
            androidx.media3.exoplayer.analytics.PlayerId player, long bufferedUs) {
        androidx.media3.common.Timeline timeline = new androidx.media3.exoplayer.source.SinglePeriodTimeline(
                60_000_000L, true, false, false, null, androidx.media3.common.MediaItem.EMPTY);
        return new androidx.media3.exoplayer.LoadControl.Parameters(player,
                timeline,
                new androidx.media3.exoplayer.source.MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)),
                0L, bufferedUs, 1f, true, false, androidx.media3.common.C.TIME_UNSET,
                androidx.media3.common.C.TIME_UNSET);
    }

    @Test public void sampleBudgetScalesWithHeapAndStopsGrowingOnLargeDevices() {
        long mib = 1024L * 1024;
        assertEquals(8 * mib, SmoothnessLoadControl.bufferBudgetBytes(32 * mib));
        assertEquals(32 * mib, SmoothnessLoadControl.bufferBudgetBytes(128 * mib));
        assertEquals(96 * mib, SmoothnessLoadControl.bufferBudgetBytes(512 * mib));
        assertEquals(96 * mib, SmoothnessLoadControl.bufferBudgetBytes(Long.MAX_VALUE));
    }

    @Test
    public void onlyProgressiveOnDemandNetworkMediaUsesAdaptiveBuffering() {
        assertTrue(SmoothnessLoadControl.supportsAdaptiveBuffer(
                "https://cdn.example/video.mp4", "video/mp4", false));
        assertTrue(SmoothnessLoadControl.supportsAdaptiveBuffer(
                "opendrive://local/asset/opaque", null, false));

        assertFalse(SmoothnessLoadControl.supportsAdaptiveBuffer(
                "https://cdn.example/master.m3u8", null, false));
        assertFalse(SmoothnessLoadControl.supportsAdaptiveBuffer(
                "https://cdn.example/manifest.mpd", "application/dash+xml", false));
        assertFalse(SmoothnessLoadControl.supportsAdaptiveBuffer(
                "file:///storage/emulated/0/video.mp4", "video/mp4", false));
        assertFalse(SmoothnessLoadControl.supportsAdaptiveBuffer(
                "magnet:?xt=urn:btih:opaque", null, false));
        assertFalse(SmoothnessLoadControl.supportsAdaptiveBuffer(
                "https://cdn.example/video.mp4", "video/mp4", true));
    }
}
