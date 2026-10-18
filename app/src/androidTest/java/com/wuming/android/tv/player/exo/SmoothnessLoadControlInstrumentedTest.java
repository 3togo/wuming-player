package com.wuming.android.tv.player.exo;

import static com.wuming.android.tv.player.exo.PlaybackBufferBudget.MIB;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.SinglePeriodTimeline;
import androidx.media3.exoplayer.upstream.Allocation;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class SmoothnessLoadControlInstrumentedTest {

    @Test
    public void highBitratePlaybackStartsAtTheByteCapEvenBeforeRebufferDuration() {
        for (String uri : List.of("https://example.test/video.mp4", "file:///video.mp4")) {
            boolean localPlayback = uri.startsWith("file:");
            try (Harness h = new Harness(uri, memory(64, 48, false))) {
                h.allocateMiB(4);
                assertFalse(h.control.shouldContinueLoading(h.parameters(1, true)));
                assertTrue(h.control.shouldStartPlayback(h.parameters(1, true)));
                h.releaseMiB(2);
                if (localPlayback) {
                    // Local playback keeps Media3's own time decision (see the class doc): one
                    // buffered second already satisfies the local minimum, so falling back under
                    // the byte cap does not ask for more. Only the adaptive path resumes here.
                    assertFalse(h.control.shouldContinueLoading(h.parameters(1, true)));
                } else {
                    assertTrue(h.control.shouldContinueLoading(h.parameters(1, true)));
                }
            }
        }
    }

    @Test
    public void memoryPressureLowersBothLoadingAndPlaybackStartThresholds() {
        try (Harness h = new Harness("https://example.test/video.mp4", memory(128, 80, false))) {
            h.allocateMiB(8);
            assertTrue(h.control.shouldContinueLoading(h.parameters(1, false)));
            h.memory.set(memory(128, 80, true));
            h.now.addAndGet(5_000L);
            assertFalse(h.control.shouldContinueLoading(h.parameters(1, true)));
            assertTrue(h.control.shouldStartPlayback(h.parameters(1, true)));
            h.memory.set(memory(128, 80, false));
            h.now.addAndGet(5_000L);
            assertFalse(h.control.shouldContinueLoading(h.parameters(1, true)));
        }
    }

    @Test
    public void recoveryResumesAboveMedia3MinimumAfterDrainingTheByteCap() {
        try (Harness h = new Harness("https://example.test/video.mp4", memory(64, 48, false))) {
            h.allocateMiB(4);
            assertFalse(h.control.shouldContinueLoading(h.parameters(2, true)));
            h.releaseMiB(2);
            // Recovery raises the watermark from 9s to 14s. Media3 still waits for 9s here.
            assertTrue(h.control.shouldContinueLoading(h.parameters(13, false)));
            assertTrue(h.control.shouldContinueLoading(h.parameters(16, false)));
            assertFalse(h.control.shouldContinueLoading(h.parameters(36, false)));
        }
    }

    @Test
    public void repeatedPrepareAndReleaseDoesNotRetainAllocatedMedia() {
        for (int i = 0; i < 5; i++) {
            try (Harness h = new Harness("https://example.test/video.mp4", memory(64, 48, false))) {
                h.allocateMiB(4);
                h.releaseMiB(4);
                // The allocator handed out by DefaultLoadControl reports through
                // getTotalBufferBytesAllocated(), which needs the per-player loading state;
                // onStopped()/onReleased() remove it, so query while still prepared.
                assertEquals(0, h.allocator.getTotalBytesAllocated());
                h.control.onStopped(h.playerId);
            }
        }
    }

    private static PlaybackBufferBudget.Memory memory(int heap, int used, boolean pressure) {
        return new PlaybackBufferBudget.Memory((long) heap * MIB, (long) used * MIB,
                1024L * MIB, 64L * MIB, false, pressure);
    }

    private static final class Harness implements AutoCloseable {
        final PlayerId playerId = new PlayerId("buffer-test");
        final AtomicLong now = new AtomicLong(1_000L);
        final AtomicReference<PlaybackBufferBudget.Memory> memory;
        final SmoothnessLoadControl control;
        final Allocator allocator;
        final Timeline timeline;
        final List<Allocation> allocations = new ArrayList<>();

        Harness(String uri, PlaybackBufferBudget.Memory snapshot) {
            memory = new AtomicReference<>(snapshot);
            control = new SmoothnessLoadControl(1, memory::get, now::get);
            control.onPrepared(playerId);
            allocator = control.getAllocator(playerId);
            timeline = new SinglePeriodTimeline(300_000_000L, true, false, false,
                    null, MediaItem.fromUri(uri));
        }

        LoadControl.Parameters parameters(int bufferedSeconds, boolean rebuffering) {
            return new LoadControl.Parameters(playerId, timeline,
                    new MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)),
                    0L, bufferedSeconds * 1_000_000L, 1f, true, rebuffering,
                    C.TIME_UNSET, rebuffering ? 1_000L : C.TIME_UNSET);
        }

        void allocateMiB(int size) {
            for (int i = 0; i < size * MIB / allocator.getIndividualAllocationLength(); i++) {
                allocations.add(allocator.allocate());
            }
        }

        void releaseMiB(int size) {
            for (int i = 0; i < size * MIB / allocator.getIndividualAllocationLength(); i++) {
                allocator.release(allocations.remove(allocations.size() - 1));
            }
        }

        @Override public void close() {
            for (Allocation allocation : allocations) allocator.release(allocation);
            allocations.clear();
            control.onReleased(playerId);
        }
    }
}
