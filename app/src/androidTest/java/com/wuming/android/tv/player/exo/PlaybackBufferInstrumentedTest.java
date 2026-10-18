package com.wuming.android.tv.player.exo;

import static org.junit.Assert.*;

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

@RunWith(AndroidJUnit4.class)
public class PlaybackBufferInstrumentedTest {
    @Test public void highBitratePlaybackStopsAllocatingAndCanStartAtTheByteBudget() {
        for (String uri : new String[]{"https://example.test/8k.mp4", "file:///sdcard/8k.mp4",
                "https://example.test/live.m3u8"}) {
            SmoothnessLoadControl control = new SmoothnessLoadControl(10, 40L * 1024 * 1024);
            PlayerId id = new PlayerId("budget-test");
            control.onPrepared(id);
            Allocator allocator = control.getAllocator(id);
            List<Allocation> allocations = new ArrayList<>();
            try {
                assertEquals(0, control.getBackBufferDurationUs(id));
                assertFalse(control.retainBackBufferFromKeyframe(id));
                Timeline timeline = new SinglePeriodTimeline(60_000_000L, true, false, false,
                        null, MediaItem.fromUri(uri));
                LoadControl.Parameters parameters = new LoadControl.Parameters(id, timeline,
                        new MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)), 0,
                        100_000, 1f, true, true, C.TIME_UNSET, C.TIME_UNSET);
                assertTrue(control.shouldContinueLoading(parameters));
                assertFalse(control.shouldStartPlayback(parameters));
                while (allocator.getTotalBytesAllocated() < SmoothnessLoadControl.bufferBudgetBytes(40L * 1024 * 1024)) {
                    allocations.add(allocator.allocate());
                }
                assertFalse("Time target must not override the byte budget: " + uri,
                        control.shouldContinueLoading(parameters));
                assertTrue("A full byte budget must not deadlock rebuffering: " + uri,
                        control.shouldStartPlayback(parameters));
            } finally {
                for (Allocation allocation : allocations) allocator.release(allocation);
                assertEquals(0, allocator.getTotalBytesAllocated());
                control.onReleased(id);
            }
        }
    }
}
